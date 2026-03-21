package com.tracing.spring.interceptor;

import com.tracing.core.TraceContextManager;
import com.tracing.core.TraceLog;
import com.tracing.core.TraceStatus;
import com.tracing.core.buffer.BufferManager;
import com.tracing.core.propagation.W3CTraceparentParser;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;

public class TraceKafkaInterceptor<K, V> implements RecordInterceptor<K, V> {

    private static final String MDC_TRACE_ID = "traceId";

    private final TraceContextManager contextManager;
    private final BufferManager bufferManager;
    private final String propagationHeader;

    public TraceKafkaInterceptor(TraceContextManager contextManager,
                                 BufferManager bufferManager,
                                 String propagationHeader) {
        this.contextManager = contextManager;
        this.bufferManager = bufferManager;
        this.propagationHeader = propagationHeader;
    }

    @Override
    public ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        String entryPoint = "KAFKA " + record.topic();
        String parentTraceId = extractParentTraceId(record);
        String externalTraceId = extractW3CTraceId(record);

        var context = contextManager.startTrace(entryPoint, parentTraceId, externalTraceId);
        MDC.put(MDC_TRACE_ID, context.getTraceId());

        TraceLog.event("kafka.message.received")
                .data("topic", record.topic())
                .data("partition", record.partition())
                .data("offset", record.offset())
                .data("key", record.key() != null ? record.key().toString() : null)
                .info();

        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        try {
            TraceLog.event("kafka.message.processed").info();

            contextManager.endTrace(TraceStatus.SUCCESS).ifPresent(bufferManager::submit);
        } finally {
            // Always clean up — prevents ThreadLocal leak on pooled threads
            contextManager.clear();
            MDC.remove(MDC_TRACE_ID);
        }
    }

    private String extractParentTraceId(ConsumerRecord<K, V> record) {
        var header = record.headers().lastHeader(propagationHeader);
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return null;
    }

    private String extractW3CTraceId(ConsumerRecord<K, V> record) {
        var header = record.headers().lastHeader(W3CTraceparentParser.HEADER_NAME);
        if (header != null && header.value() != null) {
            return W3CTraceparentParser.extractTraceId(
                    new String(header.value(), StandardCharsets.UTF_8));
        }
        return null;
    }
}
