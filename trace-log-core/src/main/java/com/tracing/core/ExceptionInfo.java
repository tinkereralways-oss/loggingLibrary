package com.tracing.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;

public final class ExceptionInfo {

    private static final int MAX_STACK_TRACE_LENGTH = 4096;

    private final String type;
    private final String message;
    private final String stackTrace;

    public ExceptionInfo(String type, String message, String stackTrace) {
        this.type = Objects.requireNonNull(type);
        this.message = message;
        this.stackTrace = stackTrace;
    }

    public static ExceptionInfo from(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            throwable.printStackTrace(pw);
        }
        String fullTrace = sw.toString();
        String truncatedTrace = fullTrace.length() > MAX_STACK_TRACE_LENGTH
                ? fullTrace.substring(0, MAX_STACK_TRACE_LENGTH) + "\n... [truncated]"
                : fullTrace;
        return new ExceptionInfo(
                throwable.getClass().getName(),
                throwable.getMessage(),
                truncatedTrace
        );
    }

    public String getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public String getStackTrace() {
        return stackTrace;
    }
}
