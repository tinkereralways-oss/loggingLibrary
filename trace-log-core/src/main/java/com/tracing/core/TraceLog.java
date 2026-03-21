package com.tracing.core;

import com.tracing.core.buffer.BufferManager;

import java.util.Optional;

public final class TraceLog {

    private static volatile TraceContextManager contextManager;
    private static volatile BufferManager bufferManager;
    private static final Object INIT_LOCK = new Object();

    private TraceLog() {
    }

    public static void initialize(TraceContextManager cm, BufferManager bm) {
        synchronized (INIT_LOCK) {
            contextManager = cm;
            bufferManager = bm;
        }
    }

    static void reset() {
        synchronized (INIT_LOCK) {
            contextManager = null;
            bufferManager = null;
        }
    }

    public static LogEventBuilder event(String eventName) {
        TraceContext ctx = currentContextOrNull();
        return new LogEventBuilder(eventName, ctx);
    }

    public static TimedEvent timedEvent(String eventName) {
        TraceContext ctx = currentContextOrNull();
        return new TimedEvent(eventName, ctx);
    }

    public static void metadata(String key, String value) {
        TraceContext ctx = currentContextOrNull();
        if (ctx != null) {
            ctx.addMetadata(key, value);
        }
    }

    public static ManualTrace startManualTrace(String entryPoint) {
        if (contextManager == null) {
            return new ManualTrace(null, null);
        }
        contextManager.startTrace(entryPoint);
        return new ManualTrace(contextManager, bufferManager);
    }

    public static Optional<String> currentTraceId() {
        TraceContext ctx = currentContextOrNull();
        return ctx == null ? Optional.empty() : Optional.of(ctx.getTraceId());
    }

    static TraceContextManager getContextManager() {
        return contextManager;
    }

    static BufferManager getBufferManager() {
        return bufferManager;
    }

    private static TraceContext currentContextOrNull() {
        TraceContextManager cm = contextManager;
        BufferManager bm = bufferManager;
        if (cm == null) {
            return null;
        }
        if (bm != null && bm.isFlushing()) {
            return null;
        }
        return cm.currentContext().orElse(null);
    }
}
