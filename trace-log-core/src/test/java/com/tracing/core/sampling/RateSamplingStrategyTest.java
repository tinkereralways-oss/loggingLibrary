package com.tracing.core.sampling;

import com.tracing.core.CompletedTrace;
import com.tracing.core.TraceContextManager;
import com.tracing.core.TraceStatus;
import com.tracing.core.id.UlidGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateSamplingStrategyTest {

    private final TraceContextManager contextManager =
            new TraceContextManager(new UlidGenerator(), "test-service", 100);

    @Test
    void fullRate_alwaysSamples() {
        RateSamplingStrategy strategy = new RateSamplingStrategy(1.0);
        for (int i = 0; i < 100; i++) {
            assertTrue(strategy.shouldSample(createTrace(TraceStatus.SUCCESS)));
        }
    }

    @Test
    void zeroRate_onlySamplesErrors() {
        RateSamplingStrategy strategy = new RateSamplingStrategy(0.0);
        assertFalse(strategy.shouldSample(createTrace(TraceStatus.SUCCESS)));
        assertFalse(strategy.shouldSample(createTrace(TraceStatus.TIMEOUT)));
        assertTrue(strategy.shouldSample(createTrace(TraceStatus.ERROR)));
    }

    @Test
    void errorTraces_alwaysSampledRegardlessOfRate() {
        RateSamplingStrategy strategy = new RateSamplingStrategy(0.0);
        for (int i = 0; i < 50; i++) {
            assertTrue(strategy.shouldSample(createTrace(TraceStatus.ERROR)));
        }
    }

    @Test
    void partialRate_samplesApproximatePercentage() {
        RateSamplingStrategy strategy = new RateSamplingStrategy(0.5);
        int sampled = 0;
        int total = 10000;
        for (int i = 0; i < total; i++) {
            if (strategy.shouldSample(createTrace(TraceStatus.SUCCESS))) {
                sampled++;
            }
        }
        double ratio = (double) sampled / total;
        assertTrue(ratio > 0.3 && ratio < 0.7,
                "Expected ~50% sampling, got " + (ratio * 100) + "%");
    }

    @Test
    void deterministicForSameTraceId() {
        CompletedTrace trace = createTrace(TraceStatus.SUCCESS);
        RateSamplingStrategy strategy = new RateSamplingStrategy(0.5);
        boolean first = strategy.shouldSample(trace);
        for (int i = 0; i < 10; i++) {
            assertEquals(first, strategy.shouldSample(trace));
        }
    }

    @Test
    void rejectsRateAboveOne() {
        assertThrows(IllegalArgumentException.class, () -> new RateSamplingStrategy(1.1));
    }

    @Test
    void rejectsNegativeRate() {
        assertThrows(IllegalArgumentException.class, () -> new RateSamplingStrategy(-0.1));
    }

    @Test
    void getRate_returnsConfiguredRate() {
        assertEquals(0.42, new RateSamplingStrategy(0.42).getRate());
    }

    private CompletedTrace createTrace(TraceStatus status) {
        contextManager.startTrace("test-entry");
        return contextManager.endTrace(status).orElseThrow();
    }
}
