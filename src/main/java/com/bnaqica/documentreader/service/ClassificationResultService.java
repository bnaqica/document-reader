package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.NewClassificationResult;
import com.bnaqica.documentreader.dto.response.ClassificationResultRecord;
import java.util.Optional;
import java.util.UUID;

/** A document's classification result. A document has at most one; a second is refused by the database. */
public interface ClassificationResultService {

    ClassificationResultRecord save(UUID documentId, NewClassificationResult result);

    Optional<ClassificationResultRecord> find(UUID documentId);
}
