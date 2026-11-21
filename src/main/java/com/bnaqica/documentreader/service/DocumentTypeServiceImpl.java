package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.CatalogEntry;
import com.bnaqica.documentreader.dto.request.DocumentTypeRequest;
import com.bnaqica.documentreader.dto.response.DocumentTypeResponse;
import com.bnaqica.documentreader.entity.DocumentType;
import com.bnaqica.documentreader.entity.DocumentTypeOrigin;
import com.bnaqica.documentreader.exception.DocumentTypeInUseException;
import com.bnaqica.documentreader.exception.DocumentTypeKeyConflictException;
import com.bnaqica.documentreader.exception.InvalidDataSchemaException;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.exception.UnusableNameException;
import com.bnaqica.documentreader.processors.DataSchemaRules;
import com.bnaqica.documentreader.processors.TypeNameNormalizer;
import com.bnaqica.documentreader.repository.DocumentTypeRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keys are unique across types and aliases. A resolve before each write gives a 409 that names the owner; the
 * database (unique constraints plus a trigger) is the guarantee when two requests race.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentTypeServiceImpl implements DocumentTypeService {

    private static final String RESOURCE = "Document type";
    /** The foreign keys that refer to a type (V5, V6). Only these mean "in use" when a delete is refused. */
    private static final Set<String> TYPE_IN_USE_CONSTRAINTS = Set.of(
            "fk_document_type_in_use",
            "fk_classification_run_type_in_use",
            "fk_classification_result_type_in_use",
            "fk_extraction_result_type_in_use");

    private final DocumentTypeRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Override
    public List<DocumentTypeResponse> findAll() {
        return repository.findAllOrderedByName().stream().map(this::toResponse).toList();
    }

    @Override
    public DocumentTypeResponse get(UUID id) {
        return toResponse(find(id));
    }

    @Override
    public Optional<DocumentTypeResponse> resolve(String name) {
        return TypeNameNormalizer.key(name)
                .flatMap(repository::findByKeyOrAliasKey)
                .map(this::toResponse);
    }

    @Override
    public List<CatalogEntry> catalog() {
        return repository.findAllOrderedByName().stream()
                .map(type -> CatalogEntry.builder()
                        .name(type.getName())
                        .description(type.getDescription())
                        .build())
                .toList();
    }

    /** Its own transaction, so a lost race doesn't abort the caller's (see {@link DocumentTypeService#create}). */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DocumentTypeResponse create(DocumentTypeRequest request) {
        String key = keyOf(request.name());
        requireStrict(request.dataSchema());
        rejectTakenKey(key, null);
        DocumentType type = DocumentType.builder()
                .id(UUID.randomUUID())
                .name(request.name())
                .normalizedKey(key)
                .description(request.description())
                .dataSchema(jsonMapper.writeValueAsString(request.dataSchema()))
                .schemaVersion(1)
                .origin(DocumentTypeOrigin.MANUAL)
                .createdAt(clock.instant())
                .build();
        saveAndFlush(type, key);
        log.info("Created document type {}", type.getId());
        return toResponse(type);
    }

    @Override
    @Transactional
    public DocumentTypeResponse update(UUID id, DocumentTypeRequest request) {
        DocumentType type = find(id);
        String key = keyOf(request.name());
        requireStrict(request.dataSchema());
        if (!key.equals(type.getNormalizedKey())) {
            rejectTakenKey(key, id);
        }
        type.rename(request.name(), key);
        type.describe(request.description());
        if (!parse(type.getDataSchema()).equals(request.dataSchema())) {
            type.replaceDataSchema(jsonMapper.writeValueAsString(request.dataSchema()));
        }
        saveAndFlush(type, key);
        log.info("Updated document type {} (schema version {})", id, type.getSchemaVersion());
        return toResponse(type);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        DocumentType type = find(id);
        try {
            repository.delete(type);
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            // only a foreign key that refers to the type means "in use"; anything else is a different problem
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            if (TYPE_IN_USE_CONSTRAINTS.stream().anyMatch(message::contains)) {
                throw new DocumentTypeInUseException(type.getName(), e);
            }
            throw e;
        }
        log.info("Deleted document type {}", id);
    }

    private static String keyOf(String name) {
        return TypeNameNormalizer.key(name).orElseThrow(() -> new UnusableNameException("name"));
    }

    private static void requireStrict(JsonNode dataSchema) {
        List<String> violations = DataSchemaRules.violations(dataSchema);
        if (!violations.isEmpty()) {
            throw new InvalidDataSchemaException(violations);
        }
    }

    /**
     * Throws if {@code key} is taken: by another type, or by one of {@code ownId}'s own aliases (a type can't be
     * renamed to its alias). {@code ownId} is null when creating.
     */
    private void rejectTakenKey(String key, UUID ownId) {
        repository
                .findByKeyOrAliasKey(key)
                .filter(owner -> !owner.getId().equals(ownId)
                        || !owner.getNormalizedKey().equals(key))
                .ifPresent(owner -> {
                    throw new DocumentTypeKeyConflictException(key, owner.getName());
                });
    }

    private void saveAndFlush(DocumentType type, String key) {
        try {
            repository.saveAndFlush(type);
        } catch (DataIntegrityViolationException e) {
            // a concurrent request took the key between the check and the write
            throw new DocumentTypeKeyConflictException(key, null);
        }
    }

    private DocumentType find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    private JsonNode parse(String json) {
        return jsonMapper.readTree(json);
    }

    private DocumentTypeResponse toResponse(DocumentType type) {
        return DocumentTypeResponse.builder()
                .id(type.getId())
                .name(type.getName())
                .normalizedKey(type.getNormalizedKey())
                .description(type.getDescription())
                .dataSchema(parse(type.getDataSchema()))
                .schemaVersion(type.getSchemaVersion())
                .origin(type.getOrigin())
                .build();
    }
}
