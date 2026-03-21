package com.tracing.spring.autoconfigure;

import com.tracing.core.TraceContextManager;
import com.tracing.core.buffer.BufferManager;
import com.tracing.spring.interceptor.TraceScheduledAspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@ConditionalOnClass({Scheduled.class, org.aspectj.lang.ProceedingJoinPoint.class})
public class TraceLogSchedulingAutoConfiguration {

    @Bean
    public TraceScheduledAspect traceScheduledAspect(TraceContextManager contextManager,
                                                     BufferManager bufferManager) {
        return new TraceScheduledAspect(contextManager, bufferManager);
    }
}
