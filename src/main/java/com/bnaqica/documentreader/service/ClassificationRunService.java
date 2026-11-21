package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.NewClassificationRun;
import com.bnaqica.documentreader.dto.response.ClassificationRunRecord;
import java.util.List;
import java.util.UUID;

/** Classification runs of a document. Rows are written once; a duplicate run number is refused by the database. */
public interface ClassificationRunService {

    ClassificationRunRecord save(UUID documentId, NewClassificationRun run);

    /** Every initial run before every tie-break run, each round in run order. */
    List<ClassificationRunRecord> findByDocument(UUID documentId);
}
