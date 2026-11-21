package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.entity.MessageType;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A handler for one type whose behaviour each test sets, recording what it was given. */
class StubHandler implements InboxMessageHandler {

    private final MessageType type;
    private final Queue<InboxMessageRecord> handled = new ConcurrentLinkedQueue<>();
    private final Queue<String> exhausted = new ConcurrentLinkedQueue<>();
    private final Queue<Boolean> transactionActive = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Consumer<InboxMessageRecord>> onHandle = new AtomicReference<>(message -> {});
    private final AtomicReference<Runnable> onExhaust = new AtomicReference<>(() -> {});

    StubHandler(MessageType type) {
        this.type = type;
    }

    @Override
    public MessageType type() {
        return type;
    }

    @Override
    public void handle(InboxMessageRecord message) {
        transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
        handled.add(message);
        onHandle.get().accept(message);
    }

    @Override
    public void onExhausted(InboxMessageRecord message, String lastError) {
        onExhaust.get().run();
        exhausted.add(lastError);
    }

    void whenHandling(Consumer<InboxMessageRecord> behaviour) {
        onHandle.set(behaviour);
    }

    void whenExhausted(Runnable behaviour) {
        onExhaust.set(behaviour);
    }

    void failWith(String error) {
        whenHandling(message -> {
            throw new IllegalStateException(error);
        });
    }

    List<InboxMessageRecord> handled() {
        return List.copyOf(handled);
    }

    List<String> exhausted() {
        return List.copyOf(exhausted);
    }

    List<Boolean> transactionActive() {
        return List.copyOf(transactionActive);
    }

    void reset() {
        handled.clear();
        exhausted.clear();
        transactionActive.clear();
        onHandle.set(message -> {});
        onExhaust.set(() -> {});
    }
}
