package com.tracing.spring.autoconfigure;

import com.tracing.core.TraceContextManager;
import com.tracing.core.buffer.BufferManager;
import com.tracing.spring.interceptor.TraceJmsListenerAspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.JmsListener;

@Configuration
@ConditionalOnClass({JmsListener.class, org.aspectj.lang.ProceedingJoinPoint.class})
public class TraceLogJmsAutoConfiguration {

    @Bean
    public TraceJmsListenerAspect traceJmsListenerAspect(
            TraceContextManager contextManager,
            BufferManager bufferManager,
            TraceLogProperties properties) {
        return new TraceJmsListenerAspect(contextManager, bufferManager,
                properties.getTraceId().getPropagationHeader());
    }
}
