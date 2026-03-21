package com.tracing.spring.interceptor;

import com.tracing.core.TraceContextManager;
import com.tracing.core.TraceLog;
import com.tracing.core.TraceStatus;
import com.tracing.core.buffer.BufferConfig;
import com.tracing.core.buffer.BufferManager;
import com.tracing.core.id.UlidGenerator;
import com.tracing.spring.test.TestSink;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TraceJmsListenerAspectTest {

    private TraceContextManager contextManager;
    private BufferManager bufferManager;
    private TestSink testSink;
    private TraceJmsListenerAspect aspect;

    @BeforeEach
    void setUp() {
        testSink = new TestSink();
        contextManager = new TraceContextManager(new UlidGenerator(), "test-service", 1000);
        bufferManager = new BufferManager(testSink, new BufferConfig(1, 300, 10000));
        TraceLog.initialize(contextManager, bufferManager);
        aspect = new TraceJmsListenerAspect(contextManager, bufferManager, "X-Trace-Id");
    }

    @AfterEach
    void tearDown() {
        contextManager.clear();
        MDC.clear();
    }

    @Test
    void messageArg_startsAndEndsTrace() throws Throwable {
        ProceedingJoinPoint joinPoint = mockJoinPoint(mockMessage(null, null, null));
        when(joinPoint.proceed()).thenReturn(null);

        aspect.traceJmsListenerMethod(joinPoint);
        bufferManager.shutdown();

        assertThat(testSink.getTraces()).hasSize(1);
        assertThat(testSink.getTraces().get(0).getStatus()).isEqualTo(TraceStatus.SUCCESS);
    }

    @Test
    void propertyExtraction_xTraceId() throws Throwable {
        Message message = mockMessage("parent-jms-123", null, null);
        ProceedingJoinPoint joinPoint = mockJoinPoint(message);
        when(joinPoint.proceed()).thenReturn(null);

        aspect.traceJmsListenerMethod(joinPoint);
        bufferManager.shutdown();

        assertThat(testSink.getTraces().get(0).getParentTraceId()).isEqualTo("parent-jms-123");
    }

    @Test
    void propertyExtraction_traceparent() throws Throwable {
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        Message message = mockMessage(null, traceparent, null);
        ProceedingJoinPoint joinPoint = mockJoinPoint(message);
        when(joinPoint.proceed()).thenReturn(null);

        aspect.traceJmsListenerMethod(joinPoint);
        bufferManager.shutdown();

        assertThat(testSink.getTraces().get(0).getTraceId())
                .isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void noMessageArg_fallbackBehavior() throws Throwable {
        ProceedingJoinPoint joinPoint = mockJoinPoint((Message) null);
        when(joinPoint.proceed()).thenReturn(null);

        aspect.traceJmsListenerMethod(joinPoint);
        bufferManager.shutdown();

        assertThat(testSink.getTraces()).hasSize(1);
        assertThat(testSink.getTraces().get(0).getEntryPoint()).contains("TestHandler.onMessage");
    }

    @Test
    void exception_setsErrorStatusAndRethrows() throws Throwable {
        ProceedingJoinPoint joinPoint = mockJoinPoint(mockMessage(null, null, null));
        RuntimeException ex = new RuntimeException("JMS processing failed");
        when(joinPoint.proceed()).thenThrow(ex);

        assertThatThrownBy(() -> aspect.traceJmsListenerMethod(joinPoint))
                .isEqualTo(ex);
        bufferManager.shutdown();

        assertThat(testSink.getTraces()).hasSize(1);
        assertThat(testSink.getTraces().get(0).getStatus()).isEqualTo(TraceStatus.ERROR);
    }

    @Test
    void destinationResolved_fromMessage() throws Throwable {
        Destination dest = mock(Destination.class);
        when(dest.toString()).thenReturn("queue://test-queue");
        Message message = mockMessage(null, null, dest);
        ProceedingJoinPoint joinPoint = mockJoinPoint(message);
        when(joinPoint.proceed()).thenReturn(null);

        aspect.traceJmsListenerMethod(joinPoint);
        bufferManager.shutdown();

        assertThat(testSink.getTraces().get(0).getEntryPoint()).contains("queue://test-queue");
    }

    @Test
    void mdcCleanup_afterProcessing() throws Throwable {
        ProceedingJoinPoint joinPoint = mockJoinPoint(mockMessage(null, null, null));
        when(joinPoint.proceed()).thenReturn(null);

        aspect.traceJmsListenerMethod(joinPoint);
        bufferManager.shutdown();

        assertThat(MDC.get("traceId")).isNull();
    }

    private ProceedingJoinPoint mockJoinPoint(Message message) {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getDeclaringType()).thenReturn((Class) TestHandler.class);
        when(signature.getName()).thenReturn("onMessage");
        when(joinPoint.getSignature()).thenReturn(signature);
        if (message != null) {
            when(joinPoint.getArgs()).thenReturn(new Object[]{message});
        } else {
            when(joinPoint.getArgs()).thenReturn(new Object[]{"plain-string"});
        }
        return joinPoint;
    }

    private Message mockMessage(String parentTraceId, String traceparent, Destination destination) throws JMSException {
        Message message = mock(Message.class);
        when(message.getStringProperty("X-Trace-Id")).thenReturn(parentTraceId);
        when(message.getStringProperty("traceparent")).thenReturn(traceparent);
        when(message.getJMSDestination()).thenReturn(destination);
        when(message.getJMSMessageID()).thenReturn("MSG-001");
        return message;
    }

    static class TestHandler {
        void onMessage() {}
    }
}
