package com.bnaqica.documentreader.api;

import com.bnaqica.documentreader.dto.request.DocumentTypeRequest;
import com.bnaqica.documentreader.dto.response.DocumentTypeResponse;
import com.bnaqica.documentreader.service.DocumentTypeService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The document type catalog. Anyone with a valid token reads it; only admins change it. */
@RestController
@RequestMapping("/document-types")
@RequiredArgsConstructor
public class DocumentTypeController {

    private final DocumentTypeService service;

    @GetMapping
    List<DocumentTypeResponse> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    DocumentTypeResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    DocumentTypeResponse create(@Valid @RequestBody DocumentTypeRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    DocumentTypeResponse update(@PathVariable UUID id, @Valid @RequestBody DocumentTypeRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
