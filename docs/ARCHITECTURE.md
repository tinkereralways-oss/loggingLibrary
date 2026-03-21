# trace-log Architecture

## Module Structure

```
trace-log/
├── trace-log-core/                  Core library (zero Spring dependencies)
├── trace-log-spring-boot-starter/   Spring Boot auto-configuration
├── trace-log-example/               Demo application
└── trace-log-bom/                   Bill of Materials for version alignment
```

### trace-log-core

Contains all core abstractions with no Spring dependency. Can be used standalone.

**Key classes:**

| Class | Responsibility |
|-------|---------------|
| `TraceLog` | Static facade — the developer API |
| `TraceContextManager` | ThreadLocal-based context lifecycle |
| `TraceContext` | Per-trace mutable state (events, metadata) |
| `CompletedTrace` | Immutable snapshot for serialization |
| `LogEvent` / `LogEventBuilder` | Event model + fluent builder |
| `TimedEvent` | AutoCloseable timed operations |
| `ManualTrace` | AutoCloseable manual trace lifecycle |
| `BufferManager` | Async queue with scheduled drain |
| `LogSink` / `JsonStdoutSink` | Pluggable output abstraction |
| `UlidGenerator` | Time-sortable ULID generation |
| `W3CTraceparentParser` | Extracts trace ID from W3C `traceparent` headers |
| `TraceTaskDecorator` | Async context propagation |

### trace-log-spring-boot-starter

Auto-configuration that wires core components into Spring Boot.

**Auto-configuration classes:**

| Class | Condition | Registers |
|-------|-----------|-----------|
| `TraceLogAutoConfiguration` | `tracelog.enabled=true` | Core beans + imports all below |
| `TraceLogWebMvcAutoConfiguration` | Servlet web app + `HandlerInterceptor` | REST interceptor + MDC filter |
| `TraceLogKafkaAutoConfiguration` | `RecordInterceptor` on classpath | Kafka message interceptor |
| `TraceLogJmsAutoConfiguration` | `@JmsListener` + AspectJ on classpath | JMS/IBM MQ aspect |
| `TraceLogSchedulingAutoConfiguration` | `@Scheduled` + AspectJ on classpath | Scheduled task aspect |
| `TraceLogAsyncAutoConfiguration` | `@Async` on classpath | TaskDecorator for thread pools |

## Trace Lifecycle

```
┌─────────────┐     ┌──────────────────┐     ┌───────────────┐
│  Interceptor │────>│ TraceContextMgr  │────>│  TraceContext  │
│  preHandle() │     │  startTrace()    │     │  (ThreadLocal) │
└─────────────┘     └──────────────────┘     └───────┬───────┘
                                                      │
                    ┌──────────────────┐              │
                    │ Application Code │              │
                    │ TraceLog.event() │──────────────┤ addEvent()
                    │ TraceLog.timed() │              │
                    │ TraceLog.meta()  │              │
                    └──────────────────┘              │
                                                      │
┌─────────────┐     ┌──────────────────┐     ┌───────┴───────┐
│  Interceptor │────>│ TraceContextMgr  │────>│ CompletedTrace │
│  afterComp() │     │  endTrace()      │     │  (immutable)   │
└─────────────┘     └──────────────────┘     └───────┬───────┘
                                                      │
                    ┌──────────────────┐              │ submit()
                    │  BufferManager   │<─────────────┘
                    │  (async queue)   │
                    └───────┬──────────┘
                            │ drain (every 5s)
                    ┌───────┴──────────┐
                    │    LogSink       │
                    │  (JsonStdout)    │──────> stdout JSON
                    └──────────────────┘
```

### Phase 1: Trace Creation

1. Interceptor detects an inflow (HTTP request, Kafka message, etc.)
2. Interceptor checks for a W3C `traceparent` header — if present, extracts the 32-char hex trace ID via `W3CTraceparentParser`
3. `TraceContextManager.startTrace()` uses the OTEL trace ID if available, otherwise generates a new ULID/UUID
4. `TraceContext` is pushed onto the ThreadLocal stack
5. Trace ID is copied to SLF4J MDC and response headers

### Phase 2: Event Accumulation

1. Application code calls `TraceLog.event("name").data("k", v).info()`
2. `LogEventBuilder` captures the caller class/method via `StackWalker` (cached, limited to 50 frames)
3. Immutable `LogEvent` is created and added to the `TraceContext`'s `ConcurrentLinkedQueue`
4. `AtomicInteger` with CAS loop enforces the max events cap

