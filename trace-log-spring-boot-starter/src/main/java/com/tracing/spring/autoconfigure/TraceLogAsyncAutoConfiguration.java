package com.tracing.spring.autoconfigure;

import com.tracing.core.TraceContextManager;
import com.tracing.core.propagation.TraceTaskDecorator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.Async;

@Configuration
@ConditionalOnClass(Async.class)
public class TraceLogAsyncAutoConfiguration {

    @Bean
    public TraceTaskDecorator traceTaskDecorator(TraceContextManager contextManager) {
        return new TraceTaskDecorator(contextManager);
    }

    @Bean
    public TaskDecorator springTraceTaskDecorator(TraceTaskDecorator traceTaskDecorator) {
        return traceTaskDecorator::decorate;
    }
}
