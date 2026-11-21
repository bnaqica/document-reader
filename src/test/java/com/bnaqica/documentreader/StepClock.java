package com.bnaqica.documentreader;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A clock for tests that starts at {@link #START} and moves one second forward each time it is read, so records
 * created one after another get known, increasing times.
 */
public final class StepClock extends Clock {

    public static final Instant START = Instant.parse("2026-11-20T16:00:00Z");

    private final AtomicLong reads = new AtomicLong();

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    /** Back to {@link #START}; call before each test, because a Spring test context shares one clock. */
    public void reset() {
        reads.set(0);
    }

    @Override
    public Instant instant() {
        return START.plusSeconds(reads.getAndIncrement());
    }
}
