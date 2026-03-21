# trace-log API Reference

## Core API — `TraceLog`

The primary developer-facing class. All methods are static. Works from any class on the call stack without passing objects.

### `TraceLog.event(String eventName)`

Creates a new log event builder. Returns `LogEventBuilder`.

```java
TraceLog.event("order.created")
    .data("orderId", "ORD-123")
    .metric("total", 99.99)
    .info();
```

### `TraceLog.timedEvent(String eventName)`

Creates an auto-timed event that records its duration on close. Returns `TimedEvent` (implements `AutoCloseable`).

```java
try (var timer = TraceLog.timedEvent("db.query")) {
    result = repo.findAll();
    timer.data("count", result.size());
}
// Duration automatically recorded
```

### `TraceLog.metadata(String key, String value)`

Attaches key-value metadata to the current trace (not to an individual event). Metadata appears at the trace level in the JSON output.

```java
TraceLog.metadata("userId", "u-123");
TraceLog.metadata("tenantId", "t-456");
```

### `TraceLog.startManualTrace(String entryPoint)`

Starts a manual trace for code paths not covered by auto-instrumentation. Returns `ManualTrace` (implements `AutoCloseable`).

```java
try (var trace = TraceLog.startManualTrace("batch.import")) {
    // All TraceLog.event() calls attach to this trace
}
// Trace auto-completed and flushed on close
```

### `TraceLog.currentTraceId()`

Returns the current trace ID, if a trace is active. Returns `Optional<String>`.

```java
TraceLog.currentTraceId().ifPresent(id ->
    request.getHeaders().set("X-Trace-Id", id));
```

---

## LogEventBuilder

Fluent builder for constructing log events. Returned by `TraceLog.event()`.

### Data methods (chainable)

| Method | Description |
|--------|-------------|
| `.data(String key, Object value)` | Add a data point (technical key-value pair) |
| `.metric(String key, Number value)` | Add a business/performance metric |
| `.message(String msg)` | Add a human-readable description |
| `.duration(long ms)` | Set explicit duration in milliseconds |

### Terminal methods (emit the event)

| Method | Severity |
|--------|----------|
| `.trace()` | `TRACE` |
| `.debug()` | `DEBUG` |
| `.info()` | `INFO` |
| `.warn()` | `WARN` |
| `.error()` | `ERROR` |
| `.error(Throwable t)` | `ERROR` with exception details |

If no trace is active, all methods are no-ops (no exceptions thrown).

---

## TimedEvent

Auto-timed event wrapper. Returned by `TraceLog.timedEvent()`. Implements `AutoCloseable`.

### Methods (chainable)

| Method | Description |
|--------|-------------|
| `.data(String key, Object value)` | Add a data point |
| `.metric(String key, Number value)` | Add a metric |
| `.message(String msg)` | Add a message |
| `.severity(Severity level)` | Set severity (default: `INFO`) |

Duration is automatically calculated between creation and `close()`.

```java
try (var timer = TraceLog.timedEvent("slow.op").severity(Severity.WARN)) {
    timer.data("input", inputData);
    // ... slow operation ...
    timer.metric("processedRows", count);
}
```

---

## ManualTrace

Manual trace lifecycle wrapper. Returned by `TraceLog.startManualTrace()`. Implements `AutoCloseable`.

On `close()`, the trace is completed with `SUCCESS` status and submitted for flushing. Thread-safe (uses `AtomicBoolean` to prevent double-close).

---

## Enums

### Severity

```java
TRACE, DEBUG, INFO, WARN, ERROR
```

### TraceStatus

```java
SUCCESS   // Normal completion
ERROR     // Exception occurred
TIMEOUT   // Trace exceeded max duration
PARTIAL   // Trace force-flushed before completion
```

---

## Data Model

### CompletedTrace (JSON output)

| Field | Type | Description |
|-------|------|-------------|
| `traceId` | String | OTEL trace ID (if `traceparent` header present), ULID, or UUID |
| `serviceName` | String | From `spring.application.name` or config |
| `entryPoint` | String | e.g., `REST POST /api/orders (OrderController.create)` |
| `startTime` | ISO-8601 | Trace start |
| `endTime` | ISO-8601 | Trace end |
| `durationMs` | long | Total trace duration |
| `status` | String | `SUCCESS`, `ERROR`, `TIMEOUT`, `PARTIAL` |
| `parentTraceId` | String | From incoming header (null if none) |
| `metadata` | Map | Trace-level key-value pairs |
| `events` | List | Ordered list of LogEvent objects |

### LogEvent (within events array)

| Field | Type | Description |
|-------|------|-------------|
| `eventName` | String | e.g., `order.created`, `db.query` |
| `timestamp` | ISO-8601 | Event creation time |
| `severity` | String | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `origin` | String | Caller class and method (auto-captured) |
| `dataPoints` | Map | Technical key-value pairs |
| `metrics` | Map | Business/performance numbers |
| `message` | String | Human-readable description (nullable) |
| `exception` | Object | Exception details (nullable) |
| `durationMs` | Long | For timed events (nullable) |

### ExceptionInfo (within exception field)

| Field | Type | Description |
|-------|------|-------------|
| `type` | String | Exception class name |
| `message` | String | Exception message |
| `stackTrace` | String | Stack trace (truncated to 4096 chars) |

---

## Extension Points

### Custom LogSink

```java
public interface LogSink {
    void write(CompletedTrace trace);
    void flush();
    void close();
}
```

Register as a Spring bean to override the default `JsonStdoutSink`:

```java
@Bean
public LogSink logSink() {
    return new MyCustomSink();
}
```

### Custom TraceIdGenerator

```java
public interface TraceIdGenerator {
    String generate();
}
```

Register as a Spring bean to override:

```java
@Bean
public TraceIdGenerator traceIdGenerator() {
    return new MyCustomIdGenerator();
}
```

### Custom TraceHandlerInterceptor

The interceptor is a `@ConditionalOnMissingBean` — provide your own:

```java
@Bean
public TraceHandlerInterceptor traceHandlerInterceptor(
        TraceContextManager contextManager,
        BufferManager bufferManager) {
    return new MyCustomInterceptor(contextManager, bufferManager, "X-Trace-Id");
}
```

### CompositeSink

Fan out to multiple sinks:

```java
@Bean
public LogSink logSink(ObjectMapper mapper) {
    return new CompositeSink(List.of(
        new JsonStdoutSink(mapper, false, System.out),
        new HttpSink("https://logs.example.com"),
        new KafkaSink(kafkaTemplate, "trace-logs")
    ));
}
```
