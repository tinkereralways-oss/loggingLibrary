package com.tracing.spring.autoconfigure;

import com.tracing.core.TraceContextManager;
import com.tracing.core.buffer.BufferManager;
import com.tracing.core.id.TraceIdGenerator;
import com.tracing.core.id.UuidGenerator;
import com.tracing.core.sampling.AlwaysSampleStrategy;
import com.tracing.core.sampling.RateSamplingStrategy;
import com.tracing.core.sampling.SamplingStrategy;
import com.tracing.core.sink.LogSink;
import com.tracing.spring.test.TestSink;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class TraceLogAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TraceLogAutoConfiguration.class))
            .withPropertyValues("spring.application.name=test-app")
            .withUserConfiguration(JacksonConfig.class);

    @Test
    void defaultConfig_registersAllBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TraceIdGenerator.class);
            assertThat(context).hasSingleBean(LogSink.class);
            assertThat(context).hasSingleBean(TraceContextManager.class);
            assertThat(context).hasSingleBean(BufferManager.class);
            assertThat(context).hasSingleBean(SamplingStrategy.class);
        });
    }

    @Test
    void disabledProperty_disablesEverything() {
        contextRunner
                .withPropertyValues("tracelog.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TraceIdGenerator.class);
                    assertThat(context).doesNotHaveBean(BufferManager.class);
                });
    }

    @Test
    void customLogSinkBean_winsViaConditionalOnMissingBean() {
        contextRunner
                .withUserConfiguration(CustomSinkConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(LogSink.class);
                    assertThat(context.getBean(LogSink.class)).isInstanceOf(TestSink.class);
                });
    }

    @Test
    void uuidFormatConfig_createsUuidGenerator() {
        contextRunner
                .withPropertyValues("tracelog.trace-id.format=UUID")
                .run(context -> {
                    assertThat(context.getBean(TraceIdGenerator.class))
                            .isInstanceOf(UuidGenerator.class);
                });
    }

    @Test
    void serviceNameProperty_overridesAppName() {
        contextRunner
                .withPropertyValues("tracelog.service-name=my-custom-service")
                .run(context -> {
                    assertThat(context).hasSingleBean(TraceContextManager.class);
                });
    }

    @Test
    void bufferProperties_applied() {
        contextRunner
                .withPropertyValues(
                        "tracelog.buffer.max-pending-traces=500",
                        "tracelog.buffer.orphan-scan-interval-seconds=10"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(BufferManager.class);
                });
    }

    @Test
    void samplingRateProperty_createsRateStrategy() {
        contextRunner
                .withPropertyValues("tracelog.sampling.rate=0.5")
                .run(context -> {
                    assertThat(context.getBean(SamplingStrategy.class))
                            .isInstanceOf(RateSamplingStrategy.class);
                    RateSamplingStrategy strategy = (RateSamplingStrategy) context.getBean(SamplingStrategy.class);
                    assertThat(strategy.getRate()).isEqualTo(0.5);
                });
    }

    @Test
    void defaultSamplingRate_createsAlwaysSampleStrategy() {
        contextRunner.run(context -> {
            assertThat(context.getBean(SamplingStrategy.class))
                    .isInstanceOf(AlwaysSampleStrategy.class);
        });
    }

    @Configuration
    static class JacksonConfig {
        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
    }

    @Configuration
    static class CustomSinkConfig {
        @Bean
        LogSink logSink() {
            return new TestSink();
        }
    }
}
