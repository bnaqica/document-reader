package com.bnaqica.documentreader.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.processors.OutboxDispatcher;
import com.bnaqica.documentreader.service.InboxProcessingService;
import com.bnaqica.documentreader.service.WorkerSignalService;
import com.bnaqica.documentreader.service.WorkerSignalServiceImpl;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The worker only waits and delegates. The poll interval is an hour, so every call below comes from a signal; the
 * signal service is the real in-memory one, and the services it calls are mocks.
 */
class InboxWorkerTests {

    private static final Duration WAIT = Duration.ofSeconds(2);

    private final WorkerSignalService signals = new WorkerSignalServiceImpl();
    private final InboxProcessingService processing = mock(InboxProcessingService.class);
    private final OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
    private InboxWorker worker;

    private static DocumentReaderProperties properties(boolean enabled) {
        return new DocumentReaderProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new DocumentReaderProperties.Outbox(Duration.ofHours(1)),
                null,
                null,
                null,
                new DocumentReaderProperties.Workers(enabled));
    }

    @BeforeEach
    void setUp() {
        given(processing.handledTypes()).willReturn(EnumSet.of(MessageType.CLASSIFY_DOCUMENT));
        worker = new InboxWorker(signals, processing, dispatcher, properties(true));
    }

    @AfterEach
    void stop() {
        worker.stop();
    }

    @Test
    void inboxWorkerOnlyDelegates() throws Exception {
        worker.start();

        signals.signal(MessageType.CLASSIFY_DOCUMENT);
        verify(processing, timeout(WAIT.toMillis()).times(1)).processDue(MessageType.CLASSIFY_DOCUMENT);

        signals.signalDispatcher();
        verify(dispatcher, timeout(WAIT.toMillis()).times(1)).dispatchPending();

        // nothing else: the timeouts above return at the first call, so settle and check for extra ones
        Thread.sleep(200);
        verify(processing).handledTypes();
        verify(processing).processDue(MessageType.CLASSIFY_DOCUMENT);
        verifyNoMoreInteractions(processing, dispatcher);
    }

    /** A deploy must not cut off a message being handled: stop() waits for the round instead of interrupting it. */
    @Test
    void stopLetsAnInFlightRoundFinish() throws Exception {
        CountDownLatch inRound = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        given(processing.processDue(MessageType.CLASSIFY_DOCUMENT)).willAnswer(invocation -> {
            inRound.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
            finished.set(true);
            return 1;
        });
        worker.start();
        signals.signal(MessageType.CLASSIFY_DOCUMENT);
        assertThat(inRound.await(2, TimeUnit.SECONDS)).isTrue();

        worker.stop();

        assertThat(finished).isTrue();
        assertThat(interrupted).isFalse();
    }

    @Test
    void dependsOnServicesOnly() {
        Constructor<?>[] constructors = InboxWorker.class.getConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(Arrays.stream(constructors[0].getParameterTypes()).map(Class::getSimpleName))
                .containsExactlyInAnyOrder(
                        "WorkerSignalService",
                        "InboxProcessingService",
                        "OutboxDispatcher",
                        "DocumentReaderProperties");
    }

    @Test
    void loopsRunOnlyForHandledTypes() throws Exception {
        worker.start();

        signals.signal(MessageType.EXTRACT_DOCUMENT);
        Thread.sleep(300);

        verify(processing, never()).processDue(MessageType.EXTRACT_DOCUMENT);
    }

    @Test
    void loopKeepsGoingAfterAnException() {
        willThrow(new IllegalStateException("database down")).given(processing).processDue(any());
        worker.start();

        signals.signal(MessageType.CLASSIFY_DOCUMENT);
        verify(processing, timeout(WAIT.toMillis()).times(1)).processDue(MessageType.CLASSIFY_DOCUMENT);
        signals.signal(MessageType.CLASSIFY_DOCUMENT);
        verify(processing, timeout(WAIT.toMillis()).times(2)).processDue(MessageType.CLASSIFY_DOCUMENT);
    }

    @Test
    void stopEndsEveryLoop() throws Exception {
        worker.start();
        assertThat(worker.isRunning()).isTrue();

        worker.stop();
        signals.signal(MessageType.CLASSIFY_DOCUMENT);
        signals.signalDispatcher();
        Thread.sleep(300);

        assertThat(worker.isRunning()).isFalse();
        verify(processing, never()).processDue(any());
        verify(dispatcher, never()).dispatchPending();
    }

    @Test
    void disabledWorkersStartNothing() throws Exception {
        worker = new InboxWorker(signals, processing, dispatcher, properties(false));

        worker.start();
        signals.signal(MessageType.CLASSIFY_DOCUMENT);
        signals.signalDispatcher();
        Thread.sleep(300);

        assertThat(worker.isRunning()).isFalse();
        verify(processing, times(0)).processDue(any());
        verify(dispatcher, never()).dispatchPending();
    }
}
