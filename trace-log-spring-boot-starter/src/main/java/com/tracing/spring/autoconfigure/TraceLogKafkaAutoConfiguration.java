package com.tracing.spring.autoconfigure;

import com.tracing.core.TraceContextManager;
import com.tracing.core.buffer.BufferManager;
import com.tracing.spring.interceptor.TraceKafkaInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.RecordInterceptor;

@Configuration
@ConditionalOnClass(RecordInterceptor.class)
public class TraceLogKafkaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceKafkaInterceptor<?, ?> traceKafkaInterceptor(
            TraceContextManager contextManager,
            BufferManager bufferManager,
            TraceLogProperties properties) {
        return new TraceKafkaInterceptor<>(contextManager, bufferManager,
                properties.getTraceId().getPropagationHeader());
    }
}
