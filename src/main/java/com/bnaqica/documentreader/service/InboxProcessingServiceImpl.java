package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.config.WorkerConfig.WorkerInstance;
import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.entity.MessageType;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * All of the inbox's processing rules. Not transactional itself: the claim commits in its own transaction, handlers
 * run with none open, and each outcome (done, retry, exhausted) is its own short transaction. Logs carry ids, types,
 * attempts and statuses only, never payloads or handler errors, which may quote document content.
 */
@Slf4j
@Service
public class InboxProcessingServiceImpl implements InboxProcessingService {

    /** 2^30 × any sane initial backoff is far past any cap, and the shift can't overflow. */
    private static final int MAX_DOUBLINGS = 30;

    private final Map<MessageType, InboxMessageHandler> handlers;
    private final InboxMessageService inbox;
    private final WorkerSignalService signals;
    private final DocumentReaderProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final String instanceId;

    public InboxProcessingServiceImpl(
            List<InboxMessageHandler> handlers,
            InboxMessageService inbox,
            WorkerSignalService signals,
            DocumentReaderProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager,
            WorkerInstance workerInstance) {
        this.handlers = byType(handlers);
        this.inbox = inbox;
        this.signals = signals;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.instanceId = workerInstance.id();
    }

    private static Map<MessageType, InboxMessageHandler> byType(List<InboxMessageHandler> handlers) {
        Map<MessageType, InboxMessageHandler> byType = new EnumMap<>(MessageType.class);
        for (InboxMessageHandler handler : handlers) {
            if (byType.putIfAbsent(handler.type(), handler) != null) {
                throw new IllegalStateException("Two inbox message handlers for " + handler.type());
            }
        }
        return Collections.unmodifiableMap(byType);
    }

    @Override
    public int processDue(MessageType type) {
        InboxMessageHandler handler = handlers.get(type);
        if (handler == null) {
            return 0;
        }
        int limit = properties.inbox().concurrency();
        List<InboxMessageRecord> claimed =
                inbox.claimDue(type, limit, instanceId, properties.inbox().lease());
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            claimed.forEach(message -> threads.execute(() -> processSafely(handler, message)));
        }
        if (claimed.size() == limit) {
            signals.signal(type); // more may be due: run again at once instead of waiting for the poll
        }
        return claimed.size();
    }

    /** The task boundary: nothing a handler throws, not even an Error, escapes to the executor's default handler. */
    private void processSafely(InboxMessageHandler handler, InboxMessageRecord message) {
        try {
            process(handler, message);
        } catch (Throwable t) {
            log.error(
                    "Processing inbox message {} failed with {}; it is retried after its lease",
                    message.id(),
                    t.getClass().getName());
        }
    }

    @Override
    public Set<MessageType> handledTypes() {
        return handlers.keySet();
    }

    @Override
    public int maxAttempts(MessageType type) {
        return type == MessageType.DELIVER_WEBHOOK
                ? properties.webhook().maxAttempts()
                : properties.inbox().maxAttempts();
    }

    private void process(InboxMessageHandler handler, InboxMessageRecord message) {
        int maxAttempts = maxAttempts(message.messageType());
        if (message.attempts() > maxAttempts) {
            // only a reclaim after a crash on the last attempt gets here
            exhaust(handler, message, lastErrorOrLeaseExpired(message));
            return;
        }
        try {
            handler.handle(message);
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                releaseAfterInterrupt(message);
            } else {
                fail(handler, message, maxAttempts, describe(e));
            }
            return;
        }
        try {
            transaction.executeWithoutResult(status -> inbox.markDone(message.id()));
        } catch (RuntimeException e) {
            log.error("Could not mark inbox message {} done; it is retried after its lease", message.id(), e);
        }
    }

    private void fail(InboxMessageHandler handler, InboxMessageRecord message, int maxAttempts, String error) {
        if (message.attempts() >= maxAttempts) {
            exhaust(handler, message, error);
            return;
        }
        try {
            // a no-op when the handler already marked the message done, or another instance reclaimed it
            inbox.markRetry(message, error, clock.instant().plus(backoff(message.attempts())));
        } catch (RuntimeException e) {
            log.error("Could not schedule a retry of inbox message {}; it is retried after its lease", message.id(), e);
        }
    }

    /**
     * Marks the message failed and tells its step, in one transaction. The step is told only when the mark applied:
     * not when the handler already marked the message done, nor when another instance holds it. If the step throws,
     * both roll back and the message is exhausted again after its lease; the error is recorded first, so that retry
     * reports it.
     */
    private void exhaust(InboxMessageHandler handler, InboxMessageRecord message, String error) {
        try {
            inbox.recordError(message, error);
            transaction.executeWithoutResult(status -> {
                if (inbox.markFailed(message, error)) {
                    handler.onExhausted(message, error);
                }
            });
        } catch (RuntimeException e) {
            log.error(
                    "Could not exhaust inbox message {}; it stays claimed and is exhausted again after its lease",
                    message.id(),
                    e);
        }
    }

    /**
     * Shutdown interrupted the handler, so its failure says nothing about the message: put it back without counting
     * the attempt. The interrupt is cleared while releasing, because an interrupted thread can't get a connection.
     */
    private void releaseAfterInterrupt(InboxMessageRecord message) {
        boolean interrupted = Thread.interrupted();
        try {
            inbox.release(message);
        } catch (RuntimeException e) {
            log.error("Could not release interrupted inbox message {}; it is retried after its lease", message.id(), e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** {@code backoff-initial × 2^(attempts − 1)}, capped at {@code backoff-max}. */
    Duration backoff(int attempts) {
        Duration initial = properties.inbox().backoffInitial();
        Duration max = properties.inbox().backoffMax();
        Duration backoff = initial.multipliedBy(1L << Math.min(Math.max(attempts - 1, 0), MAX_DOUBLINGS));
        return backoff.compareTo(max) > 0 ? max : backoff;
    }

    private static String lastErrorOrLeaseExpired(InboxMessageRecord message) {
        return message.lastError() != null ? message.lastError() : "Lease expired on the last attempt";
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() == null
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
