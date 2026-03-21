package com.tracing.spring.test;

import com.tracing.core.sink.LogSink;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestTraceLogConfiguration {

    @Bean
    public LogSink logSink() {
        return new TestSink();
    }
}
