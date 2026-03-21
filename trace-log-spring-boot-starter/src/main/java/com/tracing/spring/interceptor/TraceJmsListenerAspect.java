package com.tracing.spring.interceptor;

import com.tracing.core.TraceContextManager;
import com.tracing.core.TraceLog;
import com.tracing.core.TraceStatus;
import com.tracing.core.buffer.BufferManager;
import com.tracing.core.propagation.W3CTraceparentParser;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;

@Aspect
public class TraceJmsListenerAspect {

    private static final String MDC_TRACE_ID = "traceId";

    private final TraceContextManager contextManager;
    private final BufferManager bufferManager;
    private final String propagationProperty;

    public TraceJmsListenerAspect(TraceContextManager contextManager,
                                  BufferManager bufferManager,
                                  String propagationProperty) {
        this.contextManager = contextManager;
        this.bufferManager = bufferManager;
        this.propagationProperty = propagationProperty;
    }

    @Around("@annotation(org.springframework.jms.annotation.JmsListener)")
    public Object traceJmsListenerMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        Message jmsMessage = findJmsMessage(joinPoint.getArgs());

        String destination = resolveDestination(jmsMessage, joinPoint);
        String entryPoint = "JMS " + destination
                + " (" + joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName() + ")";

        String parentTraceId = extractParentTraceId(jmsMessage);
        String externalTraceId = extractW3CTraceId(jmsMessage);

        var context = contextManager.startTrace(entryPoint, parentTraceId, externalTraceId);
        MDC.put(MDC_TRACE_ID, context.getTraceId());

        TraceLog.event("jms.message.received")
                .data("destination", destination)
                .data("messageId", extractMessageId(jmsMessage))
                .info();

        TraceStatus status = TraceStatus.SUCCESS;
        try {
            Object result = joinPoint.proceed();
            TraceLog.event("jms.message.processed").info();
            return result;
        } catch (Throwable t) {
            status = TraceStatus.ERROR;
            TraceLog.event("jms.message.failed").error(t);
            throw t;
        } finally {
            try {
                contextManager.endTrace(status).ifPresent(bufferManager::submit);
            } finally {
                contextManager.clear();
                MDC.remove(MDC_TRACE_ID);
            }
        }
    }

    private String extractParentTraceId(Message jmsMessage) {
        if (jmsMessage == null) {
            return null;
        }
        try {
            return jmsMessage.getStringProperty(propagationProperty);
        } catch (JMSException e) {
            return null;
        }
    }

    private String extractMessageId(Message jmsMessage) {
        if (jmsMessage == null) {
            return null;
        }
        try {
            return jmsMessage.getJMSMessageID();
        } catch (JMSException e) {
            return null;
        }
    }

    private String resolveDestination(Message jmsMessage, ProceedingJoinPoint joinPoint) {
        if (jmsMessage != null) {
            try {
                var dest = jmsMessage.getJMSDestination();
                if (dest != null) {
                    return dest.toString();
                }
            } catch (JMSException e) {
                // fall through
            }
        }
        return joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();
    }

    private String extractW3CTraceId(Message jmsMessage) {
        if (jmsMessage == null) {
            return null;
        }
        try {
            return W3CTraceparentParser.extractTraceId(
                    jmsMessage.getStringProperty(W3CTraceparentParser.HEADER_NAME));
        } catch (JMSException e) {
            return null;
        }
    }

    private Message findJmsMessage(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Message msg) {
                return msg;
            }
        }
        return null;
    }
}
