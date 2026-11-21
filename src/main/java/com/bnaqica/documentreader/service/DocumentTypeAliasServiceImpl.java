package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.request.DocumentTypeAliasRequest;
import com.bnaqica.documentreader.dto.response.DocumentTypeAliasResponse;
import com.bnaqica.documentreader.entity.DocumentTypeAlias;
import com.bnaqica.documentreader.exception.DocumentTypeKeyConflictException;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.exception.UnusableNameException;
import com.bnaqica.documentreader.processors.TypeNameNormalizer;
import com.bnaqica.documentreader.repository.DocumentTypeAliasRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Depends on {@link DocumentTypeService}, never the other way round: aliases go with their type through the FK. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentTypeAliasServiceImpl implements DocumentTypeAliasService {

    private static final String RESOURCE = "Document type alias";

    private final DocumentTypeAliasRepository repository;
    private final DocumentTypeService documentTypes;

    @Override
    public List<DocumentTypeAliasResponse> findByType(UUID documentTypeId) {
        documentTypes.get(documentTypeId);
        return repository.findAllByDocumentTypeIdOrderByAlias(documentTypeId).stream()
                .map(DocumentTypeAliasServiceImpl::toResponse)
                .toList();
    }

    /** Its own transaction, so a lost race doesn't abort the caller's. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DocumentTypeAliasResponse add(UUID documentTypeId, DocumentTypeAliasRequest request) {
        documentTypes.get(documentTypeId);
        String key = TypeNameNormalizer.key(request.alias()).orElseThrow(() -> new UnusableNameException("alias"));
        documentTypes.resolve(request.alias()).ifPresent(owner -> {
            throw new DocumentTypeKeyConflictException(key, owner.name());
        });
        DocumentTypeAlias alias = DocumentTypeAlias.builder()
                .id(UUID.randomUUID())
                .documentTypeId(documentTypeId)
                .alias(request.alias())
                .normalizedKey(key)
                .build();
        try {
            repository.saveAndFlush(alias);
        } catch (DataIntegrityViolationException e) {
            // a concurrent request took the key between the check and the insert
            throw new DocumentTypeKeyConflictException(key, null);
        }
        log.info("Added alias {} to document type {}", alias.getId(), documentTypeId);
        return toResponse(alias);
    }

    @Override
    @Transactional
    public void delete(UUID documentTypeId, UUID aliasId) {
        DocumentTypeAlias alias = repository
                .findByIdAndDocumentTypeId(aliasId, documentTypeId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, aliasId));
        repository.delete(alias);
        log.info("Removed alias {} from document type {}", aliasId, documentTypeId);
    }

    private static DocumentTypeAliasResponse toResponse(DocumentTypeAlias alias) {
        return DocumentTypeAliasResponse.builder()
                .id(alias.getId())
                .documentTypeId(alias.getDocumentTypeId())
                .alias(alias.getAlias())
                .normalizedKey(alias.getNormalizedKey())
                .build();
    }
}
