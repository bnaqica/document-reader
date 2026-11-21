package com.bnaqica.documentreader.processors;

import com.bnaqica.documentreader.dto.response.OutboxMessageRecord;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.service.InboxMessageService;
import com.bnaqica.documentreader.service.OutboxMessageService;
import com.bnaqica.documentreader.service.WorkerSignalService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves undispatched outbox messages into the inbox. Each round locks rows with {@code SKIP LOCKED}, so dispatchers on
 * several instances never move the same row; the inbox's primary key ignores a message already there. After a round
 * commits, Postgres delivers {@code NOTIFY inbox_ready} and this instance's workers for those types are woken.
 */
@Slf4j
@Component
public class OutboxDispatcher {

    static final int ROUND_SIZE = 100;

    private final OutboxMessageService outbox;
    private final InboxMessageService inbox;
    private final WorkerSignalService signals;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public OutboxDispatcher(
            OutboxMessageService outbox,
            InboxMessageService inbox,
            WorkerSignalService signals,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.inbox = inbox;
        this.signals = signals;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Dispatches rounds until one finds nothing left; returns how many messages this dispatcher moved. */
    public int dispatchPending() {
        int total = 0;
        int moved;
        do {
            moved = transaction.execute(status -> dispatchRound());
            total += moved;
        } while (moved > 0);
        if (total > 0) {
            log.info("Dispatched {} outbox messages", total);
        }
        return total;
    }

    private int dispatchRound() {
        List<OutboxMessageRecord> messages = outbox.lockUndispatched(ROUND_SIZE);
        if (messages.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        List<MessageType> inserted = inbox.insertIfAbsent(messages, now);
        outbox.markDispatched(messages.stream().map(OutboxMessageRecord::id).toList(), now);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                inserted.forEach(signals::signal);
            }
        });
        return messages.size();
    }
}
