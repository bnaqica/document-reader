package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.CatalogEntry;
import com.bnaqica.documentreader.dto.request.DocumentTypeRequest;
import com.bnaqica.documentreader.dto.response.DocumentTypeResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** CRUD for the document type catalog, plus resolving a name to its type. */
public interface DocumentTypeService {

    List<DocumentTypeResponse> findAll();

    /** Throws {@code ResourceNotFoundException} for an unknown id. */
    DocumentTypeResponse get(UUID id);

    /** The type whose own name or one of whose aliases reduces to the same key as {@code name}. */
    Optional<DocumentTypeResponse> resolve(String name);

    /** Every type's name and description, for classification prompts. */
    List<CatalogEntry> catalog();

    /**
     * Creates a {@code MANUAL} type at schema version 1, in its own transaction. A caller that catches the
     * {@code DocumentTypeKeyConflictException} from a lost race can therefore still use its own transaction, for
     * example to resolve the winning type.
     */
    DocumentTypeResponse create(DocumentTypeRequest request);

    /** Replaces name, description and data schema; the schema version goes up only if the schema changes. */
    DocumentTypeResponse update(UUID id, DocumentTypeRequest request);

    /**
     * Removes the type and its aliases. Throws {@code DocumentTypeInUseException} while documents use it; the
     * caller's transaction is then rolled back, so don't carry on in it after catching that.
     */
    void delete(UUID id);
}
