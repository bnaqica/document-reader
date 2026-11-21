package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.NewExtractionResult;
import com.bnaqica.documentreader.dto.response.ExtractionResultRecord;
import java.util.Optional;
import java.util.UUID;

/** A document's extraction result. A document has at most one; a second is refused by the database. */
public interface ExtractionResultService {

    ExtractionResultRecord save(UUID documentId, NewExtractionResult result);

    Optional<ExtractionResultRecord> find(UUID documentId);
}
