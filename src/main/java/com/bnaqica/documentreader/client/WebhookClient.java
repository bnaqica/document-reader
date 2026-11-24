package com.bnaqica.documentreader.client;

import com.bnaqica.documentreader.dto.response.BatchResultResponse;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Posts a batch's result to the caller's webhook URL. Unsigned for the proof of concept (§22). Every response comes
 * back, whatever its status, and redirects are never followed; the caller decides what counts as delivered.
 */
public interface WebhookClient {

    /** The {@code webhookUrl} argument is the whole target, so each batch posts to its own URL. */
    @PostExchange(contentType = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> post(
            URI webhookUrl,
            @RequestHeader("X-Document-Reader-Batch") String batchId,
            @RequestHeader("X-Document-Reader-Delivery") String deliveryId,
            @RequestBody BatchResultResponse result);
}
