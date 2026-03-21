package com.tracing.core.propagation;

import com.tracing.core.TraceContext;
import com.tracing.core.TraceContextManager;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

public final class TraceableExecutorService implements ExecutorService {

    private final ExecutorService delegate;
    private final TraceTaskDecorator decorator;
    private final TraceContextManager contextManager;

    public TraceableExecutorService(ExecutorService delegate, TraceTaskDecorator decorator,
                                    TraceContextManager contextManager) {
        this.delegate = delegate;
        this.decorator = decorator;
        this.contextManager = contextManager;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(decorator.decorate(command));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(wrapCallable(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(decorator.decorate(task), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(decorator.decorate(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(tasks.stream().map(this::wrapCallable).toList());
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(tasks.stream().map(this::wrapCallable).toList(), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return delegate.invokeAny(tasks.stream().map(this::wrapCallable).toList());
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(tasks.stream().map(this::wrapCallable).toList(), timeout, unit);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    private <T> Callable<T> wrapCallable(Callable<T> task) {
        TraceContext context = contextManager.currentContext().orElse(null);
        if (context == null) {
            return task;
        }
        return () -> {
            contextManager.installContext(context);
            try {
                return task.call();
            } finally {
                contextManager.detachContext();
            }
        };
    }
}
