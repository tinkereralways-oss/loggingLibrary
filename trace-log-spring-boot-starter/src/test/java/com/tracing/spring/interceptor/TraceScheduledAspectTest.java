package com.tracing.spring.interceptor;

import com.tracing.core.TraceContextManager;
import com.tracing.core.TraceLog;
import com.tracing.core.TraceStatus;
import com.tracing.core.buffer.BufferConfig;
import com.tracing.core.buffer.BufferManager;
import com.tracing.core.id.UlidGenerator;
import com.tracing.spring.test.TestSink;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TraceScheduledAspectTest {

    private TraceContextManager contextManager;
    private BufferManager bufferManager;
    private TestSink testSink;
    private TraceScheduledAspect aspect;

    @BeforeEach
    void setUp() {
        testSink = new TestSink();
        contextManager = new TraceContextManager(new UlidGenerator(), "test-service", 1000);
        bufferManager = new BufferManager(testSink, new BufferConfig(1, 300, 10000));
        TraceLog.initialize(contextManager, bufferManager);
        aspect = new TraceScheduledAspect(contextManager, bufferManager);
    }

    @AfterEach
    void tearDown() {
        contextManager.clear();
        MDC.clear();
    }

    @Test
    void createsTrace_withScheduledEntryPoint() throws Throwable {
        ProceedingJoinPoint joinPoint = mockJoinPoint();
        when(joinPoint.proceed()).thenReturn(null);

        aspect.traceScheduledMethod(joinPoint);
        bufferManager.shutdown();

        assertThat(testSink.getTraces()).hasSize(1);
        assertThat(testSink.getTraces().get(0).getEntryPoint())
                .isEqualTo("SCHEDULED MyJob.runCleanup");
    }

    @Test
    void success_setsSuccessStatus() throws Throwable {
        ProceedingJoinPoint joinPoint = mockJoinPoint();
        when(joinPoint.proceed()).thenReturn(null);

        aspect.traceScheduledMethod(joinPoint);
        bufferManager.shutdown();

        assertThat(testSink.getTraces().get(0).getStatus()).isEqualTo(TraceStatus.SUCCESS);
    }

    @Test
    void exception_setsErrorStatusAndRethrows() throws Throwable {
        ProceedingJoinPoint joinPoint = mockJoinPoint();
        RuntimeException ex = new RuntimeException("scheduled task failed");
        when(joinPoint.proceed()).thenThrow(ex);

        assertThatThrownBy(() -> aspect.traceScheduledMethod(joinPoint))
                .isEqualTo(ex);
        bufferManager.shutdown();

        assertThat(testSink.getTraces()).hasSize(1);
        assertThat(testSink.getTraces().get(0).getStatus()).isEqualTo(TraceStatus.ERROR);
    }

    @Test
    void entryPointIncludesClassAndMethod() throws Throwable {
        ProceedingJoinPoint joinPoint = mockJoinPoint();
        when(joinPoint.proceed()).thenReturn(null);

        aspect.traceScheduledMethod(joinPoint);
        bufferManager.shutdown();

        assertThat(testSink.getTraces().get(0).getEntryPoint())
                .startsWith("SCHEDULED")
                .contains("MyJob")
                .contains("runCleanup");
    }

    @Test
    void mdcCleanup_afterExecution() throws Throwable {
        ProceedingJoinPoint joinPoint = mockJoinPoint();
        when(joinPoint.proceed()).thenReturn(null);

        aspect.traceScheduledMethod(joinPoint);
        bufferManager.shutdown();

        assertThat(MDC.get("traceId")).isNull();
    }

    private ProceedingJoinPoint mockJoinPoint() {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getDeclaringType()).thenReturn((Class) MyJob.class);
        when(signature.getName()).thenReturn("runCleanup");
        when(joinPoint.getSignature()).thenReturn(signature);
        return joinPoint;
    }

    static class MyJob {
        void runCleanup() {}
    }
}
