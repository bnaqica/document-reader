package com.bnaqica.documentreader.api;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.service.RecordQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only document records. Only the pipeline creates or changes them. */
@RestController
@RequestMapping("/records/documents")
@RequiredArgsConstructor
public class DocumentRecordController {

    private final RecordQueryService records;

    @GetMapping("/{id}")
    DocumentRecord get(
            @PathVariable UUID id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return records.document(id, Caller.of(authentication));
    }
}
