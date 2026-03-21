# trace-log

Trace-scoped structured logging library for Spring Boot microservices. Generates a trace ID at every inflow point, auto-propagates a rich log context through the call stack, and flushes all events as a single structured JSON document per interaction.

## Quick Start

### 1. Add the dependency

```xml
<dependency>
    <groupId>com.tracing</groupId>
    <artifactId>trace-log-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Use `TraceLog` anywhere in your code

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @PostMapping
    public ResponseEntity<Order> create(@RequestBody OrderRequest req) {
        // Trace already started by the interceptor — just add events
        TraceLog.metadata("userId", req.getUserId());

        TraceLog.event("order.received")
            .data("itemCount", req.getItems().size())
            .metric("total", req.getTotal())
            .info();

        Order order = orderService.create(req);

        TraceLog.event("order.created")
            .data("orderId", order.getId())
            .info();

        return ResponseEntity.ok(order);
        // Trace auto-completed and flushed by the interceptor
    }
}
```

```java
@Service
public class OrderService {

    public Order create(OrderRequest req) {
        // No object passing needed — TraceLog is static + ThreadLocal

        try (var timer = TraceLog.timedEvent("inventory.check")) {
            inventoryClient.reserve(req.getItems());
            timer.data("reserved", req.getItems().size());
        }

        try (var timer = TraceLog.timedEvent("payment.charge")) {
            paymentService.charge(req.getPaymentMethod(), req.getTotal());
            timer.metric("amount", req.getTotal());
        }

        return orderRepository.save(new Order(req));
    }
}
```

### 3. Read the output

Every completed request produces one JSON document on stdout:

```json
{
  "traceId": "01KM76KPRQ9CQYM8Y9VHRS5WTK",
  "serviceName": "order-service",
  "entryPoint": "REST POST /api/orders (OrderController.create)",
  "startTime": "2026-03-20T10:15:30.123Z",
  "endTime": "2026-03-20T10:15:30.456Z",
  "durationMs": 333,
  "status": "SUCCESS",
  "parentTraceId": "upstream-trace-abc",
  "metadata": {
    "userId": "u-12345"
  },
  "events": [
    {
      "eventName": "request.received",
      "timestamp": "2026-03-20T10:15:30.123Z",
      "severity": "INFO",
      "origin": "unknown",
      "dataPoints": { "method": "POST", "uri": "/api/orders" }
    },
    {
      "eventName": "order.received",
      "timestamp": "2026-03-20T10:15:30.130Z",
      "severity": "INFO",
      "origin": "com.example.OrderController.create",
      "dataPoints": { "itemCount": 3 },
      "metrics": { "total": 149.99 }
    },
    {
      "eventName": "inventory.check",
      "timestamp": "2026-03-20T10:15:30.135Z",
      "severity": "INFO",
      "origin": "com.example.OrderService.create",
      "dataPoints": { "reserved": 3 },
      "durationMs": 25
    },
    {
      "eventName": "payment.charge",
      "timestamp": "2026-03-20T10:15:30.200Z",
      "severity": "INFO",
      "origin": "com.example.OrderService.create",
      "metrics": { "amount": 149.99 },
      "durationMs": 55
    },
    {
      "eventName": "order.created",
      "timestamp": "2026-03-20T10:15:30.420Z",
      "severity": "INFO",
      "origin": "com.example.OrderController.create",
      "dataPoints": { "orderId": "ORD-123" }
    },
    {
      "eventName": "request.completed",
      "timestamp": "2026-03-20T10:15:30.456Z",
      "severity": "INFO",
      "dataPoints": { "statusCode": 200 }
    }
  ]
}
```

## Features

- **Zero config** — add the dependency, everything auto-configures
- **Auto-instrumented inflows** — REST endpoints, Kafka listeners, JMS/IBM MQ listeners, `@Scheduled` tasks
- **ThreadLocal propagation** — `TraceLog.event()` works from any class on the call stack without passing objects
- **Business metrics** — first-class `.metric("revenue", 1500.00)` on any event
- **ULID trace IDs** — time-sortable, 26 characters, no external dependency
- **OpenTelemetry compatibility** — auto-detects W3C `traceparent` header (including mixed-case hex) and reuses the OTEL trace ID; falls back to ULID/UUID when no parent trace exists
- **Sampling** — pluggable `SamplingStrategy` with built-in rate-based sampling; error traces are always captured regardless of rate
- **Immediate flush on trace completion** — minimizes log loss on pod crash
- **Backpressure** — configurable queue limit with drop-and-warn
- **Write retry** — failed sink writes retry before dropping
- **Cross-service correlation** — `X-Trace-Id` and W3C `traceparent` headers supported in/out
- **SLF4J MDC bridge** — trace ID available in all standard log statements
- **Async propagation** — `@Async` methods and custom thread pools carry trace context
- **Production validated** — 586k TPS throughput, 2 us p99 latency ([benchmarks](docs/PERFORMANCE.md))

