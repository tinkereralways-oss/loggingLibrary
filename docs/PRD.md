# trace-log — Product Requirements Document

**Version:** 1.0.0-SNAPSHOT
**Last Updated:** 2026-03-21
**Status:** In Development

---

## 1. Problem Statement

In microservice architectures, a single user interaction (e.g., placing an order) fans out across multiple services, threads, and messaging systems. Traditional logging produces hundreds of disconnected log lines scattered across stdout, making it extremely difficult to:

- **Correlate logs** belonging to the same request across controller → service → repository layers
- **Measure operation latency** without manual stopwatch code
- **Search and filter** logs by business context (user ID, order total, payment method)
- **Trace requests across services** without dedicated infrastructure (Jaeger, Zipkin)
- **Maintain context** when work moves to async threads or message consumers

Engineering teams resort to grep-by-timestamp, manual MDC management, and passing context objects through every method signature — all of which are error-prone and add significant boilerplate.

---

## 2. Product Vision

**trace-log** is a lightweight, zero-config structured logging library for Spring Boot microservices that produces **one JSON document per interaction** containing all events, metrics, and context — automatically correlated by trace ID.

### Design Principles

1. **Zero boilerplate** — No context objects to pass. `TraceLog.event()` works from anywhere on the call stack via ThreadLocal.
2. **Zero config** — Add the dependency, everything auto-configures. No YAML, no annotations, no bean definitions.
3. **Zero external dependencies** — No tracing backend required. Output is structured JSON to stdout, consumable by any log aggregator.
4. **Non-invasive** — Library code never throws exceptions to the caller. Failed events are silently dropped. Failed sink writes are retried once then dropped.
5. **Production-safe** — Lock-free hot path, bounded queues with backpressure, configurable caps, graceful shutdown.

---

## 3. Target Users

trace-log is built for teams running **business-critical microservices** — payments, order management, fulfillment, onboarding, lending — where every minute of downtime or misdiagnosis costs revenue and customer trust. In these domains, MTTR (Mean Time to Resolution) is the metric that matters most, and it is almost entirely determined by how fast an engineer can go from "alert fired" to "I see the exact request that failed, what it was doing, and why."

### Primary Personas

| Persona | Pain Point | How trace-log reduces MTTR |
|---------|------------|---------------------------|
| **Backend engineers on business-critical services** | Debugging a failed payment or order requires correlating dozens of log lines across controller, service, repository, and async layers — often under incident pressure | One JSON document per request with every event, metric, and timing in sequence. Search by trace ID → see the full story in one place. No grep chains, no timestamp guessing. |
| **SREs / On-call engineers** | Incident triage means answering "which request failed, what did it touch, and how long did each step take?" — currently requires hopping between log streams and dashboards | Structured events with `durationMs`, business metrics, and exception details enable root-cause identification from a single trace document. Filter by `status: ERROR` to find failing traces instantly. |
| **Platform / Observability teams** | Standardizing logging across 10+ services without mandating a full tracing backend (Jaeger, Tempo) or rewriting existing code | Drop-in starter with auto-instrumentation. No code changes required for basic tracing. JSON output pipes directly to Splunk, DataDog, ELK, or any log aggregator the team already uses. |
| **Teams migrating to OpenTelemetry** | OTEL rollout is incremental — some services have it, some don't. Trace correlation breaks at service boundaries during the migration | Auto-detects W3C `traceparent` header and reuses the OTEL trace ID. Services with OTEL and services with trace-log share the same trace ID. No flag day required. |

### Why MTTR Matters Here

In business microservices, the cost of slow diagnosis is concrete:

- **Payments:** A failed charge that takes 30 minutes to diagnose means 30 minutes of blocked revenue and potential SLA breach
- **Order management:** An intermittent fulfillment bug that only reproduces under specific item/warehouse combinations is invisible without structured business context in logs
- **Onboarding/KYC:** A compliance-critical flow failing silently (no structured error, just a 500) can block customer acquisition for hours
- **Event-driven architectures:** A Kafka consumer processing a poisoned message is nearly impossible to debug without correlating the inbound message metadata to the downstream processing steps