### Phase 3: Trace Completion

1. Interceptor's cleanup hook fires (`afterCompletion`, `finally` block)
2. `TraceContextManager.endTrace()` pops the context from the stack
3. `TraceContext.complete()` creates an immutable `CompletedTrace` snapshot
4. `CompletedTrace` is submitted to `BufferManager`'s `ConcurrentLinkedQueue`
5. ThreadLocal and MDC are cleaned up in a `finally` block

### Phase 4: Flush

1. `BufferManager`'s scheduled executor drains the queue every 5 seconds
2. Up to 256 traces are batched per drain cycle
3. Each trace is serialized to JSON via Jackson and written to the sink
4. Failed writes are retried once before being counted as failures
5. Sink is flushed after each batch

## Thread Safety Model

```
Request Thread                    Flush Thread
─────────────                    ────────────
TraceLog.event()                 BufferManager.drainAndWrite()
  → TraceContext.addEvent()        → pendingTraces.poll()
     CAS loop on AtomicInteger     → sink.write(trace)
     ConcurrentLinkedQueue.add()      synchronized(out) { println }
                                   → sink.flush()
TraceContextManager.endTrace()
  → TraceContext.complete()
     snapshot events + metadata
  → BufferManager.submit()
     pendingTraces.offer()
```

| Component | Mechanism | Why |
|-----------|-----------|-----|
| `TraceContext.events` | `ConcurrentLinkedQueue` | Lock-free event accumulation from async handoffs |
| `TraceContext.metadata` | `ConcurrentHashMap` | Concurrent metadata writes |
| `TraceContext.eventCount` | `AtomicInteger` CAS loop | Exact max enforcement without over-counting |
| `BufferManager.pendingTraces` | `ConcurrentLinkedQueue` | Lock-free submission from request threads |
| `BufferManager.running` | `AtomicBoolean` | Idempotent shutdown |
| `BufferManager.IS_FLUSHING` | `ThreadLocal<Boolean>` | Prevents recursive TraceLog calls from within sink |
| `JsonStdoutSink.write()` | `synchronized(out)` | Prevents interleaved JSON output |
| `TraceLog` fields | `volatile` + `synchronized` init | Safe publication across threads |
| `ManualTrace.closed` | `AtomicBoolean` | Prevents double-close |

## Context Propagation

### Same thread (default)

ThreadLocal stack. No special handling needed.

### @Async methods

`TraceLogAsyncAutoConfiguration` registers a Spring `TaskDecorator` that:
1. Captures the current `TraceContext` on the calling thread
2. Installs it on the executor thread before the task runs
3. Detaches it in a `finally` block after the task completes

### Custom thread pools

Wrap your `ExecutorService`:

```java
new TraceableExecutorService(delegate, decorator, contextManager)
```

This decorates all `execute()`, `submit()`, `invokeAll()`, and `invokeAny()` calls.

### Nested traces

The ThreadLocal holds a `Deque<TraceContext>` (stack). `startManualTrace()` pushes a new context. `close()` pops it and restores the parent. Parent trace ID is not automatically set for nested traces within the same service.

## Backpressure and Failure Modes

| Scenario | Behavior |
|----------|----------|
| Queue full (`maxPendingTraces` exceeded) | Trace dropped, counter incremented, warning to stderr |
| Sink write fails | Retried once. If still fails, trace dropped, counter incremented |
| Exception in application code | Interceptor's `finally` block still ends the trace and cleans ThreadLocal |
| Pod crash (SIGKILL) | Only in-flight traces lost. Completed traces were already queued/flushed |
| Graceful shutdown (SIGTERM) | `BufferManager.shutdown()` drains entire queue before closing sink |
| Max events per trace exceeded | Additional events silently dropped, existing events preserved |

## Origin Capture

`LogEventBuilder` uses Java 17's `StackWalker` to identify the application class/method that called `TraceLog.event()`.

**How it works:**
1. Walk up to 50 stack frames
2. Filter out framework packages: `com.tracing.core.*`, `com.tracing.spring.*`, `org.springframework.*`, `org.apache.*`, `java.*`, `jakarta.*`, `org.aspectj.*`, `io.micrometer.*`, CGLIB proxies (`$$`)
3. Return the first matching frame as `ClassName.methodName`
4. Cache results per class name (bounded to 10,000 entries)

Events emitted from within interceptors (e.g., `request.received`) show `unknown` as the origin since the calling frame is Spring infrastructure.
