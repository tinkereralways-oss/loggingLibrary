package com.tracing.core.sampling;

import com.tracing.core.CompletedTrace;

public interface SamplingStrategy {

    boolean shouldSample(CompletedTrace trace);
}
