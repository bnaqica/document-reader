package com.bnaqica.documentreader.publisher;

import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.service.OutboxMessageService;
import com.bnaqica.documentreader.service.WorkerSignalService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes a step's next message with the step's own data: only inside the caller's transaction, so the message
 * commits or rolls back with it. On commit, Postgres delivers {@code NOTIFY outbox_ready} to other instances and this
 * instance's dispatcher is woken directly.
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxMessageService outbox;
    private final WorkerSignalService signals;
    private final JsonMapper jsonMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(MessageType type, UUID aggregateId, Object payload) {
        outbox.create(type, aggregateId, jsonMapper.valueToTree(payload));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                signals.signalDispatcher();
            }
        });
    }
}
