package com.tracing.core.buffer;

import com.tracing.core.CompletedTrace;
import com.tracing.core.sink.LogSink;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class BufferManager {

    private static final ThreadLocal<Boolean> IS_FLUSHING = ThreadLocal.withInitial(() -> false);
    private static final int DRAIN_BATCH_SIZE = 256;
    private static final int MAX_WRITE_RETRIES = 2;

    private final LogSink sink;
    private final ConcurrentLinkedQueue<CompletedTrace> pendingTraces = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicLong writtenCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);
    private final AtomicLong droppedCount = new AtomicLong(0);
    private final int maxPendingTraces;

    public BufferManager(LogSink sink, BufferConfig config) {
        this.sink = sink;
        this.maxPendingTraces = config.getMaxPendingTraces();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trace-log-flush");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(
                this::drainAndWrite,
                config.getOrphanScanIntervalSeconds(),
                config.getOrphanScanIntervalSeconds(),
                TimeUnit.SECONDS
        );
    }

    public void submit(CompletedTrace trace) {
        if (trace == null) {
            return;
        }
        // Allow submissions during shutdown so final traces aren't lost
        if (!running.get() && !shuttingDown.get()) {
            return;
        }
        if (IS_FLUSHING.get()) {
            return;
        }
        if (pendingTraces.size() >= maxPendingTraces) {
            droppedCount.incrementAndGet();
            System.err.println("[trace-log] Backpressure: dropping trace " + trace.getTraceId()
                    + " (queue full at " + maxPendingTraces + ")");
            return;
        }
        pendingTraces.offer(trace);
    }

    public boolean isFlushing() {
        return IS_FLUSHING.get();
    }

    private void drainAndWrite() {
        IS_FLUSHING.set(true);
        try {
            List<CompletedTrace> batch = new ArrayList<>(DRAIN_BATCH_SIZE);
            CompletedTrace trace;
            while (batch.size() < DRAIN_BATCH_SIZE && (trace = pendingTraces.poll()) != null) {
                batch.add(trace);
            }
            for (CompletedTrace t : batch) {
                writeWithRetry(t);
            }
            if (!batch.isEmpty()) {
                try {
                    sink.flush();
                } catch (Exception e) {
                    System.err.println("[trace-log] Flush failed: " + e.getMessage());
                }
            }
        } finally {
            IS_FLUSHING.set(false);
        }
    }

    private void writeWithRetry(CompletedTrace trace) {
        for (int attempt = 1; attempt <= MAX_WRITE_RETRIES; attempt++) {
            try {
                sink.write(trace);
                writtenCount.incrementAndGet();
                return;
            } catch (Exception e) {
                if (attempt == MAX_WRITE_RETRIES) {
                    failedCount.incrementAndGet();
                    System.err.println("[trace-log] Failed to write trace " + trace.getTraceId()
                            + " after " + MAX_WRITE_RETRIES + " attempts: " + e.getMessage());
                }
            }
        }
    }

    private void drainAll() {
        while (!pendingTraces.isEmpty()) {
            drainAndWrite();
        }
    }

    public long getWrittenCount() {
        return writtenCount.get();
    }

    public long getFailedCount() {
        return failedCount.get();
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }

    public int getPendingCount() {
        return pendingTraces.size();
    }

    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        shuttingDown.set(true);
        try {
            // Stop the scheduler from running new drain cycles
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            // Final drain of everything remaining in the queue
            drainAll();
        } finally {
            shuttingDown.set(false);
        }
        try {
            sink.flush();
            sink.close();
        } catch (Exception e) {
            System.err.println("[trace-log] Error during sink shutdown: " + e.getMessage());
        }
    }
}
