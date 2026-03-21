package com.tracing.core.sink;

import com.tracing.core.CompletedTrace;

import java.util.List;

public final class CompositeSink implements LogSink {

    private final List<LogSink> sinks;

    public CompositeSink(List<LogSink> sinks) {
        this.sinks = List.copyOf(sinks);
    }

    @Override
    public void write(CompletedTrace trace) {
        for (LogSink sink : sinks) {
            try {
                sink.write(trace);
            } catch (Exception e) {
                System.err.println("[trace-log] Sink " + sink.getClass().getSimpleName()
                        + " failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void flush() {
        for (LogSink sink : sinks) {
            try {
                sink.flush();
            } catch (Exception e) {
                System.err.println("[trace-log] Sink flush failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        for (LogSink sink : sinks) {
            try {
                sink.close();
            } catch (Exception e) {
                System.err.println("[trace-log] Sink close failed: " + e.getMessage());
            }
        }
    }
}