trace-log directly attacks the diagnosis phase of MTTR by ensuring that when an engineer opens a trace, they see **the complete request lifecycle with business context** — not a wall of unstructured text.

---

## 4. Functional Requirements

### 4.1 Core Logging API

| ID | Requirement | Priority |
|----|-------------|----------|
| F-01 | Developers can log named events with structured key-value data points | P0 |
| F-02 | Developers can attach numeric business metrics to events | P0 |
| F-03 | Developers can attach human-readable messages to events | P1 |
| F-04 | Events support severity levels: TRACE, DEBUG, INFO, WARN, ERROR | P0 |
| F-05 | Error events can capture exception type, message, and truncated stack trace | P0 |
| F-06 | Timed events automatically measure elapsed duration via try-with-resources | P0 |
| F-07 | Developers can attach trace-level metadata (e.g., userId, tenantId) that applies to the entire trace | P0 |
| F-08 | The API is static — no instance or context object needs to be passed through the call stack | P0 |
| F-09 | All API calls are no-ops when no trace is active (no exceptions thrown) | P0 |
| F-10 | Each event automatically captures the caller's class and method name (origin) | P1 |

### 4.2 Auto-Instrumentation

| ID | Requirement | Priority |
|----|-------------|----------|
| F-11 | REST endpoints are automatically traced via Spring MVC `HandlerInterceptor` | P0 |
| F-12 | Kafka consumer messages are automatically traced via `RecordInterceptor` | P0 |
| F-13 | JMS/IBM MQ listener methods are automatically traced via AOP `@Around` aspect | P0 |
| F-14 | `@Scheduled` tasks are automatically traced via AOP `@Around` aspect | P1 |
| F-15 | Each auto-instrumented inflow logs entry and exit events with transport-specific metadata | P0 |
| F-16 | Auto-instrumentation is conditional on classpath — missing dependencies simply disable the feature | P0 |
| F-17 | All auto-configured beans use `@ConditionalOnMissingBean` so users can override with custom implementations | P0 |

### 4.3 Trace ID Generation and Propagation

| ID | Requirement | Priority |
|----|-------------|----------|
| F-18 | Trace IDs are generated as ULIDs by default (time-sortable, 26 characters) | P0 |
| F-19 | UUID v4 format is available as a configuration option | P2 |
| F-20 | W3C `traceparent` header is auto-detected on incoming requests (HTTP, Kafka, JMS) | P0 |
| F-21 | When `traceparent` is present, the OTEL trace ID (32-char hex) is used as the trace-log trace ID | P0 |
| F-22 | When `traceparent` is absent, a new ULID/UUID is generated | P0 |
| F-23 | The custom `X-Trace-Id` header is read and stored as `parentTraceId` for upstream correlation | P0 |
| F-24 | The trace ID is set on the HTTP response header for downstream propagation | P0 |
| F-25 | The trace ID is placed in SLF4J MDC under key `traceId` for standard log integration | P0 |
| F-26 | `traceparent` parser validates: minimum length, delimiter positions, all-zero rejection, lowercase hex charset | P1 |

### 4.4 Context Propagation

| ID | Requirement | Priority |
|----|-------------|----------|
| F-27 | Trace context propagates to `@Async` methods automatically via Spring `TaskDecorator` | P0 |
| F-28 | `TraceableExecutorService` wrapper enables propagation for custom thread pools | P1 |
| F-29 | `TraceTaskDecorator` provides low-level `Runnable` wrapping for manual propagation (e.g., `CompletableFuture`) | P1 |
| F-30 | Nested traces are supported via ThreadLocal stack (push/pop semantics) | P2 |
| F-31 | Manual traces can be started via `TraceLog.startManualTrace()` for non-instrumented code paths | P1 |

### 4.5 Output and Sink