## API Reference

### Adding Events

```java
// Simple event
TraceLog.event("user.lookup")
    .data("userId", userId)
    .info();

// With business metrics
TraceLog.event("payment.processed")
    .data("orderId", order.getId())
    .metric("amount", order.getTotal())
    .metric("itemCount", order.getItems().size())
    .info();

// Error with exception
TraceLog.event("validation.failed")
    .data("field", "email")
    .error(exception);

// Warning
TraceLog.event("inventory.low")
    .data("sku", sku)
    .metric("remaining", 3)
    .warn();

// With a message
TraceLog.event("cache.miss")
    .data("key", cacheKey)
    .message("Falling back to database")
    .info();
```

### Timed Operations

```java
// Auto-records duration on close
try (var timer = TraceLog.timedEvent("db.query")) {
    result = repository.findById(id);
    timer.data("resultCount", result.size());
}

// Custom severity for timed operations
try (var timer = TraceLog.timedEvent("external.api").severity(Severity.WARN)) {
    response = httpClient.call(url);
    timer.data("statusCode", response.status());
}
```

### Trace-Level Metadata

```java
// Attach metadata to the entire trace
TraceLog.metadata("userId", currentUser.getId());
TraceLog.metadata("tenantId", tenant.getId());
```

### Manual Traces

For code paths not covered by auto-instrumentation:

```java
try (var trace = TraceLog.startManualTrace("batch.import")) {
    TraceLog.event("import.started").data("file", fileName).info();
    // ... all events attach to this trace ...
    TraceLog.event("import.completed").metric("rowCount", rows).info();
}
```

### Current Trace ID

```java
Optional<String> traceId = TraceLog.currentTraceId();
```

## Auto-Instrumented Inflows

| Inflow | Mechanism | Conditional On |
|--------|-----------|----------------|
| REST endpoints | `TraceHandlerInterceptor` | `spring-webmvc` on classpath |
| Kafka listeners | `TraceKafkaInterceptor` | `spring-kafka` on classpath |
| JMS listeners (IBM MQ, ActiveMQ) | `TraceJmsListenerAspect` | `spring-jms` + `aspectjweaver` on classpath |
| `@Scheduled` tasks | `TraceScheduledAspect` | `aspectjweaver` on classpath |
| `@Async` methods | `TraceTaskDecorator` | `@Async` on classpath |

Each interceptor:
1. Checks for a W3C `traceparent` header — if present, reuses the OTEL trace ID
2. Falls back to generating a new ULID/UUID if no `traceparent` is found
3. Extracts parent trace ID from `X-Trace-Id` header/property if present
4. Populates SLF4J MDC with the trace ID
5. Logs entry/exit events automatically
6. Ends the trace and flushes to the sink
7. Cleans up ThreadLocal in a `finally` block

## Cross-Service Correlation

### OpenTelemetry Integration

If your services use OpenTelemetry, trace-log automatically detects the W3C `traceparent` header and **reuses the OTEL trace ID** as its own. This means trace-log output and OTEL spans share the same trace ID — no manual correlation needed.

| Incoming Header | Behavior |
|-----------------|----------|
| `traceparent: 00-{traceId}-{spanId}-{flags}` | OTEL trace ID is used as trace-log's `traceId` |
| `X-Trace-Id: {id}` (no `traceparent`) | Custom header stored as `parentTraceId`; new ULID/UUID generated |
| Neither header present | New ULID/UUID generated |

When both `traceparent` and `X-Trace-Id` are present, the OTEL trace ID takes precedence as the `traceId`, and `X-Trace-Id` is stored as `parentTraceId`.

### Incoming requests

The library reads the W3C `traceparent` header and the `X-Trace-Id` header (configurable) from incoming HTTP requests, Kafka message headers, and JMS message properties.

### Outgoing requests

Add the trace ID to outgoing calls:

