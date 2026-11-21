package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.config.DocumentReaderProperties;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompletionWaitServiceImpl implements CompletionWaitService {

    private final ConcurrentMap<UUID, CompletableFuture<Void>> waiters = new ConcurrentHashMap<>();

    private final BatchService batches;
    private final DocumentReaderProperties properties;

    @Override
    public boolean await(UUID batchId, Duration timeout) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // the batch may not be committed yet, so nobody can process it, and re-reads would come from the cache
            throw new IllegalStateException("Wait for batch " + batchId + " outside any transaction");
        }
        // register before the first read, so a completion between the read and the registration isn't missed
        CompletableFuture<Void> completed = waiters.computeIfAbsent(batchId, id -> new CompletableFuture<>());
        try {
            if (isFinal(batchId)) {
                return true;
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            long slice = properties.waitSettings().pollInterval().toNanos();
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0) {
                waitForNotification(completed, Math.min(remaining, slice));
                // a notification is a hint, not proof: only a final status ends the wait
                if (isFinalTolerant(batchId)) {
                    return true;
                }
            }
            log.debug("Gave up waiting for batch {} after {}", batchId, timeout);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Stopped waiting for batch {}: the waiting thread was interrupted", batchId);
            return false;
        } finally {
            waiters.remove(batchId, completed);
        }
    }

    /** Waits up to {@code nanos} for the notification; once it has come, just paces the re-reads. */
    private static void waitForNotification(CompletableFuture<Void> completed, long nanos) throws InterruptedException {
        if (completed.isDone()) {
            TimeUnit.NANOSECONDS.sleep(nanos);
            return;
        }
        try {
            completed.get(nanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            // the poll re-reads the status
        } catch (ExecutionException e) {
            throw new IllegalStateException("A completion future never fails", e);
        }
    }

    /** A database blip while polling keeps the wait going instead of failing the caller. */
    private boolean isFinalTolerant(UUID batchId) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        try {
            return isFinal(batchId);
        } catch (DataAccessException e) {
            log.warn(
                    "Could not re-read batch {} while waiting ({}); still waiting",
                    batchId,
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /** Also wakes a local waiter after commit, so it doesn't depend on the listener being connected. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyBatchCompleted(UUID batchId) {
        batches.notifyCompleted(batchId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                onBatchCompleted(batchId);
            }
        });
    }

    @Override
    public void onBatchCompleted(UUID batchId) {
        CompletableFuture<Void> completed = waiters.get(batchId);
        if (completed != null) {
            completed.complete(null);
            log.debug("Woke the waiter for batch {}", batchId);
        }
    }

    @Override
    public int waitingCount() {
        return waiters.size();
    }

    private boolean isFinal(UUID batchId) {
        return batches.get(batchId).status().isFinal();
    }
}
