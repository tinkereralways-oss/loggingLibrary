package com.tracing.core.sink;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tracing.core.CompletedTrace;

import java.io.PrintStream;

public final class JsonStdoutSink implements LogSink {

    private final ObjectMapper objectMapper;
    private final PrintStream out;

    public JsonStdoutSink() {
        this(createDefaultMapper(), false, System.out);
    }

    public JsonStdoutSink(ObjectMapper objectMapper, boolean prettyPrint, PrintStream out) {
        this.objectMapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        if (prettyPrint) {
            this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        }
        this.out = out;
    }

    @Override
    public void write(CompletedTrace trace) {
        try {
            // ObjectMapper is thread-safe; serialize outside the lock
            String json = objectMapper.writeValueAsString(trace);
            // Only synchronize the actual I/O to prevent interleaved output
            synchronized (out) {
                out.println(json);
            }
        } catch (Exception e) {
            System.err.println("[trace-log] Failed to serialize trace: " + e.getMessage());
        }
    }

    @Override
    public void flush() {
        synchronized (out) {
            out.flush();
        }
    }

    @Override
    public void close() {
        flush();
    }

    private static ObjectMapper createDefaultMapper() {
        return new ObjectMapper();
    }
}
