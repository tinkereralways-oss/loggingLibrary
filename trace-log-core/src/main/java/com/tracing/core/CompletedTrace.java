package com.tracing.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CompletedTrace {

    private final String traceId;
    private final String serviceName;
    private final String entryPoint;
    private final Instant startTime;
    private final Instant endTime;
    private final long durationMs;
    private final TraceStatus status;
    private final String parentTraceId;
    private final Map<String, String> metadata;
    private final List<LogEvent> events;

    CompletedTrace(String traceId, String serviceName, String entryPoint,
                   Instant startTime, Instant endTime, long durationMs,
                   TraceStatus status, String parentTraceId,
                   Map<String, String> metadata, List<LogEvent> events) {
        this.traceId = traceId;
        this.serviceName = serviceName;
        this.entryPoint = entryPoint;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationMs = durationMs;
        this.status = status;
        this.parentTraceId = parentTraceId;
        this.metadata = metadata;
        this.events = events;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getEntryPoint() {
        return entryPoint;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public TraceStatus getStatus() {
        return status;
    }

    public String getParentTraceId() {
        return parentTraceId;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public List<LogEvent> getEvents() {
        return events;
    }
}
