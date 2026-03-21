package com.tracing.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class TraceContext {

    private final String traceId;
    private final String serviceName;
    private final String entryPoint;
    private final Instant startTime;
    private final String parentTraceId;
    private final ConcurrentLinkedQueue<LogEvent> events;
    private final ConcurrentHashMap<String, String> metadata;
    private final AtomicInteger eventCount;
    private final int maxEvents;

    TraceContext(String traceId, String serviceName, String entryPoint,
                 String parentTraceId, int maxEvents) {
        this.traceId = traceId;
        this.serviceName = serviceName;
        this.entryPoint = entryPoint;
        this.startTime = Instant.now();
        this.parentTraceId = parentTraceId;
        this.events = new ConcurrentLinkedQueue<>();
        this.metadata = new ConcurrentHashMap<>();
        this.eventCount = new AtomicInteger(0);
        this.maxEvents = maxEvents;
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

    public String getParentTraceId() {
        return parentTraceId;
    }

    public void addMetadata(String key, String value) {
        metadata.put(key, value);
    }

    void addEvent(LogEvent event) {
        int count;
        while ((count = eventCount.get()) < maxEvents) {
            if (eventCount.compareAndSet(count, count + 1)) {
                events.add(event);
                if (count + 1 == maxEvents) {
                    System.err.println("[trace-log] Event cap reached (" + maxEvents
                            + ") for trace " + traceId + " — subsequent events will be dropped");
                }
                return;
            }
        }
    }

    public int getEventCount() {
        return eventCount.get();
    }

    CompletedTrace complete(TraceStatus status) {
        Instant endTime = Instant.now();
        long durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();
        List<LogEvent> eventList = new ArrayList<>(events);
        Map<String, String> metadataSnapshot = Collections.unmodifiableMap(new HashMap<>(metadata));
        return new CompletedTrace(traceId, serviceName, entryPoint, startTime, endTime,
                durationMs, status, parentTraceId, metadataSnapshot,
                Collections.unmodifiableList(eventList));
    }
}
