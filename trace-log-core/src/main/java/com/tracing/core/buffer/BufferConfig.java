package com.tracing.core.buffer;

public final class BufferConfig {

    private final int orphanScanIntervalSeconds;
    private final int maxTraceDurationSeconds;
    private final int maxPendingTraces;

    public BufferConfig(int orphanScanIntervalSeconds, int maxTraceDurationSeconds, int maxPendingTraces) {
        if (orphanScanIntervalSeconds <= 0) {
            throw new IllegalArgumentException("orphanScanIntervalSeconds must be > 0, got: " + orphanScanIntervalSeconds);
        }
        if (maxTraceDurationSeconds <= 0) {
            throw new IllegalArgumentException("maxTraceDurationSeconds must be > 0, got: " + maxTraceDurationSeconds);
        }
        if (maxPendingTraces <= 0) {
            throw new IllegalArgumentException("maxPendingTraces must be > 0, got: " + maxPendingTraces);
        }
        this.orphanScanIntervalSeconds = orphanScanIntervalSeconds;
        this.maxTraceDurationSeconds = maxTraceDurationSeconds;
        this.maxPendingTraces = maxPendingTraces;
    }

    public static BufferConfig defaults() {
        return new BufferConfig(5, 300, 10000);
    }

    public int getOrphanScanIntervalSeconds() {
        return orphanScanIntervalSeconds;
    }

    public int getMaxTraceDurationSeconds() {
        return maxTraceDurationSeconds;
    }

    public int getMaxPendingTraces() {
        return maxPendingTraces;
    }
}