| ID | Requirement | Priority |
|----|-------------|----------|
| F-32 | Each completed trace produces exactly one JSON document containing all events, metadata, timing, and status | P0 |
| F-33 | Default output is JSON to stdout via `JsonStdoutSink` | P0 |
| F-34 | JSON timestamps use ISO-8601 format | P1 |
| F-35 | Null fields are excluded from JSON output | P1 |
| F-36 | Pretty-print mode is configurable for development | P2 |
| F-37 | Custom `LogSink` implementations can be registered as Spring beans to override the default | P0 |
| F-38 | `CompositeSink` enables writing to multiple destinations simultaneously with independent failure handling | P1 |

### 4.6 Buffering and Reliability

| ID | Requirement | Priority |
|----|-------------|----------|
| F-39 | Completed traces are buffered in an async queue and flushed by a background thread | P0 |
| F-40 | Flush interval is configurable (default: 5 seconds) | P1 |
| F-41 | Flush batch size is capped at 256 traces per cycle | P1 |
| F-42 | Failed sink writes are retried once before dropping | P0 |
| F-43 | Backpressure: traces are dropped with a stderr warning when the queue exceeds `maxPendingTraces` (default: 10,000) | P0 |
| F-44 | Events are dropped with a stderr warning when a trace exceeds `maxEventsPerTrace` (default: 1,000) | P0 |
| F-45 | Graceful shutdown drains the entire queue before closing the sink | P0 |
| F-46 | ThreadLocal state is always cleaned up in `finally` blocks to prevent leaks in thread pools | P0 |

---

## 5. Non-Functional Requirements

### 5.1 Performance

| ID | Requirement | Target |
|----|-------------|--------|
| NF-01 | `TraceLog.event().info()` hot-path latency | < 1 microsecond (lock-free CAS + CLQ offer) |
| NF-02 | No locks on the per-request code path | CAS on AtomicInteger, ConcurrentLinkedQueue only |
| NF-03 | Sink I/O is fully decoupled from request threads | Background `ScheduledExecutorService` daemon thread |
| NF-04 | StackWalker origin capture overhead | Amortized via ConcurrentHashMap frame-filter cache (max 10,000 entries) |
| NF-05 | W3C `traceparent` parsing | Zero array allocation (`indexOf`-based, no `split()`) |

### 5.2 Reliability

| ID | Requirement |
|----|-------------|
| NF-06 | Library code never throws exceptions to application code |
| NF-07 | Failed traces are counted (`droppedCount`, `failedCount`) for monitoring |
| NF-08 | Flush thread has `IS_FLUSHING` guard to prevent re-entrant TraceLog calls from within sink code |
| NF-09 | `ManualTrace.close()` is idempotent via `AtomicBoolean` (safe for double-close) |

### 5.3 Compatibility

| ID | Requirement |
|----|-------------|
| NF-10 | Java 17+ |
| NF-11 | Spring Boot 3.x (auto-configuration) |
| NF-12 | `trace-log-core` has zero Spring dependency — usable standalone |
| NF-13 | Jackson 2.17+ for JSON serialization |
| NF-14 | Works alongside OpenTelemetry (shared trace ID via `traceparent`) |
| NF-15 | Works alongside existing SLF4J/Logback logging (MDC bridge) |

### 5.4 Operability

| ID | Requirement |
|----|-------------|
| NF-16 | Library can be fully disabled via `tracelog.enabled=false` |
| NF-17 | All configuration has sensible defaults — zero config required |
| NF-18 | Docker support: multi-stage Dockerfile, docker-compose.yml with healthcheck |
| NF-19 | Example application demonstrates all features out of the box |

---

## 6. Architecture

### 6.1 Module Structure

