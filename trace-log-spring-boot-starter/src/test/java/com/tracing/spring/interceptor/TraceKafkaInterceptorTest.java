package com.tracing.spring.interceptor;

import com.tracing.core.TraceContextManager;
import com.tracing.core.TraceLog;
import com.tracing.core.TraceStatus;
import com.tracing.core.buffer.BufferConfig;
import com.tracing.core.buffer.BufferManager;
import com.tracing.core.id.UlidGenerator;
import com.tracing.core.propagation.W3CTraceparentParser;
import com.tracing.spring.test.TestSink;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TraceKafkaInterceptorTest {

    private TraceContextManager contextManager;
    private BufferManager bufferManager;
    private TestSink testSink;
    private TraceKafkaInterceptor<String, String> interceptor;

    @BeforeEach
    void setUp() {
        testSink = new TestSink();
        contextManager = new TraceContextManager(new UlidGenerator(), "test-service", 1000);
        bufferManager = new BufferManager(testSink, new BufferConfig(1, 300, 10000));
        TraceLog.initialize(contextManager, bufferManager);
        interceptor = new TraceKafkaInterceptor<>(contextManager, bufferManager, "X-Trace-Id");
    }

    @AfterEach
    void tearDown() {
        contextManager.clear();
        bufferManager.shutdown();

        MDC.clear();
    }

    @Test
    void intercept_startsTrace() {
        ConsumerRecord<String, String> record = createRecord("test-topic", "key1", "value1");
        interceptor.intercept(record, null);

        assertThat(TraceLog.currentTraceId()).isPresent();
        assertThat(MDC.get("traceId")).isNotNull();

        interceptor.afterRecord(record, null);
    }

    @Test
    void afterRecord_endsAndSubmitsTrace() {
        ConsumerRecord<String, String> record = createRecord("test-topic", "key1", "value1");
        interceptor.intercept(record, null);
        interceptor.afterRecord(record, null);

        bufferManager.shutdown();
        assertThat(testSink.getTraces()).hasSize(1);
        assertThat(testSink.getTraces().get(0).getStatus()).isEqualTo(TraceStatus.SUCCESS);
        assertThat(testSink.getTraces().get(0).getEntryPoint()).isEqualTo("KAFKA test-topic");
    }

    @Test
    void headerExtraction_xTraceId() {
        RecordHeaders headers = new RecordHeaders();
        headers.add("X-Trace-Id", "parent-from-kafka".getBytes(StandardCharsets.UTF_8));
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "test-topic", 0, 0L, ConsumerRecord.NO_TIMESTAMP, TimestampType.NO_TIMESTAMP_TYPE,
                0, 0, "key", "value", headers, java.util.Optional.empty());

        interceptor.intercept(record, null);
        interceptor.afterRecord(record, null);

        bufferManager.shutdown();
        assertThat(testSink.getTraces().get(0).getParentTraceId()).isEqualTo("parent-from-kafka");
    }

    @Test
    void headerExtraction_traceparent() {
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        RecordHeaders headers = new RecordHeaders();
        headers.add(W3CTraceparentParser.HEADER_NAME, traceparent.getBytes(StandardCharsets.UTF_8));
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "test-topic", 0, 0L, ConsumerRecord.NO_TIMESTAMP, TimestampType.NO_TIMESTAMP_TYPE,
                0, 0, "key", "value", headers, java.util.Optional.empty());

        interceptor.intercept(record, null);
        interceptor.afterRecord(record, null);

        bufferManager.shutdown();
        assertThat(testSink.getTraces().get(0).getTraceId())
                .isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void noHeaders_generatesNewId() {
        ConsumerRecord<String, String> record = createRecord("test-topic", "key", "value");
        interceptor.intercept(record, null);
        interceptor.afterRecord(record, null);

        bufferManager.shutdown();
        assertThat(testSink.getTraces().get(0).getTraceId()).isNotNull();
        assertThat(testSink.getTraces().get(0).getParentTraceId()).isNull();
    }

    @Test
    void mdcCleanup_afterRecord() {
        ConsumerRecord<String, String> record = createRecord("test-topic", "key", "value");
        interceptor.intercept(record, null);
        assertThat(MDC.get("traceId")).isNotNull();

        interceptor.afterRecord(record, null);
        assertThat(MDC.get("traceId")).isNull();
    }

    private ConsumerRecord<String, String> createRecord(String topic, String key, String value) {
        return new ConsumerRecord<>(topic, 0, 0L, key, value);
    }
}
