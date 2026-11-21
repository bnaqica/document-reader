package com.bnaqica.documentreader.api;

import com.bnaqica.documentreader.dto.request.LoanPurposeCreateRequest;
import com.bnaqica.documentreader.dto.request.LoanPurposeUpdateRequest;
import com.bnaqica.documentreader.dto.response.LoanPurposeResponse;
import com.bnaqica.documentreader.service.LoanPurposeService;
import jakarta.validation.Valid;
import java.util.List;
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

/** Loan purposes. Anyone with a valid token reads them; only admins change them. */
@RestController
@RequestMapping("/loan-purposes")
@RequiredArgsConstructor
public class LoanPurposeController {

    private final LoanPurposeService service;

    @GetMapping
    List<LoanPurposeResponse> listActive() {
        return service.findAllActive();
    }

    @GetMapping("/{code}")
    LoanPurposeResponse get(@PathVariable String code) {
        return service.get(code);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    LoanPurposeResponse create(@Valid @RequestBody LoanPurposeCreateRequest request) {
        return service.create(request);
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    LoanPurposeResponse update(@PathVariable String code, @Valid @RequestBody LoanPurposeUpdateRequest request) {
        return service.update(code, request);
    }

    /** Deactivates rather than erases, because earlier batches keep referring to the code. */
    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void deactivate(@PathVariable String code) {
        service.deactivate(code);
    }
}
