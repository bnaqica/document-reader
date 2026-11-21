package com.bnaqica.documentreader.api;

import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.WebhookAttemptRecord;
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

/** Read-only webhook delivery attempts of a batch. Only the pipeline creates them. */
@RestController
@RequestMapping("/records/batches")
@RequiredArgsConstructor
public class WebhookAttemptRecordController {

    private final RunRecordQueryService runs;

    @GetMapping("/{id}/webhook-attempts")
    List<WebhookAttemptRecord> webhookAttempts(
            @PathVariable UUID id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return runs.webhookAttempts(id, Caller.of(authentication));
    }
}