```
trace-log/
├── trace-log-core/                  Zero Spring dependencies
│   ├── TraceLog                     Static facade API
│   ├── TraceContextManager          ThreadLocal-based context lifecycle
│   ├── TraceContext                 Per-trace mutable state (events, metadata)
│   ├── CompletedTrace               Immutable snapshot for serialization
│   ├── LogEvent / LogEventBuilder   Event model + fluent builder
│   ├── TimedEvent                   AutoCloseable timed operations
│   ├── ManualTrace                  AutoCloseable manual trace lifecycle
│   ├── BufferManager                Async queue with scheduled drain
│   ├── LogSink / JsonStdoutSink     Pluggable output abstraction
│   ├── CompositeSink                Multi-sink fan-out
│   ├── W3CTraceparentParser         OTEL traceparent header parsing
│   ├── UlidGenerator                Time-sortable ID generation
│   ├── TraceTaskDecorator           Async context propagation
│   └── TraceableExecutorService     ExecutorService wrapper
│
├── trace-log-spring-boot-starter/   Auto-configuration
│   ├── TraceLogAutoConfiguration    Root config + bean wiring
│   ├── TraceLogWebMvcAutoConfiguration    REST interceptor
│   ├── TraceLogKafkaAutoConfiguration     Kafka interceptor
│   ├── TraceLogJmsAutoConfiguration       JMS/IBM MQ aspect
│   ├── TraceLogSchedulingAutoConfiguration  @Scheduled aspect
│   ├── TraceLogAsyncAutoConfiguration     @Async decorator
│   ├── TraceLogProperties           Configuration properties
│   └── TraceMdcFilter               SLF4J MDC bridge
│
├── trace-log-example/               Demo Spring Boot app
└── trace-log-bom/                   Bill of Materials
```

### 6.2 Trace Lifecycle

```
Request arrives
  → Interceptor checks for W3C traceparent header
  → If present: OTEL trace ID extracted and reused
  → If absent: new ULID/UUID generated
  → TraceContext pushed to ThreadLocal stack
  → Trace ID set in SLF4J MDC + response header

Application code runs
  → TraceLog.event("name").data("k", v).info()
  → LogEventBuilder captures caller via StackWalker
  → LogEvent added to TraceContext's ConcurrentLinkedQueue
  → AtomicInteger CAS loop enforces event cap

Request completes
  → TraceContext frozen into immutable CompletedTrace
  → CompletedTrace queued in BufferManager
  → ThreadLocal and MDC cleaned up in finally block

Background flush (every 5s)
  → BufferManager drains queue in batches of 256
  → Each trace serialized to JSON via Jackson
  → Written to LogSink (stdout by default)
  → Failed writes retried once before dropping
```

### 6.3 Thread Safety Model

| Component | Mechanism | Guarantee |
|-----------|-----------|-----------|
| `TraceContext.events` | `ConcurrentLinkedQueue` | Lock-free event accumulation from async handoffs |
| `TraceContext.metadata` | `ConcurrentHashMap` | Lock-free concurrent metadata writes |
| `TraceContext.eventCount` | `AtomicInteger` CAS loop | Exact max enforcement without over-counting |
| `BufferManager.pendingTraces` | `ConcurrentLinkedQueue` | Lock-free submission from request threads |
| `BufferManager.running` | `AtomicBoolean` | Idempotent shutdown |
| `BufferManager.IS_FLUSHING` | `ThreadLocal<Boolean>` | Prevents re-entrant TraceLog calls from flush thread |
| `JsonStdoutSink` | `synchronized(out)` on `println` only | Prevents interleaved JSON; serialization is unsynchronized |
| `TraceLog` static fields | `volatile` + `synchronized` init | Safe publication across threads |
| `ManualTrace.closed` | `AtomicBoolean` | Prevents double-close |

---

## 7. Configuration Reference

