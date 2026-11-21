package com.bnaqica.documentreader.api;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.SeparationRunRecord;
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

/** Read-only separation runs of a source file. Only the pipeline creates them. */
@RestController
@RequestMapping("/records/source-files")
@RequiredArgsConstructor
public class SeparationRunRecordController {

    private final RunRecordQueryService runs;

    @GetMapping("/{id}/separation-runs")
    List<SeparationRunRecord> separationRuns(
            @PathVariable UUID id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return runs.separationRuns(id, Caller.of(authentication));
    }
}
