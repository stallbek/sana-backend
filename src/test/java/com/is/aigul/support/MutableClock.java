package com.is.aigul.support;

import java.time.*;

public class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");
    public void advance(Duration duration) { now = now.plus(duration); }
    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
}
