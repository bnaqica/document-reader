package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.NewSourceFile;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import com.bnaqica.documentreader.entity.SourceFile;
import com.bnaqica.documentreader.entity.SourceFileStatus;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.repository.SourceFileRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SourceFileServiceImpl implements SourceFileService {

    private static final String RESOURCE = "Source file";

    private final SourceFileRepository repository;

    @Override
    @Transactional
    public List<SourceFileRecord> createAll(UUID batchId, List<NewSourceFile> files) {
        List<SourceFile> created = new ArrayList<>();
        for (int index = 0; index < files.size(); index++) {
            NewSourceFile file = files.get(index);
            created.add(SourceFile.builder()
                    .id(UUID.randomUUID())
                    .batchId(batchId)
                    .fileIndex(index)
                    .originalFilename(file.originalFilename())
                    .contentType(file.contentType())
                    .sizeBytes(file.sizeBytes())
                    .sha256(file.sha256())
                    .pageCount(file.pageCount())
                    .status(SourceFileStatus.RECEIVED)
                    .build());
        }
        repository.saveAll(created);
        log.info("Created {} source files for batch {}", created.size(), batchId);
        return created.stream().map(SourceFileServiceImpl::toRecord).toList();
    }

    @Override
    public SourceFileRecord get(UUID id) {
        return toRecord(find(id));
    }

    @Override
    public List<SourceFileRecord> findByBatch(UUID batchId) {
        return repository.findByBatchIdOrderByFileIndex(batchId).stream()
                .map(SourceFileServiceImpl::toRecord)
                .toList();
    }

    @Override
    @Transactional
    public void markStored(UUID id, String s3Bucket, String s3Key) {
        find(id).markStored(s3Bucket, s3Key);
        log.info("Stored source file {}", id);
    }

    @Override
    @Transactional
    public void updateStatus(UUID id, SourceFileStatus status, String failureReason) {
        find(id).updateStatus(status, failureReason);
        log.info("Source file {} is now {}", id, status);
    }

    private SourceFile find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    private static SourceFileRecord toRecord(SourceFile file) {
        return SourceFileRecord.builder()
                .id(file.getId())
                .batchId(file.getBatchId())
                .fileIndex(file.getFileIndex())
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .sizeBytes(file.getSizeBytes())
                .sha256(file.getSha256())
                .s3Bucket(file.getS3Bucket())
                .s3Key(file.getS3Key())
                .pageCount(file.getPageCount())
                .status(file.getStatus())
                .failureReason(file.getFailureReason())
                .build();
    }
}
