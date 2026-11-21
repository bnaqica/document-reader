package com.bnaqica.documentreader.listener;

import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.processors.OutboxDispatcher;
import com.bnaqica.documentreader.service.InboxProcessingService;
import com.bnaqica.documentreader.service.WorkerSignalService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The background loops: one per message type that has a handler, and one for the dispatcher, each on a virtual
 * thread. A loop waits for its signal or the poll interval, then delegates; it holds no other logic. Nothing starts
 * when {@code app.document-reader.workers.enabled} is false. Its default phase stops it before the data source closes.
 */
@Slf4j
@Component
public class InboxWorker implements SmartLifecycle {

    /** Within Spring's default 30 s shutdown phase timeout, leaving time to release interrupted messages. */
    static final Duration GRACE = Duration.ofSeconds(20);

    private static final Duration AFTER_INTERRUPT = Duration.ofSeconds(5);

    private final WorkerSignalService signals;
    private final InboxProcessingService processing;
    private final OutboxDispatcher dispatcher;
    private final Duration pollInterval;
    private final boolean enabled;
    private final List<Thread> loops = new ArrayList<>();
    private volatile boolean running;

    public InboxWorker(
            WorkerSignalService signals,
            InboxProcessingService processing,
            OutboxDispatcher dispatcher,
            DocumentReaderProperties properties) {
        this.signals = signals;
        this.processing = processing;
        this.dispatcher = dispatcher;
        this.pollInterval = properties.outbox().pollInterval();
        this.enabled = properties.workers().enabled();
    }

    @Override
    public synchronized void start() {
        if (!enabled || running) {
            return;
        }
        running = true;
        loops.add(Thread.ofVirtual()
                .name("outbox-dispatcher")
                .start(() -> loop("dispatcher", () -> {
                    signals.awaitDispatcherSignal(pollInterval);
                    if (running) { // stop() wakes the wait; don't start a round while shutting down
                        dispatcher.dispatchPending();
                    }
                })));
        Set<MessageType> types = processing.handledTypes();
        for (MessageType type : types) {
            loops.add(Thread.ofVirtual()
                    .name("inbox-" + type)
                    .start(() -> loop(type.name(), () -> {
                        signals.awaitSignal(type, pollInterval);
                        if (running) {
                            processing.processDue(type);
                        }
                    })));
        }
        log.info("Started the outbox dispatcher and inbox workers for {}", types);
    }

    private void loop(String name, Runnable round) {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                round.run();
            } catch (RuntimeException e) {
                log.error("Worker loop {} failed; continuing", name, e);
            }
        }
    }

    /**
     * Wakes the loops instead of interrupting them, and waits for rounds in flight, so a deploy doesn't cut off a
     * message being handled. Loops still running after {@link #GRACE} are interrupted; their handlers then put their
     * messages back without counting the attempt.
     */
    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        signals.wakeAll();
        long deadline = System.nanoTime() + GRACE.toNanos();
        try {
            for (Thread loop : loops) {
                loop.join(Duration.ofNanos(Math.max(deadline - System.nanoTime(), 1)));
            }
            for (Thread loop : loops) {
                if (loop.isAlive()) {
                    log.info("Worker loop {} still running after {}; interrupting it", loop.getName(), GRACE);
                    loop.interrupt();
                    loop.join(AFTER_INTERRUPT);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        loops.clear();
        log.info("Stopped the outbox dispatcher and inbox workers");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
