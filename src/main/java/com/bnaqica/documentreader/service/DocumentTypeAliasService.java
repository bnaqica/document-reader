package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.request.DocumentTypeAliasRequest;
import com.bnaqica.documentreader.dto.response.DocumentTypeAliasResponse;
import java.util.List;
import java.util.UUID;

/** CRUD for the other names a document type resolves by. */
public interface DocumentTypeAliasService {

    /** Throws {@code ResourceNotFoundException} for an unknown type. */
    List<DocumentTypeAliasResponse> findByType(UUID documentTypeId);

    /** Throws {@code DocumentTypeKeyConflictException} if the alias's key belongs to any type, its own included. */
    DocumentTypeAliasResponse add(UUID documentTypeId, DocumentTypeAliasRequest request);

    void delete(UUID documentTypeId, UUID aliasId);
}