```java
// HTTP (RestTemplate)
restTemplate.execute(url, method, request -> {
    TraceLog.currentTraceId().ifPresent(id ->
        request.getHeaders().set("X-Trace-Id", id));
    // ...
});

// Kafka
kafkaTemplate.send(new ProducerRecord<>(topic, key, value))
    .headers().add("X-Trace-Id",
        TraceLog.currentTraceId().orElse("").getBytes());

// JMS
jmsTemplate.convertAndSend(destination, payload, message -> {
    TraceLog.currentTraceId().ifPresent(id ->
        message.setStringProperty("X-Trace-Id", id));
    return message;
});
```

## Configuration

All properties are optional. Defaults work out of the box.

```properties
# Enable/disable the library (default: true)
tracelog.enabled=true

# Service name (default: spring.application.name)
tracelog.service-name=order-service

# Trace ID format: ULID or UUID (default: ULID)
tracelog.trace-id.format=ULID

# Header name for cross-service propagation (default: X-Trace-Id)
tracelog.trace-id.propagation-header=X-Trace-Id

# Max events per trace before capping (default: 1000)
tracelog.buffer.max-events-per-trace=1000

# How often to scan for orphaned traces (default: 5s)
tracelog.buffer.orphan-scan-interval-seconds=5

# Max traces in the flush queue before dropping (default: 10000)
tracelog.buffer.max-pending-traces=10000

# Sampling rate: 0.0 to 1.0 (default: 1.0 = sample everything)
# Error traces are always sampled regardless of rate
tracelog.sampling.rate=1.0

# Pretty-print JSON output (default: false)
tracelog.sink.pretty-print=false
```

## Architecture

### Module Structure

```
trace-log/
├── trace-log-core/                  Zero Spring dependencies
│   ├── TraceLog                     Static facade API
│   ├── TraceContext                 Per-trace state (ThreadLocal)
│   ├── TraceContextManager          Context lifecycle
│   ├── LogEvent / LogEventBuilder   Event model + fluent builder
│   ├── TimedEvent                   AutoCloseable timed operations
│   ├── CompletedTrace               Immutable snapshot for serialization
│   ├── BufferManager                Async queue + scheduled drain
│   ├── SamplingStrategy             Pluggable trace sampling
│   ├── LogSink / JsonStdoutSink     Pluggable output
│   ├── UlidGenerator                Time-sortable ID generation
│   ├── W3CTraceparentParser         OTEL traceparent header parsing
│   └── TraceTaskDecorator           Async context propagation
│
├── trace-log-spring-boot-starter/   Auto-configuration
│   ├── TraceLogAutoConfiguration    Bean wiring + sampling
│   ├── TraceHandlerInterceptor      REST interception
│   ├── TraceScheduledAspect         @Scheduled interception
│   ├── TraceKafkaInterceptor        Kafka interception
│   ├── TraceJmsListenerAspect       JMS/IBM MQ interception
│   └── TraceMdcFilter               SLF4J MDC bridge
│
├── trace-log-example/               Demo Spring Boot app
├── trace-log-e2e/                   Docker-based E2E tests
└── trace-log-bom/                   Bill of Materials
```

### Trace Lifecycle

```
1. Request arrives
   → Interceptor checks for W3C traceparent header
   → If present, OTEL trace ID is extracted and reused
   → If absent, ULID/UUID generated
   → TraceContext pushed to ThreadLocal stack
   → Trace ID copied to SLF4J MDC and response header

2. Application code runs
   → TraceLog.event("name").data("k", v).info()
   → LogEventBuilder captures caller via StackWalker
   → LogEvent added to TraceContext's ConcurrentLinkedQueue

3. Request completes
   → Interceptor calls TraceContextManager.endTrace()
   → TraceContext frozen into immutable CompletedTrace
   → CompletedTrace queued in BufferManager
   → ThreadLocal and MDC cleaned up

4. Background flush (every 5s)
   → BufferManager drains queue in batches of 256
   → Each trace serialized to JSON via Jackson
   → Written to stdout (or custom LogSink)
   → Failed writes retried once before dropping
```

### Thread Safety

- `TraceContext.events`: `ConcurrentLinkedQueue` — lock-free event accumulation
- `TraceContext.metadata`: `ConcurrentHashMap` — concurrent metadata writes
- `TraceContext.eventCount`: `AtomicInteger` with CAS loop — exact cap enforcement
- `BufferManager.pendingTraces`: `ConcurrentLinkedQueue` — lock-free submission
- `JsonStdoutSink`: synchronized only on `println()` — serialization runs unlocked
- `TraceLog` static fields: `volatile` with synchronized initialization

## Custom Sink

Implement `LogSink` to send traces anywhere:

