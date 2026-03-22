# trace-log Adoption Guide

Step-by-step instructions for engineers adopting trace-log in a Spring Boot microservice.

---

## Step 1 — Add the Maven dependency

Add to your service's `pom.xml`:

```xml
<dependency>
    <groupId>com.tracing</groupId>
    <artifactId>trace-log-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Alternatively, if your org uses the BOM for version management:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.tracing</groupId>
            <artifactId>trace-log-bom</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.tracing</groupId>
        <artifactId>trace-log-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

## Step 2 — Set your service name

In `application.yml` (or `application.properties`):

```yaml
spring:
  application:
    name: my-order-service   # used as default if tracelog.service-name not set

tracelog:
  service-name: my-order-service   # explicit override (optional if spring.application.name is set)
```

## Step 3 — Understand what's automatic (zero code changes)

Once the starter is on the classpath, traces are **automatically created and flushed** for:

| Inflow type | What you need on classpath | What happens |
|---|---|---|
| **REST endpoints** (`@RestController`, `@GetMapping`, etc.) | `spring-webmvc` (already there if you use `spring-boot-starter-web`) | Every HTTP request gets a trace. Trace completes when response is sent. HTTP 5xx = ERROR status. |
| **Kafka consumers** (`@KafkaListener`) | `spring-kafka` | Every consumed record gets a trace. |
| **JMS listeners** (`@JmsListener`) | `spring-jms` + `aspectjweaver` | Every JMS message gets a trace. |
| **Scheduled tasks** (`@Scheduled`) | `aspectjweaver` | Every scheduled invocation gets a trace. |
| **Async methods** (`@Async`) | Spring async support | Trace context propagated from caller to async thread. |

**You do not need to annotate anything new or implement any interfaces.** Existing Spring annotations are intercepted automatically.

## Step 4 — Configure optional properties

All properties are optional. Here's the full set with defaults:

```yaml
tracelog:
  enabled: true                          # kill switch
  service-name: ${spring.application.name}

  trace-id:
    format: ULID                         # ULID or UUID
    propagation-header: X-Trace-Id       # header name for cross-service propagation

  sink:
    type: json-stdout                    # output destination
    pretty-print: false                  # human-readable JSON (useful in dev)

  buffer:
    max-events-per-trace: 1000           # cap per trace (prevent runaway loops)
    orphan-scan-interval-seconds: 5      # how often the buffer drains to the sink
    max-pending-traces: 10000            # backpressure threshold

  sampling:
    rate: 1.0                            # 1.0 = 100%, 0.5 = 50%, 0.0 = errors only

  no-context:
    behavior: NOOP                       # NOOP | WARN | AUTO_START
```

**Recommended for dev:** `tracelog.sink.pretty-print: true`

**Recommended for high-traffic prod:** lower `tracelog.sampling.rate` (e.g. `0.1`). Error traces are **always** sampled regardless of rate.

## Step 5 — Add structured events in your business logic

This is the main thing you'll write code for. Use the static `TraceLog` API — no dependency injection needed.

### Add metadata (key-value pairs attached to the whole trace)

```java
TraceLog.metadata("userId", request.getUserId());
TraceLog.metadata("orderId", order.getId());
```

### Log a simple event

```java
TraceLog.event("order.validated")
    .data("itemCount", items.size())
    .metric("totalAmount", order.getTotal())
    .info();
```

### Log with different severities

```java
TraceLog.event("cache.miss").debug();
TraceLog.event("inventory.low").data("sku", sku).warn();
TraceLog.event("payment.failed").error(exception);
```

### Time an operation (auto-records duration)

```java
try (var timer = TraceLog.timedEvent("db.query")) {
    List<Order> results = repository.findByUserId(userId);
    timer.data("rowCount", results.size());
    timer.metric("resultSize", results.size());
}
// duration automatically recorded when try-with-resources closes
```

## Step 6 — Propagate trace IDs across services

The library **automatically reads** inbound trace IDs from:

- HTTP header `X-Trace-Id` (configurable via `tracelog.trace-id.propagation-header`)
- W3C `traceparent` header (OpenTelemetry standard)
- Kafka record headers
- JMS message properties

**You must manually propagate the trace ID on outbound calls.** When calling another service:

```java
// Get current trace ID
String traceId = TraceLog.currentTraceId().orElse("");

