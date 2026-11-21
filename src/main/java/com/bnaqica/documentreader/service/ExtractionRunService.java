package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.NewExtractionRun;
import com.bnaqica.documentreader.dto.response.ExtractionRunRecord;
import com.bnaqica.documentreader.entity.ExtractionStage;
import java.util.List;
import java.util.UUID;

/** Extraction runs of a document. Rows are written once; a duplicate pass number is refused by the database. */
public interface ExtractionRunService {

    ExtractionRunRecord save(UUID documentId, NewExtractionRun run);

    /** Every stage, in the order the stages run, each in pass order. */
    List<ExtractionRunRecord> findByDocument(UUID documentId);

    /** One stage, in pass order. */
    List<ExtractionRunRecord> findByDocumentAndStage(UUID documentId, ExtractionStage stage);
}