```java
public class HttpSink implements LogSink {
    private final HttpClient client;
    private final String endpoint;

    @Override
    public void write(CompletedTrace trace) {
        // POST trace as JSON to your observability backend
    }

    @Override
    public void flush() { }

    @Override
    public void close() { client.close(); }
}
```

Register as a Spring bean to override the default:

```java
@Bean
public LogSink logSink() {
    return new HttpSink("https://logs.example.com/ingest");
}
```

Use `CompositeSink` to write to multiple destinations:

```java
@Bean
public LogSink logSink(ObjectMapper mapper) {
    return new CompositeSink(List.of(
        new JsonStdoutSink(mapper, false, System.out),
        new HttpSink("https://logs.example.com/ingest")
    ));
}
```

## Async / Thread Pool Propagation

### @Async methods

Automatic — the starter registers a `TaskDecorator` that propagates trace context.

### Custom ExecutorService

```java
ExecutorService tracedPool = new TraceableExecutorService(
    Executors.newFixedThreadPool(10),
    new TraceTaskDecorator(contextManager),
    contextManager
);
```

## Requirements

- Java 17+
- Spring Boot 3.x
- Jackson (included transitively)

### Optional Dependencies (auto-detected)

| Feature | Dependency |
|---------|-----------|
| REST tracing | `spring-boot-starter-web` |
| Kafka tracing | `spring-kafka` |
| JMS/IBM MQ tracing | `spring-jms` + `jakarta.jms-api` |
| `@Scheduled` tracing | `spring-boot-starter-aop` |
| `@Async` propagation | (always available) |

## Building and Testing

```bash
# Build and run unit + integration tests (no Docker required)
mvn clean verify -DskipE2E=true

# Run E2E tests (requires Docker)
mvn test -pl trace-log-e2e

# Run performance benchmarks
mvn test -pl trace-log-core -Dtest="TraceLogPerformanceTest"

# Full build — all 97 tests including E2E
mvn clean verify
```

### Test Coverage

| Module | Tests | What's Covered |
|--------|-------|----------------|
| trace-log-core | 43 | Trace lifecycle, events, buffering, sampling, W3C parsing, latency benchmarks |
| trace-log-spring-boot-starter | 40 | Auto-configuration, REST/Kafka/JMS/Scheduled interceptors, task decorator |
| trace-log-e2e | 14 | Full Docker-based request flows, trace propagation, sampling, concurrent load |
| **Total** | **97** | |

## Running the Example

### With Maven

```bash
mvn -pl trace-log-example spring-boot:run
```

### With Docker

No Java or Maven installation required:

```bash
# Build and start
docker compose up --build

# View logs (trace JSON output)
docker compose logs -f

# Stop
docker compose down
```

### E2E test environment

```bash
# Start both default and sampled instances
docker compose -f docker-compose.e2e.yml up -d --build

# Default app: http://localhost:8085
# Sampled app (50% rate): http://localhost:8086

# View trace output
docker compose -f docker-compose.e2e.yml logs --no-log-prefix trace-log-app | grep '"traceId"'

# Stop
docker compose -f docker-compose.e2e.yml down
```

### Test endpoints

```bash
# Create an order
curl -X POST http://localhost:8085/api/orders \
  -H "Content-Type: application/json" \
  -H "X-Trace-Id: upstream-trace-123" \
  -d '{"userId":"u-42","items":["ITEM-A","ITEM-B"],"paymentMethod":"visa","total":99.99}'

# With OTEL traceparent header
curl -X POST http://localhost:8085/api/orders \
  -H "Content-Type: application/json" \
  -H "traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01" \
  -d '{"userId":"u-42","items":["ITEM-A","ITEM-B"],"paymentMethod":"visa","total":99.99}'

# Health check
curl http://localhost:8085/health
```

Watch stdout for structured trace JSON output.

## Documentation

| Document | Description |
|----------|-------------|
| [Configuration Reference](docs/CONFIGURATION.md) | All properties with defaults, sampling config, bean overrides |
| [Usage Guide](docs/USAGE.md) | Detailed examples across a payment microservices ecosystem |
| [Integration Guide](docs/INTEGRATION.md) | Setup for REST, Kafka, JMS, Scheduled, Async |
| [API Reference](docs/API.md) | Complete API documentation for TraceLog, LogEventBuilder, TimedEvent |
| [Architecture](docs/ARCHITECTURE.md) | Module structure, trace lifecycle, thread safety model |
| [Performance Benchmarks](docs/PERFORMANCE.md) | 586k TPS throughput, 2 us p99 latency, methodology |
| [Product Requirements](docs/PRD.md) | Problem statement, design principles, target users |
