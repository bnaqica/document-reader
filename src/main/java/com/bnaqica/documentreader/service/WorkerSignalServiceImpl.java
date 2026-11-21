package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.entity.MessageType;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class WorkerSignalServiceImpl implements WorkerSignalService {

    private final Map<MessageType, Semaphore> workers = new EnumMap<>(MessageType.class);
    private final Semaphore dispatcher = new Semaphore(0);

    public WorkerSignalServiceImpl() {
        for (MessageType type : MessageType.values()) {
            workers.put(type, new Semaphore(0));
        }
    }

    @Override
    public void signal(MessageType type) {
        release(workers.get(type));
    }

    @Override
    public void signalDispatcher() {
        release(dispatcher);
    }

    @Override
    public boolean awaitSignal(MessageType type, Duration timeout) {
        return await(workers.get(type), timeout);
    }

    @Override
    public boolean awaitDispatcherSignal(Duration timeout) {
        return await(dispatcher, timeout);
    }

    @Override
    public void wakeAll() {
        workers.values().forEach(WorkerSignalServiceImpl::release);
        release(dispatcher);
    }

    /** At most one permit, so many signals before a wait wake it once. */
    private static void release(Semaphore semaphore) {
        synchronized (semaphore) {
            if (semaphore.availablePermits() == 0) {
                semaphore.release();
            }
        }
    }

    private static boolean await(Semaphore semaphore, Duration timeout) {
        try {
            return semaphore.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
