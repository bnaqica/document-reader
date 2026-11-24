package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.client.WebhookClient;
import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.dto.response.BatchResultResponse;
import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.entity.WebhookStatus;
import java.net.URI;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

/**
 * Process 4 (§15), and the {@code DELIVER_WEBHOOK} inbox handler. One POST of the batch's result per attempt, with
 * the batch and delivery ids as headers and no signature (proof of concept, §22). A 2xx delivers it; anything else
 * fails the attempt, and the inbox's backoff retries it. Every attempt is recorded as soon as the POST returns, in a
 * transaction of its own. The URL, the body and the response are never logged or stored.
 */
@Slf4j
@Service
public class WebhookDeliveryServiceImpl implements WebhookDeliveryService, InboxMessageHandler {

    private final BatchService batches;
    private final BatchQueryService query;
    private final WebhookDeliveryAttemptService attempts;
    private final InboxMessageService inbox;
    private final WebhookClient webhooks;
    private final TransactionTemplate transaction;

    public WebhookDeliveryServiceImpl(
            BatchService batches,
            BatchQueryService query,
            WebhookDeliveryAttemptService attempts,
            InboxMessageService inbox,
            WebhookClient webhooks,
            PlatformTransactionManager transactionManager) {
        this.batches = batches;
        this.query = query;
        this.attempts = attempts;
        this.inbox = inbox;
        this.webhooks = webhooks;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public MessageType type() {
        return MessageType.DELIVER_WEBHOOK;
    }

    @Override
    public void handle(InboxMessageRecord message) {
        deliver(message.aggregateId(), message.id(), true);
    }

    @Override
    public void deliver(UUID batchId, UUID deliveryId) {
        deliver(batchId, deliveryId, false);
    }

    /**
     * Runs in the inbox's {@code FAILED} transaction. Only the webhook gives up: the batch keeps its status and
     * result, which its owner can still read with {@code GET}.
     */
    @Override
    public void onExhausted(InboxMessageRecord message, String lastError) {
        UUID batchId = message.aggregateId();
        // Locked, as the success transaction locks it, so a late delivery and giving up can't overwrite each other
        if (batches.lockForUpdate(batchId).webhookStatus() == WebhookStatus.DELIVERED) {
            log.debug("Batch {} webhook was already delivered; not failing it", batchId);
            return;
        }
        batches.updateWebhookStatus(batchId, WebhookStatus.FAILED);
    }

    private void deliver(UUID batchId, UUID deliveryId, boolean fromInbox) {
        BatchRecord batch = batches.get(batchId);
        if (batch.webhookStatus() != WebhookStatus.PENDING) {
            log.debug("Batch {} webhook is {}; nothing to deliver", batchId, batch.webhookStatus());
            return;
        }
        // Exactly what the batch's owner reads with GET
        BatchResultResponse result = query.getResult(batchId, new Caller(batch.requestedBy(), false));
        int attempt = attempts.findByBatch(batchId).size() + 1;

        Integer status = null;
        String error;
        try {
            ResponseEntity<Void> response =
                    webhooks.post(URI.create(batch.webhookUrl()), batchId.toString(), deliveryId.toString(), result);
            status = response.getStatusCode().value();
            error = response.getStatusCode().is2xxSuccessful() ? null : "HTTP " + status;
        } catch (ResourceAccessException e) {
            // a timeout or a refused connection: only the kind of failure is kept, never the URL
            error = "No response: "
                    + NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName();
        } catch (RuntimeException e) {
            // the request couldn't be sent at all (a port out of range, say); its message can quote the URL
            error = "Request failed: " + e.getClass().getSimpleName();
        }
        // Recorded at once, in its own transaction, so nothing that fails after this can lose it
        attempts.record(batchId, attempt, status, error); // which logs the attempt

        if (error != null) {
            throw new IllegalStateException(
                    "Webhook attempt " + attempt + " for batch " + batchId + " failed: " + error);
        }
        transaction.executeWithoutResult(tx -> {
            // Locked, as onExhausted locks it: a reclaimed message that gave up meanwhile keeps the receiver's answer
            batches.lockForUpdate(batchId);
            batches.updateWebhookStatus(batchId, WebhookStatus.DELIVERED);
            if (fromInbox) {
                inbox.markDone(deliveryId);
            }
        });
    }
}
