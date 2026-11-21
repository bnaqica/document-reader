package com.bnaqica.documentreader.api;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.ExtractionResultRecord;
import com.bnaqica.documentreader.service.RunRecordQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only extraction result of a document; 404 until it is extracted. Only the pipeline creates them. */
@RestController
@RequestMapping("/records/documents")
@RequiredArgsConstructor
public class ExtractionResultRecordController {

    private final RunRecordQueryService runs;

    @GetMapping("/{id}/extraction-result")
    ExtractionResultRecord extractionResult(
            @PathVariable UUID id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return runs.extractionResult(id, Caller.of(authentication));
    }
}
