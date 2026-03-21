package com.tracing.core.propagation;

import com.tracing.core.TraceContext;
import com.tracing.core.TraceContextManager;

public final class TraceTaskDecorator {

    private final TraceContextManager contextManager;

    public TraceTaskDecorator(TraceContextManager contextManager) {
        this.contextManager = contextManager;
    }

    public Runnable decorate(Runnable task) {
        TraceContext context = contextManager.currentContext().orElse(null);
        if (context == null) {
            return task;
        }
        return () -> {
            contextManager.installContext(context);
            try {
                task.run();
            } finally {
                contextManager.detachContext();
            }
        };
    }
}
