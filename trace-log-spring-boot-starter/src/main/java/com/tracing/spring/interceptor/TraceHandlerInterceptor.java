package com.tracing.spring.interceptor;

import com.tracing.core.TraceContext;
import com.tracing.core.TraceContextManager;
import com.tracing.core.TraceLog;
import com.tracing.core.TraceStatus;
import com.tracing.core.buffer.BufferManager;
import com.tracing.core.propagation.W3CTraceparentParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

public class TraceHandlerInterceptor implements HandlerInterceptor {

    private static final String MDC_TRACE_ID = "traceId";
    private static final String REQUEST_ATTR_TRACE_ACTIVE = "tracelog.active";

    private final TraceContextManager contextManager;
    private final BufferManager bufferManager;
    private final String propagationHeader;

    public TraceHandlerInterceptor(TraceContextManager contextManager,
                                   BufferManager bufferManager,
                                   String propagationHeader) {
        this.contextManager = contextManager;
        this.bufferManager = bufferManager;
        this.propagationHeader = propagationHeader;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String entryPoint = buildEntryPoint(request, handler);
        String parentTraceId = request.getHeader(propagationHeader);

        // If W3C traceparent header is present, use its trace ID
        String externalTraceId = W3CTraceparentParser.extractTraceId(
                request.getHeader(W3CTraceparentParser.HEADER_NAME));

        TraceContext context = contextManager.startTrace(entryPoint, parentTraceId, externalTraceId);
        request.setAttribute(REQUEST_ATTR_TRACE_ACTIVE, Boolean.TRUE);

        MDC.put(MDC_TRACE_ID, context.getTraceId());
        response.setHeader(propagationHeader, context.getTraceId());

        TraceLog.event("request.received")
                .data("method", request.getMethod())
                .data("uri", request.getRequestURI())
                .data("queryString", request.getQueryString())
                .data("remoteAddr", request.getRemoteAddr())
                .info();

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            // Guard: only end trace if preHandle started one
            if (!Boolean.TRUE.equals(request.getAttribute(REQUEST_ATTR_TRACE_ACTIVE))) {
                return;
            }

            TraceStatus status = (ex != null || response.getStatus() >= 500)
                    ? TraceStatus.ERROR : TraceStatus.SUCCESS;

            TraceLog.event("request.completed")
                    .data("statusCode", response.getStatus())
                    .info();

            if (ex != null) {
                TraceLog.event("request.exception")
                        .error(ex);
            }

            contextManager.endTrace(status).ifPresent(bufferManager::submit);
        } finally {
            // Always clean up — prevents ThreadLocal leak on pooled threads
            contextManager.clear();
            MDC.remove(MDC_TRACE_ID);
        }
    }

    private String buildEntryPoint(HttpServletRequest request, Object handler) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        if (handler instanceof HandlerMethod hm) {
            return "REST " + method + " " + uri
                    + " (" + hm.getBeanType().getSimpleName() + "." + hm.getMethod().getName() + ")";
        }
        return "REST " + method + " " + uri;
    }
}
