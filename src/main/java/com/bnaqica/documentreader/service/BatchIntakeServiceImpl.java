package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.client.DocumentStorageClient;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.NewBatch;
import com.bnaqica.documentreader.dto.NewSourceFile;
import com.bnaqica.documentreader.dto.event.SeparateFilePayload;
import com.bnaqica.documentreader.dto.request.BatchUploadRequest;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.dto.response.LoanPurposeResponse;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import com.bnaqica.documentreader.entity.BatchStatus;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.entity.SourceFileStatus;
import com.bnaqica.documentreader.exception.BatchValidationException;
import com.bnaqica.documentreader.exception.BatchValidationException.FileError;
import com.bnaqica.documentreader.exception.UnreadableDocumentException;
import com.bnaqica.documentreader.exception.UnsupportedDocumentException;
import com.bnaqica.documentreader.processors.ContentTypeDetector;
import com.bnaqica.documentreader.processors.DetectedType;
import com.bnaqica.documentreader.processors.ImageConversionProcessor;
import com.bnaqica.documentreader.processors.S3Keys;
import com.bnaqica.documentreader.processors.Sha256;
import com.bnaqica.documentreader.processors.WebhookUrlValidator;
import com.bnaqica.documentreader.publisher.OutboxPublisher;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

/**
 * Intake in the order of §6: validate the request and every file, record the batch, store each original, then queue
 * the files for separation, and wait for the result in wait mode. Not transactional itself, because the wait must
 * run outside any transaction.
 */
@Slf4j
@Service
public class BatchIntakeServiceImpl implements BatchIntakeService {

    static final String STORAGE_FAILED = "Storing the files failed";

