package com.bnaqica.documentreader.api;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.ExtractionRunRecord;
import com.bnaqica.documentreader.entity.ExtractionStage;
import com.bnaqica.documentreader.service.RunRecordQueryService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only extraction runs of a document, optionally for one stage. Only the pipeline creates them. */
@RestController
@RequestMapping("/records/documents")
@RequiredArgsConstructor
public class ExtractionRunRecordController {

    private final RunRecordQueryService runs;

    @GetMapping("/{id}/extraction-runs")
    List<ExtractionRunRecord> extractionRuns(
            @PathVariable UUID id,
            @RequestParam(required = false) ExtractionStage stage,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return runs.extractionRuns(id, stage, Caller.of(authentication));
    }
}
