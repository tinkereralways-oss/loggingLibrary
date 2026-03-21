package com.tracing.core.sampling;

import com.tracing.core.CompletedTrace;
import com.tracing.core.TraceStatus;

public final class RateSamplingStrategy implements SamplingStrategy {

    private final double rate;

    public RateSamplingStrategy(double rate) {
        if (rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException("Sampling rate must be between 0.0 and 1.0, got: " + rate);
        }
        this.rate = rate;
    }

    @Override
    public boolean shouldSample(CompletedTrace trace) {
        if (trace.getStatus() == TraceStatus.ERROR) {
            return true;
        }
        if (rate >= 1.0) {
            return true;
        }
        if (rate <= 0.0) {
            return false;
        }
        int hash = trace.getTraceId().hashCode() & 0x7FFFFFFF;
        return hash % 10000 < (int) (rate * 10000);
    }

    public double getRate() {
        return rate;
    }
}
