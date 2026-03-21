# trace-log Integration Guide

## Spring Boot Web (REST APIs)

### Setup

```xml
<dependency>
    <groupId>com.tracing</groupId>
    <artifactId>trace-log-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Tracing is automatic for all `@RestController` and `@Controller` endpoints.

### What gets captured automatically

- `request.received`: HTTP method, URI, query string, remote address
- `request.completed`: HTTP status code
- `request.exception`: Full exception details (if thrown)
- Entry point: `REST {METHOD} {URI} (Controller.method)`
- Trace ID: if W3C `traceparent` header is present, the OTEL trace ID is reused; otherwise a new ULID/UUID is generated
- Parent trace: from `X-Trace-Id` request header (stored as `parentTraceId`)
- Trace ID: set in `X-Trace-Id` response header and SLF4J MDC

---

## Spring Kafka

### Setup

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

The `TraceKafkaInterceptor` bean is auto-registered when `spring-kafka` is on the classpath.

### Register the interceptor with your listener container

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
        ConsumerFactory<String, String> consumerFactory,
        TraceKafkaInterceptor<String, String> traceInterceptor) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(consumerFactory);
    factory.setRecordInterceptor(traceInterceptor);
    return factory;
}
```

### What gets captured automatically

- `kafka.message.received`: topic, partition, offset, key
- `kafka.message.processed`: on successful processing
- Entry point: `KAFKA {topic}`
- Trace ID: if W3C `traceparent` message header is present, the OTEL trace ID is reused; otherwise a new ULID/UUID is generated
- Parent trace: from `X-Trace-Id` message header (stored as `parentTraceId`)

### Propagating trace to outgoing messages

```java
TraceLog.currentTraceId().ifPresent(id ->
    record.headers().add("X-Trace-Id", id.getBytes(StandardCharsets.UTF_8)));
```

---

## IBM MQ / JMS

### Setup

```xml
<!-- IBM MQ -->
<dependency>
    <groupId>com.ibm.mq</groupId>
    <artifactId>mq-jms-spring-boot-starter</artifactId>
    <version>3.2.4</version>
</dependency>

<!-- OR ActiveMQ -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-activemq</artifactId>
</dependency>

<!-- AOP required for JMS interception -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

Tracing is automatic for all `@JmsListener` methods.

### What gets captured automatically

- `jms.message.received`: destination, messageId
- `jms.message.processed`: on successful processing
- `jms.message.failed`: with exception on error
- Entry point: `JMS {destination} (ClassName.methodName)`
- Trace ID: if W3C `traceparent` JMS property is present, the OTEL trace ID is reused; otherwise a new ULID/UUID is generated
- Parent trace: from `X-Trace-Id` JMS string property (stored as `parentTraceId`)

### Propagating trace to outgoing messages

```java
jmsTemplate.convertAndSend("ORDER.QUEUE", payload, message -> {
    TraceLog.currentTraceId().ifPresent(id -> {
        try {
            message.setStringProperty("X-Trace-Id", id);
        } catch (JMSException e) {
            // handle
        }
    });
    return message;
});
```

---

## Scheduled Tasks

### Setup

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

Enable scheduling in your app:

```java
@SpringBootApplication
@EnableScheduling
public class MyApp { }
```

Tracing is automatic for all `@Scheduled` methods.

### What gets captured automatically

- `scheduled.started`: at method entry
- `scheduled.completed`: on successful return
- `scheduled.failed`: with exception on error
- Entry point: `SCHEDULED ClassName.methodName`

---

## Async Methods

### @Async

Automatic. The starter registers a `TaskDecorator` that propagates trace context to `@Async` methods.

```java
@Async
public CompletableFuture<Result> processAsync(String input) {
    // TraceLog.event() works here — same trace context
    TraceLog.event("async.processing").data("input", input).info();
    return CompletableFuture.completedFuture(result);
}
```

### Custom Thread Pools

Wrap your `ExecutorService`:

```java
@Bean
public ExecutorService tracedExecutor(TraceTaskDecorator decorator,
                                      TraceContextManager contextManager) {
    ExecutorService pool = Executors.newFixedThreadPool(10);
    return new TraceableExecutorService(pool, decorator, contextManager);
}
```

---

## OpenTelemetry (OTEL) Integration

trace-log automatically detects the W3C `traceparent` header and reuses the OTEL trace ID. No additional configuration is needed.

### How it works

When an incoming request (HTTP, Kafka, or JMS) carries a W3C `traceparent` header:

```
traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
                 └──────── trace ID ────────┘
```

trace-log extracts the 32-character hex trace ID and uses it as its own `traceId`. This means:
- trace-log JSON output and OTEL spans share the same trace ID
- You can correlate both in your observability backend (DataDog, Grafana, etc.) with a single ID
- No manual bridging or configuration required

### Fallback behavior

| Scenario | `traceId` in output |
|----------|---------------------|
| `traceparent` header present | OTEL's 32-char hex trace ID |
| Only `X-Trace-Id` header present | New ULID/UUID (custom header stored as `parentTraceId`) |
| No trace headers | New ULID/UUID |

### Validation

The `traceparent` header is ignored if:
- It is malformed (fewer than 4 dash-separated parts)
- The trace ID is not exactly 32 hex characters
- The trace ID is all zeros (`00000000000000000000000000000000`)

In these cases, trace-log falls back to generating its own ULID/UUID.

---

## SLF4J / Logback Integration

The trace ID is automatically placed in SLF4J MDC under the key `traceId`. Configure your logback pattern to include it:

```xml
<pattern>%d{ISO8601} [%thread] %-5level %logger{36} [traceId=%X{traceId}] - %msg%n</pattern>
```

This means your existing SLF4J log statements (from any library) will carry the trace ID without code changes.

---

## Multi-Service Correlation

### Service A (outgoing)

```java
// REST
restTemplate.execute(url, HttpMethod.GET, request -> {
    TraceLog.currentTraceId().ifPresent(id ->
        request.getHeaders().set("X-Trace-Id", id));
}, responseExtractor);

// WebClient
webClient.get()
    .uri(url)
    .header("X-Trace-Id", TraceLog.currentTraceId().orElse(""))
    .retrieve()
    .bodyToMono(Response.class);
```

### Service B (incoming)

The `X-Trace-Id` header is automatically extracted and stored as `parentTraceId` in the trace output. If a W3C `traceparent` header is also present (e.g., from an OTEL-instrumented upstream), Service B reuses the OTEL trace ID directly. Otherwise, it generates its own trace ID and links back to Service A via `parentTraceId`.

### Querying

Search your log aggregator for a trace ID to see:
- The trace in Service A (where `traceId` matches)
- The trace in Service B (where `parentTraceId` matches)

---

## Testing

### Disable in tests

```properties
# application-test.properties
tracelog.enabled=false
```

### Verify trace output in integration tests

```java
@SpringBootTest
class OrderControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createOrder_producesTrace() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/orders", request, Map.class);

        // Trace ID in response header
        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertNotNull(traceId);
        assertEquals(26, traceId.length()); // ULID format
    }
}
```

### Unit tests with TraceLog

```java
@BeforeEach
void setUp() {
    var cm = new TraceContextManager(new UlidGenerator(), "test", 1000);
    var bm = new BufferManager(new TestSink(), BufferConfig.defaults());
    TraceLog.initialize(cm, bm);
}

@AfterEach
void tearDown() {
    TraceLog.reset();
}
```
