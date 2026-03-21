package com.tracing.core;

import com.tracing.core.buffer.BufferConfig;
import com.tracing.core.buffer.BufferManager;
import com.tracing.core.id.UlidGenerator;
import com.tracing.core.sink.LogSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TraceLogTest {

    private TraceContextManager contextManager;
    private BufferManager bufferManager;
    private TestSink testSink;

    @BeforeEach
    void setUp() {
        testSink = new TestSink();
        contextManager = new TraceContextManager(new UlidGenerator(), "test-service", 1000);
        bufferManager = new BufferManager(testSink, new BufferConfig(1, 300, 10000));
        TraceLog.initialize(contextManager, bufferManager);
    }

    @AfterEach
    void tearDown() {
        contextManager.clear();
        bufferManager.shutdown();
        TraceLog.reset();
    }

    @Test
    void traceLifecycle_createsAndCompletesTrace() throws InterruptedException {
        TraceContext ctx = contextManager.startTrace("REST GET /api/test");
        assertNotNull(ctx.getTraceId());
        assertEquals("test-service", ctx.getServiceName());

        TraceLog.event("user.lookup").data("userId", "123").info();
        TraceLog.event("db.query").data("table", "users").metric("rowCount", 5).info();
        TraceLog.metadata("userId", "123");

        Optional<String> traceId = TraceLog.currentTraceId();
        assertTrue(traceId.isPresent());
        assertEquals(ctx.getTraceId(), traceId.get());

        CompletedTrace completed = contextManager.endTrace(TraceStatus.SUCCESS).orElseThrow();
        assertEquals(2, completed.getEvents().size());
        assertEquals(TraceStatus.SUCCESS, completed.getStatus());
        assertEquals("123", completed.getMetadata().get("userId"));

        bufferManager.submit(completed);
        waitForSink(1);
        assertEquals(1, testSink.traces.size());
        assertEquals(completed.getTraceId(), testSink.traces.get(0).getTraceId());
    }

    @Test
    void eventOutsideTrace_isNoOp() {
        TraceLog.event("orphan.event").data("key", "value").info();
        TraceLog.metadata("key", "value");
        assertTrue(TraceLog.currentTraceId().isEmpty());
    }

    @Test
    void timedEvent_recordsDuration() throws InterruptedException {
        contextManager.startTrace("REST GET /api/timed");

        try (TimedEvent timer = TraceLog.timedEvent("slow.operation")) {
            timer.data("input", "test");
            Thread.sleep(50);
        }

        CompletedTrace completed = contextManager.endTrace(TraceStatus.SUCCESS).orElseThrow();
        assertEquals(1, completed.getEvents().size());

        LogEvent event = completed.getEvents().get(0);
        assertEquals("slow.operation", event.getEventName());
        assertNotNull(event.getDurationMs());
        assertTrue(event.getDurationMs() >= 40);
    }

    @Test
    void timedEvent_customSeverity() {
        contextManager.startTrace("REST GET /api/timed-warn");

        try (TimedEvent timer = TraceLog.timedEvent("risky.operation").severity(Severity.WARN)) {
            timer.data("risk", "high");
        }

        CompletedTrace completed = contextManager.endTrace(TraceStatus.SUCCESS).orElseThrow();
        assertEquals(Severity.WARN, completed.getEvents().get(0).getSeverity());
    }

    @Test
    void nestedManualTraces_useStack() {
        contextManager.startTrace("outer");
        String outerId = TraceLog.currentTraceId().orElseThrow();

        try (ManualTrace inner = TraceLog.startManualTrace("inner")) {
            String innerId = TraceLog.currentTraceId().orElseThrow();
            assertNotEquals(outerId, innerId);
            TraceLog.event("inner.event").info();
        }

        assertEquals(outerId, TraceLog.currentTraceId().orElseThrow());
        contextManager.endTrace(TraceStatus.SUCCESS);
    }

    @Test
    void traceIdFormat_isUlid26Chars() throws InterruptedException {
        UlidGenerator generator = new UlidGenerator();
        String id = generator.generate();
        assertEquals(26, id.length());
        assertTrue(id.matches("[0-9A-HJKMNP-TV-Z]+"));

        String id1 = generator.generate();
        Thread.sleep(2);
        String id2 = generator.generate();
        assertTrue(id2.compareTo(id1) > 0, "ULID generated later should sort after earlier one");
    }

    @Test
    void maxEventsPerTrace_caps() {
        contextManager = new TraceContextManager(new UlidGenerator(), "test-service", 5);
        TraceLog.initialize(contextManager, bufferManager);

        contextManager.startTrace("REST GET /api/capped");

        for (int i = 0; i < 10; i++) {
            TraceLog.event("event." + i).info();
        }

        CompletedTrace completed = contextManager.endTrace(TraceStatus.SUCCESS).orElseThrow();
        assertEquals(5, completed.getEvents().size());
    }

    @Test
    void maxEventsPerTrace_exactBoundary() {
        contextManager = new TraceContextManager(new UlidGenerator(), "test-service", 3);
        TraceLog.initialize(contextManager, bufferManager);

        contextManager.startTrace("REST GET /api/boundary");

        TraceLog.event("event.1").info();
        TraceLog.event("event.2").info();
        TraceLog.event("event.3").info();
        TraceLog.event("event.4").info(); // should be dropped

        CompletedTrace completed = contextManager.endTrace(TraceStatus.SUCCESS).orElseThrow();
        assertEquals(3, completed.getEvents().size());
        assertEquals(3, completed.getEvents().get(2).getEventName().charAt(6) - '0');
    }

    @Test
    void maxEventsPerTrace_concurrent() throws InterruptedException {
        int maxEvents = 100;
        contextManager = new TraceContextManager(new UlidGenerator(), "test-service", maxEvents);
        TraceLog.initialize(contextManager, bufferManager);

        TraceContext ctx = contextManager.startTrace("REST GET /api/concurrent");
        CountDownLatch latch = new CountDownLatch(1);
        int threadCount = 10;
        int eventsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadNum = t;
            executor.submit(() -> {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < eventsPerThread; i++) {
                    ctx.addEvent(new LogEvent("concurrent." + threadNum + "." + i,
                            java.time.Instant.now(), Severity.INFO, "test",
                            null, null, null, null, null));
                }
            });
        }
        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        CompletedTrace completed = contextManager.endTrace(TraceStatus.SUCCESS).orElseThrow();
        assertEquals(maxEvents, completed.getEvents().size(),
                "Concurrent addEvent should respect maxEvents exactly");
    }

    @Test
    void errorEvent_capturesException() {
        contextManager.startTrace("REST GET /api/error");

        RuntimeException ex = new RuntimeException("something broke");
        TraceLog.event("operation.failed").data("input", "bad").error(ex);

        CompletedTrace completed = contextManager.endTrace(TraceStatus.ERROR).orElseThrow();
        LogEvent event = completed.getEvents().get(0);
        assertEquals(Severity.ERROR, event.getSeverity());
        assertNotNull(event.getException());
        assertEquals("java.lang.RuntimeException", event.getException().getType());
        assertEquals("something broke", event.getException().getMessage());
    }

    @Test
    void parentTraceId_isPreserved() {
        contextManager.startTrace("REST GET /api/child", "parent-trace-123");
        CompletedTrace completed = contextManager.endTrace(TraceStatus.SUCCESS).orElseThrow();
        assertEquals("parent-trace-123", completed.getParentTraceId());
    }

    @Test
    void endTrace_emptyStack_returnsEmpty() {
        Optional<CompletedTrace> result = contextManager.endTrace(TraceStatus.SUCCESS);
        assertTrue(result.isEmpty());
    }

    @Test
    void bufferManager_backpressureDropsTraces() {
        BufferManager smallBuffer = new BufferManager(testSink, new BufferConfig(60, 300, 3));
        try {
            for (int i = 0; i < 10; i++) {
                contextManager.startTrace("trace-" + i);
                CompletedTrace completed = contextManager.endTrace(TraceStatus.SUCCESS).orElseThrow();
                smallBuffer.submit(completed);
            }
            assertTrue(smallBuffer.getDroppedCount() > 0, "Should have dropped traces due to backpressure");
        } finally {
            smallBuffer.shutdown();
        }
    }

    @Test
    void bufferManager_shutdownDrainsQueue() {
        contextManager.startTrace("REST GET /api/drain");
        TraceLog.event("test.event").info();
        CompletedTrace completed = contextManager.endTrace(TraceStatus.SUCCESS).orElseThrow();

        bufferManager.submit(completed);
        bufferManager.shutdown();

        assertEquals(1, testSink.traces.size());
    }

    @Test
    void bufferManager_writeRetryOnFailure() throws InterruptedException {
        FailOnceSink failOnceSink = new FailOnceSink();
        BufferManager retryBuffer = new BufferManager(failOnceSink, new BufferConfig(1, 300, 100));
        try {
            contextManager.startTrace("REST GET /api/retry");
            CompletedTrace completed = contextManager.endTrace(TraceStatus.SUCCESS).orElseThrow();
            retryBuffer.submit(completed);
            // Wait for drain
            for (int i = 0; i < 30; i++) {
                if (failOnceSink.writeCount > 0) break;
                Thread.sleep(100);
            }
            assertTrue(failOnceSink.writeCount > 0, "Should have succeeded on retry");
            assertEquals(0, retryBuffer.getFailedCount());
        } finally {
            retryBuffer.shutdown();
        }
    }

    @Test
    void threadLocalCleanup_afterClear() {
        contextManager.startTrace("REST GET /api/leak");
        TraceLog.event("test").info();
        contextManager.clear();
        assertTrue(TraceLog.currentTraceId().isEmpty());
    }

    @Test
    void exceptionInfo_truncatesLongStackTrace() {
        RuntimeException nested = new RuntimeException("root cause");
        for (int i = 0; i < 100; i++) {
            nested = new RuntimeException("wrapper " + i, nested);
        }
        ExceptionInfo info = ExceptionInfo.from(nested);
        assertNotNull(info);
        assertTrue(info.getStackTrace().length() <= 4096 + 20,
                "Stack trace should be truncated");
        assertTrue(info.getStackTrace().endsWith("[truncated]"));
    }

    @Test
    void bufferConfig_rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new BufferConfig(0, 300, 10000));
        assertThrows(IllegalArgumentException.class, () -> new BufferConfig(5, -1, 10000));
        assertThrows(IllegalArgumentException.class, () -> new BufferConfig(5, 300, 0));
    }

    private void waitForSink(int expectedCount) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            if (testSink.traces.size() >= expectedCount) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Timeout waiting for sink to reach " + expectedCount + " traces, got: " + testSink.traces.size());
    }

    static class TestSink implements LogSink {
        final List<CompletedTrace> traces = Collections.synchronizedList(new ArrayList<>());

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
    }

    static class FailOnceSink implements LogSink {
        volatile int attemptCount = 0;
        volatile int writeCount = 0;

        @Override
        public void write(CompletedTrace trace) {
            attemptCount++;
            if (attemptCount == 1) {
                throw new RuntimeException("Simulated sink failure");
            }
            writeCount++;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
