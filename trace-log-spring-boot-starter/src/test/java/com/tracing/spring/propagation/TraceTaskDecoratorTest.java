package com.tracing.spring.propagation;

import com.tracing.core.TraceContext;
import com.tracing.core.TraceContextManager;
import com.tracing.core.TraceLog;
import com.tracing.core.TraceStatus;
import com.tracing.core.buffer.BufferConfig;
import com.tracing.core.buffer.BufferManager;
import com.tracing.core.id.UlidGenerator;
import com.tracing.core.propagation.TraceTaskDecorator;
import com.tracing.spring.test.TestSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceTaskDecoratorTest {

    private TraceContextManager contextManager;
    private BufferManager bufferManager;
    private TestSink testSink;
    private TraceTaskDecorator decorator;

    @BeforeEach
    void setUp() {
        testSink = new TestSink();
        contextManager = new TraceContextManager(new UlidGenerator(), "test-service", 1000);
        bufferManager = new BufferManager(testSink, new BufferConfig(1, 300, 10000));
        TraceLog.initialize(contextManager, bufferManager);
        decorator = new TraceTaskDecorator(contextManager);
    }

    @AfterEach
    void tearDown() {
        contextManager.clear();
        bufferManager.shutdown();
    }

    @Test
    void decoratedRunnable_seesParentContextOnChildThread() throws Exception {
        TraceContext parentContext = contextManager.startTrace("parent-trace");
        String parentTraceId = parentContext.getTraceId();

        AtomicReference<String> childTraceId = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable decorated = decorator.decorate(() -> {
            childTraceId.set(TraceLog.currentTraceId().orElse(null));
            latch.countDown();
        });

        Thread thread = new Thread(decorated);
        thread.start();
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(childTraceId.get()).isEqualTo(parentTraceId);

        contextManager.endTrace(TraceStatus.SUCCESS);
    }

    @Test
    void undecoratedRunnable_hasNoContext() throws Exception {
        contextManager.startTrace("parent-trace");

        AtomicReference<String> childTraceId = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable undecorated = () -> {
            childTraceId.set(TraceLog.currentTraceId().orElse(null));
            latch.countDown();
        };

        Thread thread = new Thread(undecorated);
        thread.start();
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(childTraceId.get()).isNull();

        contextManager.endTrace(TraceStatus.SUCCESS);
    }

    @Test
    void contextCleanedUp_afterDecoratedRun() throws Exception {
        contextManager.startTrace("parent-trace");

        AtomicReference<Boolean> hasContextAfterRun = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable decorated = decorator.decorate(() -> {
            // context is available during run
        });

        Runnable wrapper = () -> {
            decorated.run();
            hasContextAfterRun.set(TraceLog.currentTraceId().isPresent());
            latch.countDown();
        };

        Thread thread = new Thread(wrapper);
        thread.start();
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(hasContextAfterRun.get()).isFalse();

        contextManager.endTrace(TraceStatus.SUCCESS);
    }

    @Test
    void noActiveTrace_returnsOriginalRunnable() {
        Runnable original = () -> {};
        Runnable result = decorator.decorate(original);
        assertThat(result).isSameAs(original);
    }
}
