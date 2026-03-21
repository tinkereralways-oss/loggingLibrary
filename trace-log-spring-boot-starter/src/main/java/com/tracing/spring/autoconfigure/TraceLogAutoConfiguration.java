package com.tracing.spring.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracing.core.TraceContextManager;
import com.tracing.core.TraceLog;
import com.tracing.core.buffer.BufferConfig;
import com.tracing.core.buffer.BufferManager;
import com.tracing.core.id.TraceIdGenerator;
import com.tracing.core.id.UlidGenerator;
import com.tracing.core.id.UuidGenerator;
import com.tracing.core.sink.JsonStdoutSink;
import com.tracing.core.sink.LogSink;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnProperty(name = "tracelog.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TraceLogProperties.class)
@Import({
        TraceLogWebMvcAutoConfiguration.class,
        TraceLogKafkaAutoConfiguration.class,
        TraceLogJmsAutoConfiguration.class,
        TraceLogSchedulingAutoConfiguration.class,
        TraceLogAsyncAutoConfiguration.class
})
public class TraceLogAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceIdGenerator traceIdGenerator(TraceLogProperties properties) {
        return switch (properties.getTraceId().getFormat()) {
            case ULID -> new UlidGenerator();
            case UUID -> new UuidGenerator();
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public LogSink logSink(ObjectMapper objectMapper, TraceLogProperties properties) {
        return new JsonStdoutSink(objectMapper, properties.getSink().isPrettyPrint(), System.out);
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceContextManager traceContextManager(
            TraceIdGenerator traceIdGenerator,
            TraceLogProperties properties,
            @Value("${spring.application.name:unknown-service}") String appName) {
        String serviceName = properties.getServiceName() != null
                ? properties.getServiceName() : appName;
        return new TraceContextManager(traceIdGenerator, serviceName,
                properties.getBuffer().getMaxEventsPerTrace());
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public BufferManager bufferManager(LogSink logSink, TraceLogProperties properties) {
        BufferConfig config = new BufferConfig(
                properties.getBuffer().getOrphanScanIntervalSeconds(),
                properties.getBuffer().getMaxTraceDurationSeconds(),
                properties.getBuffer().getMaxPendingTraces()
        );
        return new BufferManager(logSink, config);
    }

    @Bean
    public TraceLogInitializer traceLogInitializer(TraceContextManager contextManager,
                                                   BufferManager bufferManager) {
        return new TraceLogInitializer(contextManager, bufferManager);
    }

    static class TraceLogInitializer {
        TraceLogInitializer(TraceContextManager contextManager, BufferManager bufferManager) {
            TraceLog.initialize(contextManager, bufferManager);
        }
    }
}
