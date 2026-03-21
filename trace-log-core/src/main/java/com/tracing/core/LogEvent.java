package com.tracing.core;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

public final class LogEvent {

    private final String eventName;
    private final Instant timestamp;
    private final Severity severity;
    private final String origin;
    private final Map<String, Object> dataPoints;
    private final Map<String, Number> metrics;
    private final String message;
    private final ExceptionInfo exception;
    private final Long durationMs;

    LogEvent(String eventName, Instant timestamp, Severity severity, String origin,
             Map<String, Object> dataPoints, Map<String, Number> metrics,
             String message, ExceptionInfo exception, Long durationMs) {
        this.eventName = eventName;
        this.timestamp = timestamp;
        this.severity = severity;
        this.origin = origin;
        this.dataPoints = dataPoints == null ? Collections.emptyMap() : Collections.unmodifiableMap(dataPoints);
        this.metrics = metrics == null ? Collections.emptyMap() : Collections.unmodifiableMap(metrics);
        this.message = message;
        this.exception = exception;
        this.durationMs = durationMs;
    }

    public String getEventName() {
        return eventName;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getOrigin() {
        return origin;
    }

    public Map<String, Object> getDataPoints() {
        return dataPoints;
    }

    public Map<String, Number> getMetrics() {
        return metrics;
    }

    public String getMessage() {
        return message;
    }

    public ExceptionInfo getException() {
        return exception;
    }

    public Long getDurationMs() {
        return durationMs;
    }
}
