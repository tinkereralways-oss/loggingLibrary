package com.tracing.core.sampling;

import com.tracing.core.CompletedTrace;
import com.tracing.core.TraceContextManager;
import com.tracing.core.TraceStatus;
import com.tracing.core.buffer.BufferConfig;
import com.tracing.core.buffer.BufferManager;
import com.tracing.core.id.UlidGenerator;
import com.tracing.core.sink.LogSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BufferManagerSamplingTest {

    private final TraceContextManager contextManager =
            new TraceContextManager(new UlidGenerator(), "test-service", 100);
    private BufferManager bufferManager;

    @AfterEach
    void tearDown() {
        if (bufferManager != null) {
            bufferManager.shutdown();
        }
    }

    @Test
    void alwaysSampleStrategy_acceptsAllTraces() {
        TestSink sink = new TestSink();
        bufferManager = new BufferManager(sink, new BufferConfig(1, 300, 10000), new AlwaysSampleStrategy());

        for (int i = 0; i < 5; i++) {
            bufferManager.submit(createTrace(TraceStatus.SUCCESS));
        }
        bufferManager.shutdown();
        bufferManager = null;

        assertEquals(5, sink.traces.size());
        assertEquals(0, bufferManager == null ? 0 : bufferManager.getSampledOutCount());
    }

    @Test
    void zeroRateStrategy_onlyAcceptsErrors() {
        TestSink sink = new TestSink();
        bufferManager = new BufferManager(sink, new BufferConfig(1, 300, 10000), new RateSamplingStrategy(0.0));

        bufferManager.submit(createTrace(TraceStatus.SUCCESS));
        bufferManager.submit(createTrace(TraceStatus.SUCCESS));
        bufferManager.submit(createTrace(TraceStatus.ERROR));
        long sampledOut = bufferManager.getSampledOutCount();
        bufferManager.shutdown();
        bufferManager = null;

        assertEquals(1, sink.traces.size());
        assertEquals(TraceStatus.ERROR, sink.traces.get(0).getStatus());
        assertEquals(2, sampledOut);
    }

    @Test
    void sampledOutCount_tracksFilteredTraces() {
        TestSink sink = new TestSink();
        bufferManager = new BufferManager(sink, new BufferConfig(1, 300, 10000), new RateSamplingStrategy(0.0));

        for (int i = 0; i < 10; i++) {
            bufferManager.submit(createTrace(TraceStatus.SUCCESS));
        }
        assertEquals(10, bufferManager.getSampledOutCount());
    }

    @Test
    void backwardCompatConstructor_usesAlwaysSample() {
        TestSink sink = new TestSink();
        bufferManager = new BufferManager(sink, new BufferConfig(1, 300, 10000));

        for (int i = 0; i < 5; i++) {
            bufferManager.submit(createTrace(TraceStatus.SUCCESS));
        }
        bufferManager.shutdown();
        bufferManager = null;

        assertEquals(5, sink.traces.size());
    }

    private CompletedTrace createTrace(TraceStatus status) {
        contextManager.startTrace("test-entry");
        return contextManager.endTrace(status).orElseThrow();
    }

    static class TestSink implements LogSink {
        final List<CompletedTrace> traces = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void write(CompletedTrace trace) {
            traces.add(trace);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }
}
