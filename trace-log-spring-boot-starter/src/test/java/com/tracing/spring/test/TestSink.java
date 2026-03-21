package com.tracing.spring.test;

import com.tracing.core.CompletedTrace;
import com.tracing.core.sink.LogSink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TestSink implements LogSink {

    private final List<CompletedTrace> traces = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void write(CompletedTrace trace) {
        traces.add(trace);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    public List<CompletedTrace> getTraces() {
        return traces;
    }

    public void clear() {
        traces.clear();
    }
}
