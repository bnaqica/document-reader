package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.entity.MessageType;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PgNotificationServiceImpl implements PgNotificationService {

    private final WorkerSignalService signals;
    private final CompletionWaitService waits;

    @Override
    public void onNotification(String channel, String payload) {
        switch (channel) {
            case OUTBOX_READY -> signals.signalDispatcher();
            case INBOX_READY -> signalType(payload);
            case BATCH_COMPLETED -> completeBatch(payload);
            default -> log.debug("Ignored a notification on channel {}", channel);
        }
    }

    @Override
    public void onConnected() {
        signals.wakeAll();
    }

    private void signalType(String payload) {
        try {
            signals.signal(MessageType.valueOf(payload));
        } catch (IllegalArgumentException | NullPointerException e) {
            log.debug("Ignored an inbox_ready notification that names no message type");
        }
    }

    private void completeBatch(String payload) {
        UUID batchId;
        try {
            batchId = UUID.fromString(payload);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.debug("Ignored a batch_completed notification that holds no batch id");
            return;
        }
        waits.onBatchCompleted(batchId);
    }
}
