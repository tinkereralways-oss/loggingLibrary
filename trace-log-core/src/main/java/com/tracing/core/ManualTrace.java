package com.tracing.core;

import com.tracing.core.buffer.BufferManager;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ManualTrace implements AutoCloseable {

    private final TraceContextManager contextManager;
    private final BufferManager bufferManager;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ManualTrace(TraceContextManager contextManager, BufferManager bufferManager) {
        this.contextManager = contextManager;
        this.bufferManager = bufferManager;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (contextManager == null) {
            return;
        }
        contextManager.endTrace(TraceStatus.SUCCESS)
                .ifPresent(completed -> {
                    if (bufferManager != null) {
                        bufferManager.submit(completed);
                    }
                });
    }
}
