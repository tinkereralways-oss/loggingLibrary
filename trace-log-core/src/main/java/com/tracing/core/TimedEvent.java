package com.tracing.core;

import java.time.Duration;
import java.time.Instant;

public final class TimedEvent implements AutoCloseable {

    private final LogEventBuilder builder;
    private final Instant startTime;
    private Severity severity = Severity.INFO;

    TimedEvent(String eventName, TraceContext context) {
        this.startTime = Instant.now();
        this.builder = new LogEventBuilder(eventName, context);
    }

    public TimedEvent data(String key, Object value) {
        builder.data(key, value);
        return this;
    }

    public TimedEvent metric(String key, Number value) {
        builder.metric(key, value);
        return this;
    }

    public TimedEvent message(String message) {
        builder.message(message);
        return this;
    }

    public TimedEvent severity(Severity severity) {
        this.severity = severity;
        return this;
    }

    @Override
    public void close() {
        long elapsed = Duration.between(startTime, Instant.now()).toMillis();
        builder.duration(elapsed);
        switch (severity) {
            case TRACE -> builder.trace();
            case DEBUG -> builder.debug();
            case WARN -> builder.warn();
            case ERROR -> builder.error();
            default -> builder.info();
        }
    }
}
