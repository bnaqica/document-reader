package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.client.VisionClient;
import com.bnaqica.documentreader.dto.CallContext;
import com.bnaqica.documentreader.dto.PageImageRef;
import com.bnaqica.documentreader.dto.TypeMatchAnswer;
import com.bnaqica.documentreader.dto.request.DocumentTypeAliasRequest;
import com.bnaqica.documentreader.dto.request.DocumentTypeRequest;
import com.bnaqica.documentreader.dto.response.DocumentTypeResponse;
import com.bnaqica.documentreader.exception.DocumentTypeKeyConflictException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * An action service over the catalog CRUD services (§10.2). The first name saved wins: a later variant becomes an
 * alias, and two documents adding the same name at once are settled by the unique key, the loser using the winner's
 * type.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentTypeCatalogServiceImpl implements DocumentTypeCatalogService {

    /** The model's reasoning can quote borrower data, so it never becomes a description. */
    static final String MODEL_TYPE_DESCRIPTION = "Named by the model during classification; not yet reviewed";

    private final DocumentTypeService documentTypes;
    private final DocumentTypeAliasService aliases;
    private final VisionClient vision;

    @Override
    public DocumentTypeResponse createOrMatch(CallContext context, List<PageImageRef> pages, String proposedName) {
        Optional<DocumentTypeResponse> meanwhile = documentTypes.resolve(proposedName);
        if (meanwhile.isPresent()) {
            log.debug("Document {}: the proposed type was added meanwhile", context.documentId());
            return meanwhile.get();
        }
        TypeMatchAnswer match = vision.confirmNewType(context, pages, proposedName, documentTypes.catalog());
        Optional<DocumentTypeResponse> existing = match.matchesExisting()
                ? Optional.ofNullable(match.existingName()).flatMap(documentTypes::resolve)
                : Optional.empty();
        if (existing.isPresent()) {
            return aliasOf(existing.get(), proposedName);
        }
        JsonNode schema = vision.draftSchema(context, pages, proposedName);
        try {
            DocumentTypeResponse created = documentTypes.createFromModel(
                    new DocumentTypeRequest(proposedName, MODEL_TYPE_DESCRIPTION, schema), context.documentId());
            log.info("Document {} added document type {}", context.documentId(), created.id());
            return created;
        } catch (DocumentTypeKeyConflictException e) {
            // another document added the same name between our check and our insert
            return documentTypes.resolve(proposedName).orElseThrow(() -> e);
        }
    }

    private DocumentTypeResponse aliasOf(DocumentTypeResponse type, String proposedName) {
        try {
            aliases.add(type.id(), new DocumentTypeAliasRequest(proposedName));
            log.info("Added an alias to document type {}", type.id());
            return type;
        } catch (DocumentTypeKeyConflictException e) {
            // the name was added meanwhile, as this alias or for another type: whatever holds it now wins
            return documentTypes.resolve(proposedName).orElse(type);
        }
    }
}
