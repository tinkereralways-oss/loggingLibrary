package com.tracing.spring.interceptor;

import com.tracing.core.TraceContextManager;
import com.tracing.core.TraceLog;
import com.tracing.core.TraceStatus;
import com.tracing.core.buffer.BufferManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;

@Aspect
public class TraceScheduledAspect {

    private static final String MDC_TRACE_ID = "traceId";

    private final TraceContextManager contextManager;
    private final BufferManager bufferManager;

    public TraceScheduledAspect(TraceContextManager contextManager, BufferManager bufferManager) {
        this.contextManager = contextManager;
        this.bufferManager = bufferManager;
    }

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object traceScheduledMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        String entryPoint = "SCHEDULED " + joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();

        var context = contextManager.startTrace(entryPoint);
        MDC.put(MDC_TRACE_ID, context.getTraceId());

        TraceLog.event("scheduled.started").info();

        TraceStatus status = TraceStatus.SUCCESS;
        try {
            Object result = joinPoint.proceed();
            TraceLog.event("scheduled.completed").info();
            return result;
        } catch (Throwable t) {
            status = TraceStatus.ERROR;
            TraceLog.event("scheduled.failed").error(t);
            throw t;
        } finally {
            try {
                contextManager.endTrace(status).ifPresent(bufferManager::submit);
            } finally {
                // Always clean up — prevents ThreadLocal leak on pooled threads
                contextManager.clear();
                MDC.remove(MDC_TRACE_ID);
            }
        }
    }
}
