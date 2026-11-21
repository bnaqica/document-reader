package com.bnaqica.documentreader.api;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.ClassificationResultRecord;
import com.bnaqica.documentreader.service.RunRecordQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only classification result of a document; 404 until it is classified. Only the pipeline creates them. */
@RestController
@RequestMapping("/records/documents")
@RequiredArgsConstructor
public class ClassificationResultRecordController {

    private final RunRecordQueryService runs;

    @GetMapping("/{id}/classification-result")
    ClassificationResultRecord classificationResult(
            @PathVariable UUID id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return runs.classificationResult(id, Caller.of(authentication));
    }
}
