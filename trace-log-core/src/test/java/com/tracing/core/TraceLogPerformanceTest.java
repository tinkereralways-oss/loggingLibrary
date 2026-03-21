package com.tracing.core;

import com.tracing.core.buffer.BufferConfig;
import com.tracing.core.buffer.BufferManager;
import com.tracing.core.id.TraceIdGenerator;
import com.tracing.core.id.UlidGenerator;
import com.tracing.core.sink.LogSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance test validating the trace-log library can sustain 20,000+ TPS.
 *
 * Each "transaction" is the full hot path a real request takes:
 *   1. startTrace()       — push TraceContext onto ThreadLocal stack
 *   2. TraceLog.event()   — build LogEvent with StackWalker origin capture
 *   3. metadata()         — write to ConcurrentHashMap
 *   4. endTrace()         — snapshot events/metadata into CompletedTrace
 *   5. bufferManager.submit() — enqueue into ConcurrentLinkedQueue
 *
 * The sink is a no-op counter so we measure the library overhead, not I/O.
 */
class TraceLogPerformanceTest {

    private TraceContextManager contextManager;
    private BufferManager bufferManager;
    private CountingSink sink;

    @BeforeEach
    void setUp() {
        sink = new CountingSink();
        contextManager = new TraceContextManager(new UlidGenerator(), "perf-service", 100);
        // Large queue so backpressure doesn't interfere with throughput measurement
        bufferManager = new BufferManager(sink, new BufferConfig(1, 300, 500_000));
        TraceLog.initialize(contextManager, bufferManager);
    }

    @AfterEach
    void tearDown() {
        contextManager.clear();
        bufferManager.shutdown();
        TraceLog.reset();
    }

    // ------------------------------------------------------------------
    // Primary gate: full trace lifecycle at 20k TPS
    // ------------------------------------------------------------------

    @Test
    void fullTraceLifecycle_sustains20kTPS() throws Exception {
        int targetTps = 20_000;
        int durationSeconds = 5;
        int totalTraces = targetTps * durationSeconds; // 100,000
        int threadCount = 16;
        int tracesPerThread = totalTraces / threadCount;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicLong completedTraces = new AtomicLong(0);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                // Each thread gets its own context manager to avoid ThreadLocal contention
                // (mirrors real-world: each HTTP thread has its own stack)
                TraceContextManager localCtx = new TraceContextManager(
                        new UlidGenerator(), "perf-service", 100);
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }

