package com.bnaqica.documentreader.api;

import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.entity.InboxStatus;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.service.InboxMessageService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only inbox, for admins: a message has no single batch, so the caller-owns-it rule can't apply. */
@RestController
@RequestMapping("/records/inbox-messages")
@RequiredArgsConstructor
public class InboxMessageRecordController {

    private final InboxMessageService inbox;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    List<InboxMessageRecord> list(
            @RequestParam(required = false) MessageType type,
            @RequestParam(required = false) InboxStatus status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return inbox.find(type, status, limit);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    InboxMessageRecord get(@PathVariable UUID id) {
        return inbox.get(id);
    }
}