All properties are optional. Defaults work out of the box.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `tracelog.enabled` | boolean | `true` | Enable/disable the library entirely |
| `tracelog.service-name` | String | `${spring.application.name}` | Service identifier in trace output |
| `tracelog.trace-id.format` | `ULID` / `UUID` | `ULID` | ID generation strategy (overridden when `traceparent` present) |
| `tracelog.trace-id.propagation-header` | String | `X-Trace-Id` | Custom header name for cross-service correlation |
| `tracelog.buffer.max-events-per-trace` | int | `1000` | Events per trace before capping with warning |
| `tracelog.buffer.orphan-scan-interval-seconds` | int | `5` | Flush cycle interval |
| `tracelog.buffer.max-trace-duration-seconds` | int | `300` | Reserved for future orphan detection |
| `tracelog.buffer.max-pending-traces` | int | `10000` | Queue size before backpressure drops |
| `tracelog.sink.pretty-print` | boolean | `false` | Indent JSON output |
| `tracelog.no-context.behavior` | `NOOP` / `WARN` / `AUTO_START` | `NOOP` | Behavior when `TraceLog.event()` called with no active trace |

---

## 8. JSON Output Schema

Each completed trace produces one JSON document:

```json
{
  "traceId": "string — OTEL hex ID or ULID or UUID",
  "serviceName": "string",
  "entryPoint": "string — e.g. REST POST /api/orders (OrderController.create)",
  "startTime": "ISO-8601",
  "endTime": "ISO-8601",
  "durationMs": "long",
  "status": "SUCCESS | ERROR | TIMEOUT | PARTIAL",
  "parentTraceId": "string | null",
  "metadata": {
    "key": "value"
  },
  "events": [
    {
      "eventName": "string",
      "timestamp": "ISO-8601",
      "severity": "TRACE | DEBUG | INFO | WARN | ERROR",
      "origin": "string — ClassName.methodName",
      "dataPoints": { "key": "value" },
      "metrics": { "key": "number" },
      "message": "string | null",
      "exception": {
        "type": "string",
        "message": "string",
        "stackTrace": "string (truncated at 4096 chars)"
      },
      "durationMs": "long | null (timed events only)"
    }
  ]
}
```

---

## 9. Cross-Service Correlation

### Trace ID Resolution Priority

| Priority | Header | Result |
|----------|--------|--------|
| 1 (highest) | `traceparent: 00-{traceId}-{spanId}-{flags}` | OTEL trace ID becomes `traceId` |
| 2 | `X-Trace-Id: {value}` | Stored as `parentTraceId`; new ULID/UUID generated |
| 3 (fallback) | No headers | New ULID/UUID generated |

### Supported Transports

| Transport | Incoming ID extraction | Outgoing ID propagation |
|-----------|----------------------|------------------------|
| HTTP (REST) | `traceparent` header, `X-Trace-Id` header | `X-Trace-Id` response header (automatic) |
| Kafka | `traceparent` message header, `X-Trace-Id` message header | Manual via `record.headers().add()` |
| JMS | `traceparent` string property, `X-Trace-Id` string property | Manual via `message.setStringProperty()` |
| `@Scheduled` | N/A (no inbound headers) | N/A |

---

## 10. Extension Points

| Extension | Mechanism | Use Case |
|-----------|-----------|----------|
| Custom `LogSink` | Implement `LogSink` interface, register as `@Bean` | Send traces to Elasticsearch, Kafka, HTTP endpoint |
| Custom `TraceIdGenerator` | Implement `TraceIdGenerator` interface, register as `@Bean` | Custom ID format (e.g., prefixed UUIDs) |
| Custom `TraceHandlerInterceptor` | Extend or replace, register as `@Bean` | URL-path exclusions, custom header logic |
| `CompositeSink` | Wrap multiple `LogSink` instances | Write to stdout + remote backend simultaneously |
| `TraceableExecutorService` | Wrap any `ExecutorService` | Propagate trace context to custom thread pools |

---

## 11. Known Limitations and Trade-offs

