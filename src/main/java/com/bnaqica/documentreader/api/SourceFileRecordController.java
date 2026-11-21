package com.bnaqica.documentreader.api;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.PageRecord;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import com.bnaqica.documentreader.service.RecordQueryService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only source file and page records. Only the pipeline creates or changes them. */
@RestController
@RequestMapping("/records/source-files")
@RequiredArgsConstructor
public class SourceFileRecordController {

    private final RecordQueryService records;

    @GetMapping("/{id}")
    SourceFileRecord get(
            @PathVariable UUID id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return records.sourceFile(id, Caller.of(authentication));
    }

    @GetMapping("/{id}/pages")
    List<PageRecord> pages(
            @PathVariable UUID id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return records.pages(id, Caller.of(authentication));
    }
}