                for (int i = 0; i < tracesPerThread; i++) {
                    // 1. Start trace
                    localCtx.startTrace("REST GET /api/orders/" + i);

                    // 2. Add events (typical request has 3-5 events)
                    TraceLog.event("request.received")
                            .data("method", "GET")
                            .data("uri", "/api/orders/" + i)
                            .info();

                    TraceLog.event("db.query")
                            .data("table", "orders")
                            .metric("rowCount", 1)
                            .info();

                    TraceLog.event("request.completed")
                            .data("statusCode", 200)
                            .info();

                    // 3. Add metadata
                    TraceLog.metadata("userId", "user-" + i);

                    // 4. End trace → CompletedTrace
                    CompletedTrace completed = localCtx.endTrace(TraceStatus.SUCCESS).orElseThrow();

                    // 5. Submit to buffer
                    bufferManager.submit(completed);

                    completedTraces.incrementAndGet();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        long startNanos = System.nanoTime();
        go.countDown();

        executor.shutdown();
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS),
                "Threads did not complete within timeout");
        long elapsedNanos = System.nanoTime() - startNanos;

        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        long completed = completedTraces.get();
        double actualTps = completed / elapsedSeconds;

        // Drain remaining traces
        bufferManager.shutdown();
        long written = sink.getWriteCount();

        System.out.println("=== Full Trace Lifecycle Performance ===");
        System.out.println("  Threads:         " + threadCount);
        System.out.println("  Total traces:    " + completed);
        System.out.println("  Elapsed:         " + String.format("%.2f", elapsedSeconds) + "s");
        System.out.println("  Throughput:      " + String.format("%.0f", actualTps) + " TPS");
        System.out.println("  Sink written:    " + written);
        System.out.println("  Dropped:         " + bufferManager.getDroppedCount());
        System.out.println("  Target:          " + targetTps + " TPS");

        assertTrue(actualTps >= targetTps,
                "Throughput " + String.format("%.0f", actualTps)
                        + " TPS is below target " + targetTps + " TPS");
        assertEquals(totalTraces, completed, "All traces should complete");
    }

    // ------------------------------------------------------------------
    // Sustained load: verify no degradation over a longer window
    // ------------------------------------------------------------------

    @Test
    void sustainedLoad_noThroughputDegradation() throws Exception {
        int threadCount = 8;
        int tracesPerBatch = 5_000;
        int batches = 4;
        double[] batchTps = new double[batches];

        for (int batch = 0; batch < batches; batch++) {
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch go = new CountDownLatch(1);
            AtomicLong batchCompleted = new AtomicLong(0);
            int tracesPerThread = tracesPerBatch / threadCount;

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    TraceContextManager localCtx = new TraceContextManager(
                            new UlidGenerator(), "perf-service", 50);
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int i = 0; i < tracesPerThread; i++) {
                        localCtx.startTrace("REST GET /api/items");
                        TraceLog.event("service.call").data("id", i).info();
                        CompletedTrace completed = localCtx.endTrace(TraceStatus.SUCCESS).orElseThrow();
                        bufferManager.submit(completed);
                        batchCompleted.incrementAndGet();
                    }
                });
            }

            ready.await(10, TimeUnit.SECONDS);
            long startNanos = System.nanoTime();
            go.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
            long elapsedNanos = System.nanoTime() - startNanos;

            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            batchTps[batch] = batchCompleted.get() / elapsedSeconds;
        }

        System.out.println("=== Sustained Load (no degradation check) ===");
        for (int i = 0; i < batches; i++) {
            System.out.println("  Batch " + (i + 1) + ": " + String.format("%.0f", batchTps[i]) + " TPS");
        }

        // Last batch should be at least 70% of first batch (no severe degradation)
        double ratio = batchTps[batches - 1] / batchTps[0];
        System.out.println("  Last/First ratio: " + String.format("%.2f", ratio));
        assertTrue(ratio >= 0.70,
                "Throughput degraded from " + String.format("%.0f", batchTps[0])
                        + " to " + String.format("%.0f", batchTps[batches - 1])
                        + " TPS (ratio " + String.format("%.2f", ratio) + " < 0.70)");

        // Every batch must clear 20k TPS
        for (int i = 0; i < batches; i++) {
            assertTrue(batchTps[i] >= 20_000,
                    "Batch " + (i + 1) + " throughput " + String.format("%.0f", batchTps[i])
                            + " TPS is below 20,000 TPS");
        }
    }

    // ------------------------------------------------------------------
    // Hot-path latency: p99 of individual trace lifecycle
    // ------------------------------------------------------------------

    @Test
    void singleTraceLatency_p99Under500Micros() throws Exception {
        int warmupCount = 5_000;
        int measureCount = 50_000;

        TraceContextManager localCtx = new TraceContextManager(
                new UlidGenerator(), "perf-service", 50);

        // Warmup
        for (int i = 0; i < warmupCount; i++) {
            localCtx.startTrace("REST GET /warmup");
            TraceLog.event("warmup.event").data("i", i).info();
            CompletedTrace c = localCtx.endTrace(TraceStatus.SUCCESS).orElseThrow();
            bufferManager.submit(c);
        }

        // Measure
        long[] latencies = new long[measureCount];
        for (int i = 0; i < measureCount; i++) {
            long start = System.nanoTime();

            localCtx.startTrace("REST GET /api/orders/" + i);
            TraceLog.event("request.received")
                    .data("method", "GET")
                    .data("uri", "/api/orders")
                    .info();
            TraceLog.event("db.query")
                    .data("table", "orders")
                    .metric("rowCount", 1)
                    .info();
            TraceLog.metadata("userId", "user-123");
            CompletedTrace c = localCtx.endTrace(TraceStatus.SUCCESS).orElseThrow();
            bufferManager.submit(c);

            latencies[i] = System.nanoTime() - start;
        }

        java.util.Arrays.sort(latencies);
        long p50 = latencies[(int) (measureCount * 0.50)];
        long p95 = latencies[(int) (measureCount * 0.95)];
        long p99 = latencies[(int) (measureCount * 0.99)];
        long max = latencies[measureCount - 1];

        System.out.println("=== Single Trace Latency ===");
        System.out.println("  p50: " + (p50 / 1000) + " µs");
        System.out.println("  p95: " + (p95 / 1000) + " µs");
        System.out.println("  p99: " + (p99 / 1000) + " µs");
        System.out.println("  max: " + (max / 1000) + " µs");

        // At 20k TPS, each trace has a 50µs budget.
        // p99 under 500µs gives 10x headroom for GC pauses, I/O, etc.
        assertTrue(p99 < 500_000,
                "p99 latency " + (p99 / 1000) + " µs exceeds 500 µs budget");
    }

    // ------------------------------------------------------------------
    // Submit-only throughput (queue insertion, no trace creation)
    // ------------------------------------------------------------------

    @Test
    void bufferSubmit_throughputExceeds100kTPS() throws Exception {
        int threadCount = 16;
        int submitsPerThread = 50_000;
        int total = threadCount * submitsPerThread;

        // Pre-create traces to isolate submit() performance
        TraceContextManager localCtx = new TraceContextManager(
                new UlidGenerator(), "perf-service", 10);
        CompletedTrace[] traces = new CompletedTrace[total];
        for (int i = 0; i < total; i++) {
            localCtx.startTrace("pre-built-trace");
            traces[i] = localCtx.endTrace(TraceStatus.SUCCESS).orElseThrow();
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);

        for (int t = 0; t < threadCount; t++) {
            final int threadIdx = t;
            executor.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                int startIdx = threadIdx * submitsPerThread;
                for (int i = 0; i < submitsPerThread; i++) {
                    bufferManager.submit(traces[startIdx + i]);
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        long startNanos = System.nanoTime();
        go.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        long elapsedNanos = System.nanoTime() - startNanos;

        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double tps = total / elapsedSeconds;

        System.out.println("=== Buffer Submit Throughput ===");
        System.out.println("  Total submits: " + total);
        System.out.println("  Elapsed:       " + String.format("%.2f", elapsedSeconds) + "s");
        System.out.println("  Throughput:    " + String.format("%.0f", tps) + " TPS");
        System.out.println("  Dropped:       " + bufferManager.getDroppedCount());

        assertTrue(tps >= 100_000,
                "Submit throughput " + String.format("%.0f", tps)
                        + " TPS is below 100,000 TPS minimum");
    }

    // ------------------------------------------------------------------
    // Sink: no-op counter that measures write throughput without I/O
    // ------------------------------------------------------------------

    static class CountingSink implements LogSink {
        private final AtomicLong writeCount = new AtomicLong(0);

        @Override
        public void write(CompletedTrace trace) {
            writeCount.incrementAndGet();
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        long getWriteCount() {
            return writeCount.get();
        }
    }
}
