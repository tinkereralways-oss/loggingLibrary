# trace-log Discovery Prompt

Run this prompt against a business microservice codebase to generate a tailored integration plan. Copy the entire prompt below into Claude Code (or any AI assistant with codebase access) while working in the target service's repository.

---

## The Prompt

```
You are analyzing a microservice to plan integration with the trace-log library. Before
scanning the codebase, understand what the library does and does not do automatically:

## trace-log Library Capabilities

### What is auto-instrumented (zero code changes after adding the dependency):
- REST endpoints (@RestController, @Controller): TraceHandlerInterceptor starts a trace
  on every inbound HTTP request, logs request.received and request.completed events,
  sets X-Trace-Id response header, populates SLF4J MDC with traceId, cleans up ThreadLocal.
- Kafka consumers (@KafkaListener): TraceKafkaInterceptor starts a trace per message,
  extracts X-Trace-Id and W3C traceparent from message headers for correlation.
- JMS/IBM MQ listeners (@JmsListener): TraceJmsListenerAspect starts a trace per message,
  extracts X-Trace-Id and traceparent from JMS message properties.
- @Scheduled tasks: TraceScheduledAspect wraps each invocation in a trace with
  entry point "SCHEDULED ClassName.methodName".
- @Async methods: TraceTaskDecorator propagates the parent trace context to the async thread.
- MDC: trace ID is automatically set under key "traceId" for all standard log statements.

### What requires code changes by the adopting engineer:
- Business events: TraceLog.event("order.created").data("orderId", id).metric("total", amount).info()
  — the library does NOT know your domain; engineers add events at business decision points.
- Trace metadata: TraceLog.metadata("userId", userId) — attach request-scoped context
  to the entire trace.
- Timed operations: try (var timer = TraceLog.timedEvent("db.query")) { ... }
  — wraps a block and records durationMs automatically.
- Thread handoffs (Kafka listener → processing pool, or any executor.submit()):
  The auto-traced Kafka/JMS trace ends on the listener thread. If processing continues
  on another thread, the engineer must:
  1. Grab the trace ID: String originatingTraceId = TraceLog.currentTraceId().orElse(null);
  2. In the processing thread: try (var trace = TraceLog.startManualTrace("processing.name")) {
     TraceLog.metadata("originatingTraceId", originatingTraceId); ... }
- Outbound trace propagation: The library does NOT auto-add trace IDs to outgoing
  HTTP/Kafka/JMS calls. Engineers must add:
  TraceLog.currentTraceId().ifPresent(id -> request.getHeaders().set("X-Trace-Id", id));

### Sampling:
- Configurable via tracelog.sampling.rate (0.0 to 1.0, default 1.0 = sample everything).
- Error traces (TraceStatus.ERROR) are ALWAYS captured regardless of sampling rate.
- Custom SamplingStrategy bean can override the default rate-based strategy.

### What the library does NOT do:
- No automatic outbound header propagation (engineer must add trace ID to outgoing calls)
- No reactive/WebFlux support (ThreadLocal-based, requires thread-per-request model)
- No gRPC interceptor
- No automatic database query tracing (engineer wraps with timedEvent)
- No distributed tracing UI (produces JSON to stdout; complements OTEL, not replaces it)

---

Now analyze this microservice codebase and produce a trace-log integration plan. For each
section, cite specific files, classes, and line numbers from THIS repo.

## 1. Service Identity

- What is the service name (from application.yml/properties, pom.xml, or build.gradle)?
- What Spring Boot version is it running?
- What Java version?

## 2. Inflow Inventory

Find every entry point where work begins. For each one, identify:

### REST Endpoints
- List every @RestController and @Controller class
- For each, list the endpoints (method + path)
- Which ones are business-critical (payments, orders, accounts) vs operational (health, metrics)?
- Are there any filters or interceptors already in place that touch request/response?

### Kafka Consumers
- List every @KafkaListener method
- What topics does each consume?
- Does the listener process on the Kafka thread, or hand off to another thread/executor?
- Are there any existing header extraction patterns (trace IDs, correlation IDs)?

### JMS / IBM MQ Listeners
- List every @JmsListener method
- What queues/topics does each listen to?
- Any existing message property extraction for correlation?

### Scheduled Tasks
- List every @Scheduled method
- What does each one do (cleanup, sync, polling, batch)?
- How long do they typically run?

### Other Entry Points
- CommandLineRunner / ApplicationRunner implementations
- Custom message handlers (raw socket, proprietary protocols)
- gRPC endpoints
- Anything else that starts a unit of work

## 3. Async and Threading Model

- Are @Async methods used? List them.
- Are there custom ExecutorService / ThreadPoolTaskExecutor beans? List them.
- Does any code hand off work from one thread to another (e.g., Kafka listener → processing pool)?
  For each case, identify:
  - The class/method where the handoff happens
  - What data is passed to the child thread
  - Whether any existing correlation ID is carried over
- Are CompletableFuture chains used? Where?

## 4. Cross-Service Communication

### Outbound REST calls
- List every RestTemplate, WebClient, RestClient, or Feign client usage
- Is there any existing pattern for propagating correlation/trace IDs in outbound headers?

### Outbound Kafka messages
- List every KafkaTemplate.send() call
- Are trace/correlation IDs added to message headers?

### Outbound JMS/MQ messages
- List every JmsTemplate usage
- Are trace/correlation IDs set as message properties?

### Outbound gRPC calls
- List any gRPC stubs or channel usage

## 5. Existing Observability

- Is there any existing tracing library (OpenTelemetry, Sleuth, Zipkin, Brave)? What version?
- Is MDC used anywhere? What keys are set?
- Are there existing correlation ID patterns (custom headers, request attributes)?
- Is there a log aggregator in use (Splunk, DataDog, ELK, Grafana Loki)?
- Are there existing structured logging patterns (JSON logging, custom log formats)?

## 6. Error Handling Patterns

- Is there a @ControllerAdvice / @ExceptionHandler? What does it catch?
- Are there try-catch blocks in service/repository layers that swallow exceptions silently?
- Are there retry mechanisms (Spring Retry, Resilience4j, manual loops)?
- What exceptions represent business errors vs infrastructure errors?

## 7. Database and External Service Calls

- List every repository interface and what database it connects to
- List every external HTTP call (REST clients to other services)
- List every cache interaction (Redis, Caffeine, etc.)
- Which of these are latency-sensitive and would benefit from timed events?

---

## Integration Plan Output

Based on the analysis above, produce:

### A. Dependency Addition
Show the exact dependency to add to this project's build file.

### B. Configuration
Recommend application.yml/properties settings based on:
- The service name found
- Expected request volume (estimate from endpoint count and usage patterns)
- Whether sampling is needed
- Pretty-print for dev profiles

### C. Auto-Instrumented (Zero Code Change)
List every inflow that trace-log will instrument automatically with no code changes:
- REST endpoints → TraceHandlerInterceptor
- Kafka listeners → TraceKafkaInterceptor
- JMS listeners → TraceJmsListenerAspect
- @Scheduled tasks → TraceScheduledAspect

### D. Recommended TraceLog.event() Additions
For each service/repository class with business logic, suggest specific events:

Format each suggestion as:
```java
// In {ClassName}.{methodName}() at line {N}
TraceLog.event("{domain}.{action}")
    .data("{key}", {value})
    .metric("{key}", {value})
    .info();
