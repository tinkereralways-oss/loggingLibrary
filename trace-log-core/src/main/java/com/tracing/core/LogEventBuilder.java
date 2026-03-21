package com.tracing.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LogEventBuilder {

    private static final String[] FRAMEWORK_PREFIXES = {
            "com.tracing.core.",
            "com.tracing.spring.",
            "org.springframework.",
            "org.apache.",
            "java.",
            "javax.",
            "jakarta.",
            "jdk.",
            "sun.",
            "com.sun.",
            "org.aspectj.",
            "io.micrometer."
    };

    private static final ConcurrentHashMap<String, Boolean> FRAME_FILTER_CACHE = new ConcurrentHashMap<>();
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    private final String eventName;
    private final TraceContext context;
    private final Instant timestamp;
    private Map<String, Object> dataPoints;
    private Map<String, Number> metrics;
    private String message;
    private ExceptionInfo exception;
    private Long durationMs;

    LogEventBuilder(String eventName, TraceContext context) {
        this.eventName = eventName;
        this.context = context;
        this.timestamp = Instant.now();
    }

    public LogEventBuilder data(String key, Object value) {
        if (dataPoints == null) {
            dataPoints = new LinkedHashMap<>();
        }
        dataPoints.put(key, value);
        return this;
    }

    public LogEventBuilder metric(String key, Number value) {
        if (metrics == null) {
            metrics = new LinkedHashMap<>();
        }
        metrics.put(key, value);
        return this;
    }

    public LogEventBuilder message(String message) {
        this.message = message;
        return this;
    }

    public LogEventBuilder duration(long durationMs) {
        this.durationMs = durationMs;
        return this;
    }

    public void trace() {
        emit(Severity.TRACE);
    }

    public void debug() {
        emit(Severity.DEBUG);
    }

    public void info() {
        emit(Severity.INFO);
    }

    public void warn() {
        emit(Severity.WARN);
    }

    public void error() {
        emit(Severity.ERROR);
    }

    public void error(Throwable throwable) {
        this.exception = ExceptionInfo.from(throwable);
        emit(Severity.ERROR);
    }

    private void emit(Severity severity) {
        if (context == null) {
            return;
        }
        String origin = captureOrigin();
        LogEvent event = new LogEvent(eventName, timestamp, severity, origin,
                dataPoints, metrics, message, exception, durationMs);
        context.addEvent(event);
    }

    private String captureOrigin() {
        return STACK_WALKER
                .walk(frames -> frames
                        .limit(50)
                        .filter(f -> isApplicationFrame(f.getClassName()))
                        .findFirst()
                        .map(f -> f.getClassName() + "." + f.getMethodName())
                        .orElse("unknown"));
    }

    private static final int MAX_CACHE_SIZE = 10_000;

    private static boolean isApplicationFrame(String className) {
        Boolean cached = FRAME_FILTER_CACHE.get(className);
        if (cached != null) {
            return cached;
        }
        boolean result = checkApplicationFrame(className);
        if (FRAME_FILTER_CACHE.size() < MAX_CACHE_SIZE) {
            FRAME_FILTER_CACHE.put(className, result);
        }
        return result;
    }

    private static boolean checkApplicationFrame(String className) {
        if (className.contains("$$")) {
            return false;
        }
        for (String prefix : FRAMEWORK_PREFIXES) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }
}
