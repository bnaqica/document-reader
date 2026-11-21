package com.bnaqica.documentreader.api;

import com.bnaqica.documentreader.dto.response.OutboxMessageRecord;
import com.bnaqica.documentreader.service.OutboxMessageService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only outbox, for admins: a message has no single batch, so the caller-owns-it rule can't apply. */
@RestController
@RequestMapping("/records/outbox-messages")
@RequiredArgsConstructor
public class OutboxMessageRecordController {

    private final OutboxMessageService outbox;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    List<OutboxMessageRecord> list(@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return outbox.findRecent(limit);
    }
}