```

Prioritize:
1. Business decisions (approved/rejected/escalated)
2. External service calls (with latency via timedEvent)
3. Database operations (with latency via timedEvent)
4. Error/exception points
5. Cache hits/misses

### E. Metadata Recommendations
What trace-level metadata should be attached? Map to actual fields in this codebase:
```java
TraceLog.metadata("{key}", {where to get the value});
```

### F. Thread Handoff Points
For each case where work moves to another thread:
1. Show the exact code change needed in the handing-off method (grab trace ID)
2. Show the exact code change needed in the receiving method (startManualTrace)
3. Note whether error-aware tracing is needed (for sampling guarantees)

### G. Outbound Propagation
For each outbound call to another service, show the exact code to propagate the trace ID:
```java
TraceLog.currentTraceId().ifPresent(id -> /* set header/property */);
```

### H. Existing Observability Compatibility
- If OpenTelemetry is present: trace-log will auto-detect traceparent headers, no changes needed
- If MDC is used: trace-log sets MDC key "traceId" automatically, note any conflicts
- If a correlation ID header already exists: recommend setting tracelog.trace-id.propagation-header to match

### I. Migration Risk Assessment
- Will trace-log conflict with any existing interceptors/filters?
- Are there any classpath conflicts (Jackson version, Spring Boot version)?
- Are there any thread pool configurations that might need TraceableExecutorService wrapping?
- Estimated effort: how many files need changes vs how many are auto-instrumented?
```

---

## Usage

```bash
# Navigate to the target microservice repo
cd /path/to/payment-service

# Run the prompt with Claude Code
# Paste the prompt above, or reference this file:
# "Run the discovery prompt from /path/to/trace-log/docs/DISCOVERY_PROMPT.md against this codebase"
```

The output gives you a complete, file-specific integration plan — not generic advice, but exact classes, line numbers, and code snippets tailored to that service.
