package com.tracing.spring.autoconfigure;

import com.tracing.core.TraceContextManager;
import com.tracing.core.buffer.BufferManager;
import com.tracing.spring.filter.TraceMdcFilter;
import com.tracing.spring.interceptor.TraceHandlerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(HandlerInterceptor.class)
public class TraceLogWebMvcAutoConfiguration implements WebMvcConfigurer {

    private final TraceHandlerInterceptor traceHandlerInterceptor;

    public TraceLogWebMvcAutoConfiguration(TraceHandlerInterceptor traceHandlerInterceptor) {
        this.traceHandlerInterceptor = traceHandlerInterceptor;
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceHandlerInterceptor traceHandlerInterceptor(
            TraceContextManager contextManager,
            BufferManager bufferManager,
            TraceLogProperties properties) {
        return new TraceHandlerInterceptor(contextManager, bufferManager,
                properties.getTraceId().getPropagationHeader());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(traceHandlerInterceptor);
    }

    @Bean
    public FilterRegistrationBean<TraceMdcFilter> traceMdcFilter() {
        FilterRegistrationBean<TraceMdcFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TraceMdcFilter());
        registration.setOrder(Integer.MIN_VALUE + 1);
        return registration;
    }
}
