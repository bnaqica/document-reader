package com.bnaqica.documentreader.api;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.ClassificationRunRecord;
import com.bnaqica.documentreader.service.RunRecordQueryService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only classification runs of a document. Only the pipeline creates them. */
@RestController
@RequestMapping("/records/documents")
@RequiredArgsConstructor
public class ClassificationRunRecordController {

    private final RunRecordQueryService runs;

    @GetMapping("/{id}/classification-runs")
    List<ClassificationRunRecord> classificationRuns(
            @PathVariable UUID id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return runs.classificationRuns(id, Caller.of(authentication));
    }
}