| # | Limitation | Impact | Mitigation |
|---|-----------|--------|------------|
| 1 | Async workers that outlive the request lose events after `endTrace()` snapshot | Events emitted after trace completion are silently dropped | Ensure async work completes before request returns, or use manual traces for long-running async flows |
| 2 | Single flush thread | Slow sinks cause queue growth until drops | Tune `maxPendingTraces` and `orphanScanIntervalSeconds`; use fast sinks in production |
| 3 | Not a replacement for distributed tracing UIs | No flame graphs, service maps, or span-level visualization | Use alongside OTEL + Jaeger/Tempo for visual tracing; trace-log provides the structured log layer |
| 4 | No sampling | Every request produces a trace document | Implement sampling in a custom `LogSink` if needed |
| 5 | 5-second flush window on SIGKILL | In-flight and queued traces lost on hard kill | SIGTERM triggers graceful drain; for SIGKILL, reduce `orphanScanIntervalSeconds` |

---

## 12. Deployment

### Maven Dependency

```xml
<dependency>
    <groupId>com.tracing</groupId>
    <artifactId>trace-log-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Docker

```bash
docker compose up --build        # Build and start on port 8085
docker compose logs -f           # View trace JSON output
docker compose down              # Stop
```

### Verification

```bash
# Health check
curl http://localhost:8085/health

# Basic order (generates ULID trace ID)
curl -X POST http://localhost:8085/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":"u-42","items":["ITEM-A","ITEM-B"],"paymentMethod":"visa","total":99.99}'

# With OTEL traceparent (reuses OTEL trace ID)
curl -X POST http://localhost:8085/api/orders \
  -H "Content-Type: application/json" \
  -H "traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01" \
  -d '{"userId":"u-42","items":["ITEM-A"],"paymentMethod":"visa","total":49.99}'

# With upstream correlation header
curl -X POST http://localhost:8085/api/orders \
  -H "Content-Type: application/json" \
  -H "X-Trace-Id: upstream-trace-123" \
  -d '{"userId":"u-42","items":["ITEM-A"],"paymentMethod":"visa","total":49.99}'
```

---

## 13. Future Roadmap

| Item | Description | Priority |
|------|-------------|----------|
| Sampling support | Configurable sampling rate (e.g., 10% of traces) to reduce volume in high-throughput services | P1 |
| `AtomicInteger` queue counter | Replace `CLQ.size()` O(n) backpressure check with O(1) counter | P1 |
| Multi-thread flush pool | Configurable flush thread count for slow sinks | P2 |
| Reactive support | `WebFlux` / `WebFilter` instrumentation for reactive Spring Boot apps | P1 |
| gRPC interceptor | Auto-instrumentation for gRPC server calls | P2 |
| Trace export to OTLP | Export `CompletedTrace` as OTEL spans via OTLP protocol | P2 |
| Spring Cloud Gateway filter | Auto-instrumentation for API gateway routes | P2 |
| Metric emission | Emit trace duration and event count as Micrometer metrics | P2 |
| Trace ID in error responses | Automatically include trace ID in Spring error response bodies | P2 |
| Async join/await | Mechanism to delay `endTrace()` until async sub-tasks complete | P1 |

---

## 14. Success Metrics

| Metric | Target | Why it matters |
|--------|--------|----------------|
| **MTTR reduction** | Reduce incident diagnosis time by 50%+ (from ~30 min to < 15 min) | The primary value prop — faster diagnosis means less revenue loss and fewer SLA breaches on business-critical services |
| **Mean time to correlate (MTTC)** | From ~15 min (grep-based) to < 1 min (single trace ID search) | The biggest MTTR bottleneck is finding which logs belong to the failed request |
| **Adoption** | Integrated into 3+ production business services within 3 months | Validates that the library solves real problems without excessive onboarding cost |
| **Instrumentation overhead** | < 1ms added latency per request (p99) | Business services cannot tolerate tracing that degrades transaction performance |
| **Event loss rate** | < 0.01% under normal load (non-backpressure conditions) | Missing events during an incident defeats the purpose |
| **Zero-config adoption** | > 80% of adopting teams use default configuration only | Validates the "zero config" design principle — complexity kills adoption |
| **Incident resolution confidence** | Engineers can identify root cause from trace-log output alone in > 70% of incidents | Measures whether the structured output contains enough business context to be actionable |