// Add to outbound HTTP call
HttpHeaders headers = new HttpHeaders();
headers.set("X-Trace-Id", traceId);
// or use the W3C traceparent format if the downstream expects it
```

If you use `RestTemplate` or `WebClient`, consider writing a `ClientHttpRequestInterceptor` that does this automatically for all outbound calls.

## Step 7 — Handle async / thread pool scenarios

### @Async methods

Handled automatically. Trace context propagates to the async thread.

### Custom thread pools / ExecutorService

Wrap with `TraceableExecutorService`:

```java
@Bean
public ExecutorService myThreadPool(TraceContextManager contextManager) {
    return new TraceableExecutorService(
        Executors.newFixedThreadPool(10),
        new TraceTaskDecorator(contextManager),
        contextManager
    );
}
```

### Manual Runnable decoration

```java
@Autowired
private TraceTaskDecorator traceTaskDecorator;

Runnable decorated = traceTaskDecorator.decorate(() -> {
    // trace context available here
    TraceLog.event("async.work").info();
});
executor.submit(decorated);
```

## Step 8 — Handle code paths not covered by interceptors

For code that runs outside REST/Kafka/JMS/Scheduled (e.g., CLI runners, custom listeners):

```java
try (ManualTrace trace = TraceLog.startManualTrace("batch-import")) {
    TraceLog.event("import.started").info();
    // ... your logic ...
    TraceLog.event("import.completed").data("rows", count).info();
}
// trace flushed on close
```

## Step 9 — Read the output

Each completed trace produces **one JSON document** on stdout. Example:

```json
{
  "traceId": "01KM76KPRQ9CQYM8Y9VHRS5WTK",
  "serviceName": "my-order-service",
  "entryPoint": "REST POST /api/orders (OrderController.create)",
  "startTime": "2026-03-20T10:15:30.123Z",
  "endTime": "2026-03-20T10:15:30.456Z",
  "durationMs": 333,
  "status": "SUCCESS",
  "parentTraceId": "upstream-trace-abc",
  "metadata": { "userId": "u-12345", "orderId": "ORD-001" },
  "events": [
    {
      "eventName": "order.validated",
      "timestamp": "2026-03-20T10:15:30.200Z",
      "severity": "INFO",
      "origin": "OrderService.create",
      "dataPoints": { "itemCount": 3 },
      "metrics": { "totalAmount": 99.99 }
    }
  ]
}
```

The trace ID is also placed into the **SLF4J MDC** as `traceId`, so your existing log lines (Logback/Log4j2) can include it:

```xml
<!-- logback pattern -->
<pattern>%d [%X{traceId}] %-5level %logger - %msg%n</pattern>
```

## Step 10 — Verify it works

1. Start your app
2. Hit an endpoint: `curl -s http://localhost:8080/your-endpoint | jq`
3. Check stdout for the trace JSON document
4. To test cross-service propagation:
   ```bash
   curl -H "X-Trace-Id: test-123" http://localhost:8080/your-endpoint
   ```
   Verify `parentTraceId` appears in the output

## Step 11 — Custom sink (optional, advanced)

If you need to send traces somewhere other than stdout (e.g., Elasticsearch, a file, a message queue), implement `LogSink` and register it as a Spring bean:

```java
@Bean
public LogSink myCustomSink() {
    return new LogSink() {
        public void write(CompletedTrace trace) { /* send somewhere */ }
        public void flush() { /* flush if buffered */ }
        public void close() { /* cleanup */ }
    };
}
```

This overrides the default `JsonStdoutSink`. Use `CompositeSink` if you want both:

```java
@Bean
public LogSink compositeSink() {
    return new CompositeSink(List.of(new JsonStdoutSink(), myCustomSink()));
}
```

---

## Common pitfalls

- **Don't** create `TraceLog` instances — it's a static API backed by ThreadLocal
- **Don't** forget to close `TimedEvent` — always use try-with-resources
- **Don't** log sensitive data (PII, credentials) via `.data()` or `.metadata()` — it goes to stdout as JSON
- **Do** set `tracelog.no-context.behavior: WARN` during development to catch places where you call `TraceLog` outside a traced context
