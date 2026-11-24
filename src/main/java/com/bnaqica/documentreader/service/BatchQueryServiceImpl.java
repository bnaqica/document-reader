package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.dto.response.BatchResultResponse;
import com.bnaqica.documentreader.dto.response.ClassificationResultRecord;
import com.bnaqica.documentreader.dto.response.ClassificationRunRecord;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.dto.response.DocumentResultResponse;
import com.bnaqica.documentreader.dto.response.DocumentResultResponse.Classification;
import com.bnaqica.documentreader.dto.response.DocumentResultResponse.DocumentTypeSummary;
import com.bnaqica.documentreader.dto.response.DocumentResultResponse.Pages;
import com.bnaqica.documentreader.dto.response.DocumentResultResponse.TieBreak;
import com.bnaqica.documentreader.dto.response.DocumentTypeResponse;
import com.bnaqica.documentreader.dto.response.FileResultResponse;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import com.bnaqica.documentreader.entity.ClassificationRound;
import com.bnaqica.documentreader.entity.DocumentTypeOrigin;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An action service over the record CRUD services. It reads one batch's rows in one read-only transaction and builds
 * the §7.3 envelope; ownership is checked on the batch, as for the record endpoints.
 */
@Service
@RequiredArgsConstructor
public class BatchQueryServiceImpl implements BatchQueryService {

    private final BatchService batches;
    private final SourceFileService sourceFiles;
    private final DocumentService documents;
    private final DocumentTypeService documentTypes;
    private final ClassificationRunService classificationRuns;
    private final ClassificationResultService classificationResults;
    private final ExtractionResultService extractionResults;

    @Override
    @Transactional(readOnly = true)
    public BatchResultResponse getResult(UUID batchId, Caller caller) {
        BatchRecord batch = batches.get(batchId);
        if (!caller.mayRead(batch.requestedBy())) {
            throw new ResourceNotFoundException("Batch", batchId);
        }
        List<DocumentRecord> docs = documents.findByBatch(batchId); // file order, then page order
        Map<UUID, Long> documentsPerFile =
                docs.stream().collect(Collectors.groupingBy(DocumentRecord::sourceFileId, Collectors.counting()));
        Map<UUID, DocumentTypeResponse> types = documentTypes.findAll().stream()
                .collect(Collectors.toMap(DocumentTypeResponse::id, Function.identity()));
        return BatchResultResponse.builder()
                .batchId(batch.id())
                .status(batch.status())
                .loanPurpose(batch.loanPurposeCode())
                .receivedAt(batch.receivedAt())
                .completedAt(batch.completedAt())
                .error(batch.failureReason())
                .files(sourceFiles.findByBatch(batchId).stream() // index order
                        .map(file -> toFile(file, documentsPerFile.getOrDefault(file.id(), 0L)))
                        .toList())
                .documents(docs.stream().map(doc -> toDocument(doc, types)).toList())
                .build();
    }

    private static FileResultResponse toFile(SourceFileRecord file, long documentCount) {
        return FileResultResponse.builder()
                .id(file.id())
                .index(file.fileIndex())
                .fileName(file.originalFilename())
                .pageCount(file.pageCount())
                .status(file.status())
                .documentCount((int) documentCount)
                .error(file.failureReason())
                .build();
    }

    private DocumentResultResponse toDocument(DocumentRecord doc, Map<UUID, DocumentTypeResponse> types) {
        DocumentResultResponse.DocumentResultResponseBuilder result = DocumentResultResponse.builder()
                .id(doc.id())
                .sourceFileId(doc.sourceFileId())
                .pages(new Pages(doc.startPage(), doc.endPage()))
                .status(doc.status())
                .truncated(doc.truncated())
                .error(doc.failureReason());
        Optional<ClassificationResultRecord> classification = classificationResults.find(doc.id());
        classification.ifPresent(decided -> {
            DocumentTypeResponse type = types.get(decided.documentTypeId());
            result.documentType(new DocumentTypeSummary(type.name(), type.origin() == DocumentTypeOrigin.MODEL));
            result.classification(toClassification(decided, classificationRuns.findByDocument(doc.id()), types));
        });
        extractionResults
                .find(doc.id())
                .ifPresent(extracted -> result.summary(extracted.summary()).data(extracted.data()));
        return result.build();
    }

    /** Votes come from the successful runs of each round, so they don't depend on how the result stored them. */
    private static Classification toClassification(
            ClassificationResultRecord decided,
            List<ClassificationRunRecord> runs,
            Map<UUID, DocumentTypeResponse> types) {
        List<ClassificationRunRecord> initial = successful(runs, ClassificationRound.INITIAL);
        TieBreak tieBreak = null;
        if (decided.tieBroken()) {
            List<ClassificationRunRecord> tiebreak = successful(runs, ClassificationRound.TIEBREAK);
            tieBreak = new TieBreak(tiebreak.size(), votes(tiebreak, types), decided.decidedBy());
        }
        return Classification.builder()
                .runs(initial.size())
                .votes(votes(initial, types))
                .agreement(decided.agreement())
                .tieBreak(tieBreak)
                .build();
    }

    private static List<ClassificationRunRecord> successful(
            List<ClassificationRunRecord> runs, ClassificationRound round) {
        return runs.stream()
                .filter(run -> run.round() == round && run.succeeded() && run.documentTypeId() != null)
                .toList();
    }

    /** Count per type name, most votes first; ties keep the order the types were first voted for. */
    private static Map<String, Integer> votes(
            List<ClassificationRunRecord> runs, Map<UUID, DocumentTypeResponse> types) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        runs.forEach(run -> counts.merge(types.get(run.documentTypeId()).name(), 1, Integer::sum));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first, LinkedHashMap::new));
    }
}
