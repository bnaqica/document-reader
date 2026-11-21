package com.bnaqica.documentreader.api;

import com.bnaqica.documentreader.dto.request.DocumentTypeAliasRequest;
import com.bnaqica.documentreader.dto.response.DocumentTypeAliasResponse;
import com.bnaqica.documentreader.service.DocumentTypeAliasService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The other names a document type resolves by. Anyone with a valid token reads them; only admins change them. */
@RestController
@RequestMapping("/document-types/{documentTypeId}/aliases")
@RequiredArgsConstructor
public class DocumentTypeAliasController {

    private final DocumentTypeAliasService service;

    @GetMapping
    List<DocumentTypeAliasResponse> list(@PathVariable UUID documentTypeId) {
        return service.findByType(documentTypeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    DocumentTypeAliasResponse add(
            @PathVariable UUID documentTypeId, @Valid @RequestBody DocumentTypeAliasRequest request) {
        return service.add(documentTypeId, request);
    }

    @DeleteMapping("/{aliasId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void delete(@PathVariable UUID documentTypeId, @PathVariable UUID aliasId) {
        service.delete(documentTypeId, aliasId);
    }
}
