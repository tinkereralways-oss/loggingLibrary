package com.tracing.core.sampling;

import com.tracing.core.CompletedTrace;

public final class AlwaysSampleStrategy implements SamplingStrategy {

    @Override
    public boolean shouldSample(CompletedTrace trace) {
        return true;
    }
}
