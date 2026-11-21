package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bnaqica.documentreader.entity.MessageType;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkerSignalServiceTests {

    private static final Duration SHORT = Duration.ofMillis(200);

    private final WorkerSignalService signals = new WorkerSignalServiceImpl();

    @Test
    void signalWakesWaiter() {
        signals.signal(MessageType.CLASSIFY_DOCUMENT);

        long started = System.nanoTime();
        assertThat(signals.awaitSignal(MessageType.CLASSIFY_DOCUMENT, Duration.ofSeconds(5)))
                .isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void waitTimesOutWithoutASignal() {
        long started = System.nanoTime();

        assertThat(signals.awaitSignal(MessageType.CLASSIFY_DOCUMENT, SHORT)).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(SHORT);
    }

    @Test
    void signalForAnotherTypeDoesNotWake() {
        signals.signal(MessageType.EXTRACT_DOCUMENT);

        assertThat(signals.awaitSignal(MessageType.CLASSIFY_DOCUMENT, SHORT)).isFalse();
    }

    @Test
    void signalsBeforeAWaitCollapseIntoOne() {
        signals.signal(MessageType.SEPARATE_FILE);
        signals.signal(MessageType.SEPARATE_FILE);
        signals.signal(MessageType.SEPARATE_FILE);

        assertThat(signals.awaitSignal(MessageType.SEPARATE_FILE, SHORT)).isTrue();
        assertThat(signals.awaitSignal(MessageType.SEPARATE_FILE, SHORT)).isFalse();
    }

    @Test
    void dispatcherSignalBehavesTheSame() {
        signals.signalDispatcher();
        signals.signalDispatcher();

        assertThat(signals.awaitDispatcherSignal(SHORT)).isTrue();
        assertThat(signals.awaitDispatcherSignal(SHORT)).isFalse();
    }

    @Test
    void interruptedWaitReturnsFalseAndKeepsTheInterrupt() {
        Thread.currentThread().interrupt();
        try {
            assertThat(signals.awaitSignal(MessageType.SEPARATE_FILE, SHORT)).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void wakeAllWakesEveryWaiterOnce() {
        signals.wakeAll();

        for (MessageType type : MessageType.values()) {
            assertThat(signals.awaitSignal(type, SHORT)).isTrue();
        }
        assertThat(signals.awaitDispatcherSignal(SHORT)).isTrue();
        assertThat(signals.awaitDispatcherSignal(SHORT)).isFalse();
    }
}
