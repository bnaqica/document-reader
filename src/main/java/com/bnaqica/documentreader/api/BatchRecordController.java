package com.bnaqica.documentreader.api;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import com.bnaqica.documentreader.service.RecordQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

/**
 * Read-only batch records. Only the pipeline creates or changes them, so every other method gets 405. Intake and the
 * result envelope live at {@code /batches} (WI-11).
 */
@RestController
@RequestMapping("/records/batches")
@RequiredArgsConstructor
public class BatchRecordController {

    private final RecordQueryService records;

    @GetMapping
    List<BatchRecord> list(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return records.batches(Caller.of(authentication), limit);
    }

    @GetMapping("/{id}")
    BatchRecord get(
            @PathVariable UUID id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return records.batch(id, Caller.of(authentication));
    }

    @GetMapping("/{id}/source-files")
    List<SourceFileRecord> sourceFiles(
            @PathVariable UUID id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return records.sourceFiles(id, Caller.of(authentication));
    }

    @GetMapping("/{id}/documents")
    List<DocumentRecord> documents(
            @PathVariable UUID id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return records.documents(id, Caller.of(authentication));
    }
}
