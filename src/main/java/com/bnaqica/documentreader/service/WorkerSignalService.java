package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.entity.MessageType;
import java.time.Duration;

/**
 * In-memory wake-up signals: one per message type for its worker, and one for the dispatcher. Signals sent before
 * a wait collapse into one wake-up. Lets a publisher or notification wake a loop without one listener calling another.
 */
public interface WorkerSignalService {

    void signal(MessageType type);

    void signalDispatcher();

    /** True when signalled within {@code timeout}; false when it passes without a signal. */
    boolean awaitSignal(MessageType type, Duration timeout);

    boolean awaitDispatcherSignal(Duration timeout);

    /** Wakes every waiter once, so loops can see a shutdown without being interrupted. */
    void wakeAll();
}