    /** When files fail with different statuses, the answer is the first of these that any file failed with. */
    private static final List<HttpStatus> CHECK_ORDER =
            List.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, HttpStatus.CONTENT_TOO_LARGE, HttpStatus.UNPROCESSABLE_CONTENT);

    private final LoanPurposeService loanPurposes;
    private final WebhookUrlValidator webhookUrls;
    private final DocumentReaderProperties properties;
    private final ContentTypeDetector detector;
    private final ImageConversionProcessor conversion;
    private final BatchService batches;
    private final SourceFileService sourceFiles;
    private final DocumentStorageClient storage;
    private final S3Keys keys;
    private final OutboxPublisher outbox;
    private final BatchCompletionService completion;
    private final CompletionWaitService completionWait;
    private final BatchQueryService query;
    private final TransactionTemplate transaction;

    public BatchIntakeServiceImpl(
            LoanPurposeService loanPurposes,
            WebhookUrlValidator webhookUrls,
            DocumentReaderProperties properties,
            ContentTypeDetector detector,
            ImageConversionProcessor conversion,
            BatchService batches,
            SourceFileService sourceFiles,
            DocumentStorageClient storage,
            S3Keys keys,
            OutboxPublisher outbox,
            BatchCompletionService completion,
            CompletionWaitService completionWait,
            BatchQueryService query,
            PlatformTransactionManager transactionManager) {
        this.loanPurposes = loanPurposes;
        this.webhookUrls = webhookUrls;
        this.properties = properties;
        this.detector = detector;
        this.conversion = conversion;
        this.batches = batches;
        this.sourceFiles = sourceFiles;
        this.storage = storage;
        this.keys = keys;
        this.outbox = outbox;
        this.completion = completion;
        this.completionWait = completionWait;
        this.query = query;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public IntakeOutcome submit(BatchUploadRequest request, Caller caller) {
        if (caller.subject() == null || caller.subject().isBlank()) {
            // A batch records who sent it, and only they may read it; checked before any file is read
            throw new AccessDeniedException("The token has no subject");
        }
        checkFields(request);
        checkTotalSize(request.files());
        List<CheckedFile> files = checkFiles(request.files());
        boolean async = "true".equalsIgnoreCase(request.async());

        UUID batchId = record(request, async, caller, files).id();
        List<SourceFileRecord> records = sourceFiles.findByBatch(batchId);
        BatchStatus status;
        try {
            queue(batchId, records, storeOriginals(files, records));
            status = BatchStatus.PROCESSING;
        } catch (RuntimeException e) {
            // Any failure here, not only S3's: the batch must still reach a final status and be announced
            log.error("Storing the files of batch {} failed", batchId, e);
            fail(batchId, records);
            status = BatchStatus.FAILED;
        }
        if (async) {
            return new Accepted(batchId, status);
        }
        // Outside any transaction: the batch is committed, and each re-read sees the latest status
        if (completionWait.await(batchId, properties.waitSettings().timeout())) {
            return new Finished(query.getResult(batchId, caller));
        }
        return new Accepted(batchId, status);
    }

    /** Transaction 1: the batch and its files, all {@code RECEIVED}, committed before anything goes to S3. */
    private BatchRecord record(BatchUploadRequest request, boolean async, Caller caller, List<CheckedFile> files) {
        return transaction.execute(status -> {
            BatchRecord batch = batches.create(new NewBatch(
                    request.loanPurpose(), async, async ? request.webhookUrl() : null, caller.subject(), files.size()));
            sourceFiles.createAll(
                    batch.id(),
                    files.stream()
                            .map(file -> new NewSourceFile(
                                    file.file().getOriginalFilename(),
                                    file.type().mimeType(),
                                    file.size(),
                                    file.sha256(),
                                    file.pageCount()))
                            .toList());
            return batch;
        });
    }

    /** Puts each original under its file's id, with the extension of its detected type. No transaction is open. */
    private List<String> storeOriginals(List<CheckedFile> files, List<SourceFileRecord> records) {
        List<String> stored = new ArrayList<>();
        for (int index = 0; index < files.size(); index++) {
            CheckedFile file = files.get(index);
            String key = keys.original(records.get(index).id(), file.type().extension());
            storage.put(key, bytes(file.file()), file.type().mimeType());
            stored.add(key);
        }
        return stored;
    }

    /** Transaction 2: every file stored, the batch processing, and one separation message per file. */
    private void queue(UUID batchId, List<SourceFileRecord> records, List<String> keysByIndex) {
        String bucket = properties.s3().bucket();
        transaction.executeWithoutResult(status -> {
            for (int index = 0; index < records.size(); index++) {
                UUID fileId = records.get(index).id();
                sourceFiles.markStored(fileId, bucket, keysByIndex.get(index));
                outbox.publish(MessageType.SEPARATE_FILE, fileId, new SeparateFilePayload(fileId));
            }
            batches.updateStatus(batchId, BatchStatus.PROCESSING, null);
        });
        log.info("Queued {} files of batch {} for separation", records.size(), batchId);
    }

    /**
     * Every file, then the batch, fails with one reason. Completing the batch sends its notice and, for an async batch,
     * queues its webhook, so the caller hears of the failure the usual way. Objects already put stay in S3.
     */
    private void fail(UUID batchId, List<SourceFileRecord> records) {
        transaction.executeWithoutResult(status -> {
            for (SourceFileRecord file : records) {
                sourceFiles.updateStatus(file.id(), SourceFileStatus.FAILED, STORAGE_FAILED);
            }
            completion.onBatchFailed(batchId, STORAGE_FAILED);
        });
    }

    /** The request's own fields, in the order of §7.1; the first failure is the answer. */
    private void checkFields(BatchUploadRequest request) {
        String code = request.loanPurpose();
        if (code == null || loanPurposes.findActive(code).isEmpty()) {
            throw badRequest("loanPurpose must be one of: "
                    + loanPurposes.findAllActive().stream()
                            .map(LoanPurposeResponse::code)
                            .collect(Collectors.joining(", ")));
        }
        int maxFiles = properties.batch().maxFiles();
        List<MultipartFile> files = request.files();
        if (files == null || files.isEmpty() || files.size() > maxFiles) {
            throw badRequest("Send between 1 and " + maxFiles + " files");
        }
        String asyncValue = request.async();
        if (asyncValue != null && !asyncValue.equalsIgnoreCase("true") && !asyncValue.equalsIgnoreCase("false")) {
            throw badRequest("async must be true or false");
        }
        boolean async = "true".equalsIgnoreCase(asyncValue);
        boolean hasUrl = request.webhookUrl() != null && !request.webhookUrl().isBlank();
        if (async && !hasUrl) {
            throw badRequest("webhookUrl is required when async is true");
        }
        if (!async && hasUrl) {
            throw badRequest("webhookUrl is only used when async is true");
        }
        if (async) {
            webhookUrls.validate(request.webhookUrl());
        }
    }

    /** Before any file is read, so a batch over the limit is never parsed. */
    private void checkTotalSize(List<MultipartFile> files) {
        long total = files.stream().mapToLong(MultipartFile::getSize).sum();
        DataSize limit = properties.batch().maxTotalSize();
        if (total > limit.toBytes()) {
            throw new BatchValidationException(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "The files total " + megabytesRoundedUp(total) + " MB; the limit is " + limit.toMegabytes()
                            + " MB");
        }
    }

    /**
     * Checks every file, each stopping at its first failure, and refuses the batch if any failed. Only one file's
     * bytes are held at a time.
     */
    private List<CheckedFile> checkFiles(List<MultipartFile> files) {
        List<CheckedFile> checked = new ArrayList<>();
        List<FileError> errors = new ArrayList<>();
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            try {
                checked.add(check(file));
            } catch (FileRefusedException e) {
                errors.add(new FileError(index, file.getOriginalFilename(), e.status.value(), e.getMessage()));
            }
        }
        if (!errors.isEmpty()) {
            HttpStatus status = CHECK_ORDER.stream()
                    .filter(candidate -> errors.stream().anyMatch(error -> error.status() == candidate.value()))
                    .findFirst()
                    .orElseThrow();
            String detail =
                    errors.size() == 1 ? "1 file can't be accepted" : errors.size() + " files can't be accepted";
            throw new BatchValidationException(status, detail, errors);
        }
        return checked;
    }

    /** Type from content (415), then size (413), then readability and page count (422). */
    private CheckedFile check(MultipartFile file) {
        byte[] bytes = bytes(file);
        DetectedType type;
        try {
            type = detector.detect(bytes);
        } catch (UnsupportedDocumentException e) {
            throw new FileRefusedException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
        }
        DataSize maxSize = properties.file().maxSize();
        if (bytes.length > maxSize.toBytes()) {
            throw new FileRefusedException(
                    HttpStatus.CONTENT_TOO_LARGE, "The file is larger than " + maxSize.toMegabytes() + " MB");
        }
        int pages;
        try {
            pages = conversion.pageCount(bytes, type);
        } catch (UnreadableDocumentException e) {
            throw new FileRefusedException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        }
        int maxPages = properties.file().maxPages();
        if (pages > maxPages) {
            throw new FileRefusedException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "The file has " + pages + " pages; the limit is " + maxPages);
        }
        return new CheckedFile(file, type, bytes.length, Sha256.hex(new ByteArrayInputStream(bytes)), pages);
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long megabytesRoundedUp(long bytes) {
        long megabyte = DataSize.ofMegabytes(1).toBytes();
        return (bytes + megabyte - 1) / megabyte;
    }

    private static BatchValidationException badRequest(String detail) {
        return new BatchValidationException(HttpStatus.BAD_REQUEST, detail);
    }

    /** A file that passed every check, with what intake records about it. Its bytes are read again to store it. */
    private record CheckedFile(MultipartFile file, DetectedType type, long size, String sha256, int pageCount) {}

    /** One file's first failed check; collected, never thrown out of intake. */
    private static final class FileRefusedException extends RuntimeException {

        private final HttpStatus status;

        FileRefusedException(HttpStatus status, String detail) {
            super(detail, null, false, false);
            this.status = status;
        }
    }
}
