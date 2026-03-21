package com.tracing.core;

import com.tracing.core.id.TraceIdGenerator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

public final class TraceContextManager {

    private static final ThreadLocal<Deque<TraceContext>> CONTEXT_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private final TraceIdGenerator idGenerator;
    private final String serviceName;
    private final int maxEventsPerTrace;

    public TraceContextManager(TraceIdGenerator idGenerator, String serviceName, int maxEventsPerTrace) {
        this.idGenerator = idGenerator;
        this.serviceName = serviceName;
        this.maxEventsPerTrace = maxEventsPerTrace;
    }

    public TraceContext startTrace(String entryPoint) {
        return startTrace(entryPoint, null);
    }

    public TraceContext startTrace(String entryPoint, String parentTraceId) {
        return startTrace(entryPoint, parentTraceId, null);
    }

    public TraceContext startTrace(String entryPoint, String parentTraceId, String externalTraceId) {
        String traceId = (externalTraceId != null && !externalTraceId.isBlank())
                ? externalTraceId : idGenerator.generate();
        TraceContext context = new TraceContext(traceId, serviceName, entryPoint, parentTraceId, maxEventsPerTrace);
        CONTEXT_STACK.get().push(context);
        return context;
    }

    public Optional<TraceContext> currentContext() {
        Deque<TraceContext> stack = CONTEXT_STACK.get();
        return stack.isEmpty() ? Optional.empty() : Optional.of(stack.peek());
    }

    public Optional<CompletedTrace> endTrace(TraceStatus status) {
        Deque<TraceContext> stack = CONTEXT_STACK.get();
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        TraceContext context = stack.pop();
        if (stack.isEmpty()) {
            CONTEXT_STACK.remove();
        }
        return Optional.of(context.complete(status));
    }

    public void installContext(TraceContext context) {
        CONTEXT_STACK.get().push(context);
    }

    public TraceContext detachContext() {
        Deque<TraceContext> stack = CONTEXT_STACK.get();
        if (stack.isEmpty()) {
            return null;
        }
        TraceContext context = stack.pop();
        if (stack.isEmpty()) {
            CONTEXT_STACK.remove();
        }
        return context;
    }

    public void clear() {
        CONTEXT_STACK.remove();
    }
}
