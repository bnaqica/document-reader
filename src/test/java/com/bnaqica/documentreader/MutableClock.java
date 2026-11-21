package com.bnaqica.documentreader;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock that stands still until a test moves it, for checking times computed from "now". */
public final class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-11-21T09:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    public void reset() {
        now.set(START);
    }

    public void advance(Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }
}
