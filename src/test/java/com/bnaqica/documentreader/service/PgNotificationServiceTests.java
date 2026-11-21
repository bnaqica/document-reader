package com.bnaqica.documentreader.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.bnaqica.documentreader.entity.MessageType;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PgNotificationServiceTests {

    private final WorkerSignalService signals = mock(WorkerSignalService.class);
    private final CompletionWaitService waits = mock(CompletionWaitService.class);
    private final PgNotificationService notifications = new PgNotificationServiceImpl(signals, waits);

    @AfterEach
    void nothingElse() {
        verifyNoMoreInteractions(signals, waits);
    }

    @Test
    void outboxReadyWakesTheDispatcher() {
        notifications.onNotification("outbox_ready", "");

        verify(signals).signalDispatcher();
    }

    @Test
    void inboxReadyWakesTheNamedType() {
        notifications.onNotification("inbox_ready", "EXTRACT_DOCUMENT");

        verify(signals).signal(MessageType.EXTRACT_DOCUMENT);
    }

    @Test
    void inboxReadyForAnUnknownTypeIsIgnored() {
        notifications.onNotification("inbox_ready", "PRINT_FILE");
        notifications.onNotification("inbox_ready", null);

        verifyNoInteractions(signals, waits);
    }

    @Test
    void batchCompletedWakesItsWaiter() {
        UUID batch = UUID.randomUUID();

        notifications.onNotification("batch_completed", batch.toString());

        verify(waits).onBatchCompleted(batch);
    }

    @Test
    void batchCompletedWithABadIdIsIgnored() {
        notifications.onNotification("batch_completed", "not-a-uuid");

        verifyNoInteractions(signals, waits);
    }

    @Test
    void unknownChannelIsIgnored() {
        notifications.onNotification("something_else", "x");

        verifyNoInteractions(signals, waits);
    }

    @Test
    void connectingWakesEveryLoop() {
        notifications.onConnected();

        verify(signals).wakeAll();
    }
}
