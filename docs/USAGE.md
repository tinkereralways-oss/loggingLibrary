# trace-log Usage Guide

Practical examples for every feature of the trace-log library. All examples assume the Spring Boot starter is on your classpath — traces are started automatically for REST, Kafka, JMS, and `@Scheduled` inflows.

---

## Table of Contents

- [Getting Started](#getting-started)
- [Logging Events](#logging-events)
- [Timed Operations](#timed-operations)
- [Trace-Level Metadata](#trace-level-metadata)
- [Error Handling](#error-handling)
- [Manual Traces](#manual-traces)
- [Async and Thread Pool Propagation](#async-and-thread-pool-propagation)
- [Cross-Service Correlation](#cross-service-correlation)
- [OpenTelemetry Integration](#opentelemetry-integration)
- [Custom Sinks](#custom-sinks)
- [Real-World Patterns](#real-world-patterns)
- [Running with Docker](#running-with-docker)
- [Testing](#testing)

---

## Getting Started

### Add the dependency

```xml
<dependency>
    <groupId>com.tracing</groupId>
    <artifactId>trace-log-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

That's it. No configuration files, no annotations, no bean definitions. The starter auto-configures everything.

### Your first traced endpoint

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/{id}")
    public User getUser(@PathVariable String id) {
        TraceLog.event("user.lookup")
            .data("userId", id)
            .info();

        User user = userService.findById(id);

        TraceLog.event("user.found")
            .data("userId", id)
            .data("email", user.getEmail())
            .info();

        return user;
    }
}
```

The interceptor automatically starts a trace when the request arrives and flushes the completed trace (with all your events) as a single JSON document when the request ends.

---

## Logging Events

### Simple event

```java
TraceLog.event("order.received").info();
```

### Event with structured data

```java
TraceLog.event("order.received")
    .data("orderId", "ORD-123")
    .data("userId", "u-42")
    .data("channel", "web")
    .info();
```

### Event with business metrics

```java
TraceLog.event("payment.processed")
    .data("orderId", order.getId())
    .data("paymentMethod", "visa")
    .metric("amount", 149.99)
    .metric("itemCount", 3)
    .info();
```

### Event with a message

```java
TraceLog.event("cache.miss")
    .data("key", cacheKey)
    .message("Falling back to database query")
    .info();
```

### Event with all options

```java
TraceLog.event("inventory.reserved")
    .data("sku", "WIDGET-42")
    .data("warehouse", "US-EAST-1")
    .metric("quantity", 5)
    .metric("remainingStock", 142)
    .message("Reserved from primary warehouse")
    .info();
```

### Severity levels

```java
TraceLog.event("startup.check").trace();           // TRACE
TraceLog.event("config.loaded").debug();            // DEBUG
TraceLog.event("order.created").info();             // INFO
TraceLog.event("inventory.low").warn();             // WARN
TraceLog.event("payment.declined").error();         // ERROR
TraceLog.event("db.connection.lost").error(ex);     // ERROR with exception
```

### Logging from anywhere on the call stack

No need to pass context objects. `TraceLog` uses ThreadLocal — call it from your controller, service, repository, or utility class:

```java
@RestController
public class OrderController {
    @PostMapping("/api/orders")
    public Order create(@RequestBody OrderRequest req) {
        TraceLog.event("controller.received").data("userId", req.getUserId()).info();
        return orderService.create(req);  // no trace object passed
    }
}

@Service
public class OrderService {
    public Order create(OrderRequest req) {
        TraceLog.event("service.validating").data("itemCount", req.getItems().size()).info();
        inventoryService.reserve(req.getItems());  // still no object passing
        return orderRepository.save(req);
    }
}

@Repository
public class OrderRepository {
    public Order save(OrderRequest req) {
        TraceLog.event("repo.saving").data("table", "orders").info();
        // All three events end up in the same trace document
        return jdbcTemplate.insert(req);
    }
}
```

---

## Timed Operations

### Basic timed event

```java
try (var timer = TraceLog.timedEvent("db.query")) {
    results = repository.findByStatus("ACTIVE");
    timer.data("resultCount", results.size());
}
// Output includes: "durationMs": 23
```

### Timed event with metrics

```java
try (var timer = TraceLog.timedEvent("payment.charge")) {
    timer.data("paymentMethod", "visa");
    timer.metric("amount", 149.99);

    String txnId = paymentGateway.charge(card, amount);

    timer.data("transactionId", txnId);
    timer.metric("gatewayFee", 2.50);
}
```

### Timed event with custom severity

```java
try (var timer = TraceLog.timedEvent("external.api.call").severity(Severity.WARN)) {
    response = httpClient.get("https://partner-api.example.com/rates");
    timer.data("statusCode", response.statusCode());
    timer.metric("responseSize", response.body().length());
}
```

### Nested timed events

```java
try (var outer = TraceLog.timedEvent("order.fulfillment")) {
    outer.data("orderId", orderId);

    try (var inner1 = TraceLog.timedEvent("inventory.reserve")) {
        inner1.data("warehouse", "US-EAST");
        warehouseClient.reserve(items);
    }

    try (var inner2 = TraceLog.timedEvent("shipping.schedule")) {
        inner2.data("carrier", "FEDEX");
        shippingClient.createLabel(address);
    }

    outer.metric("totalItems", items.size());
}
// Each timed event gets its own durationMs in the output
```

### Timing a database operation

```java
public Map<String, Object> save(Map<String, Object> order) {
    String orderId = "ORD-" + System.currentTimeMillis();
    order.put("orderId", orderId);

    try (var timer = TraceLog.timedEvent("db.insert")) {
        timer.data("table", "orders");
        timer.data("orderId", orderId);
        jdbcTemplate.update("INSERT INTO orders ...", order);
    }

    return order;
}

public Optional<Order> findById(String orderId) {
    try (var timer = TraceLog.timedEvent("db.query")) {
        timer.data("table", "orders");
        timer.data("orderId", orderId);
        return jdbcTemplate.queryForObject("SELECT * FROM orders WHERE id = ?", orderId);
    }
}
```

---

## Trace-Level Metadata

Metadata is attached to the entire trace, not to individual events. Use it for contextual information that applies to the whole request.

```java
@PostMapping("/api/orders")
public Order create(@RequestBody OrderRequest req, HttpServletRequest httpReq) {
    // These appear at the trace level in the JSON output
    TraceLog.metadata("userId", req.getUserId());
    TraceLog.metadata("tenantId", req.getTenantId());
    TraceLog.metadata("region", httpReq.getHeader("X-Region"));
    TraceLog.metadata("apiVersion", "v2");

    return orderService.create(req);
}
```

Output:

```json
{
  "traceId": "01KM76KPRQ9CQYM8Y9VHRS5WTK",
  "metadata": {
    "userId": "u-42",
    "tenantId": "t-100",
    "region": "us-east-1",
    "apiVersion": "v2"
  },
  "events": [ ... ]
}
```

---

## Error Handling

### Error with exception details

```java
try {
    paymentGateway.charge(card, amount);
} catch (PaymentException e) {
    TraceLog.event("payment.failed")
        .data("orderId", orderId)
        .data("paymentMethod", card.getType())
        .metric("amount", amount)
        .error(e);  // captures exception type, message, and truncated stack trace
    throw e;
}
```

### Error without exception

```java
if (!inventory.isAvailable(sku)) {
    TraceLog.event("order.rejected")
        .data("sku", sku)
        .data("reason", "out_of_stock")
        .error();
}
```

### Warning for degraded scenarios

```java
int retries = 0;
while (retries < 3) {
    try {
        return externalApi.call(request);
    } catch (TimeoutException e) {
        retries++;
        TraceLog.event("external.api.retry")
            .data("attempt", retries)
            .data("endpoint", "/rates")
            .message("Retrying after timeout")
            .warn();
    }
}
```

### Full controller error handling pattern

```java
@PostMapping("/api/orders")
public ResponseEntity<?> create(@RequestBody OrderRequest req) {
    TraceLog.metadata("userId", req.getUserId());

    TraceLog.event("controller.create_order")
        .data("userId", req.getUserId())
        .data("itemCount", req.getItems().size())
        .info();

    try {
        Order order = orderService.create(req);

        TraceLog.event("controller.order_success")
            .data("orderId", order.getId())
            .info();

        return ResponseEntity.ok(order);

    } catch (InsufficientInventoryException e) {
        TraceLog.event("controller.order_rejected")
            .data("reason", "inventory_unavailable")
            .warn();
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));

    } catch (PaymentException e) {
        TraceLog.event("controller.order_failed")
            .data("reason", "payment_declined")
            .error(e);
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));

    } catch (RuntimeException e) {
        TraceLog.event("controller.unexpected_error")
            .error(e);
        return ResponseEntity.internalServerError().body(Map.of("error", "Internal error"));
    }
}
```

---

## Manual Traces

For code paths not covered by auto-instrumentation (batch jobs, CLI tools, startup tasks, message handlers without `@JmsListener`).

### Batch processing

```java
public void importCsvFile(Path file) {
    try (var trace = TraceLog.startManualTrace("batch.csv_import")) {
        TraceLog.metadata("fileName", file.getFileName().toString());

        TraceLog.event("import.started")
            .data("filePath", file.toString())
            .info();

        List<String> lines = Files.readAllLines(file);
        int successCount = 0;
        int errorCount = 0;

        for (String line : lines) {
            try {
                processLine(line);
                successCount++;
            } catch (Exception e) {
                errorCount++;
                TraceLog.event("import.line_error")
                    .data("line", line)
                    .error(e);
            }
        }

        TraceLog.event("import.completed")
            .metric("totalLines", lines.size())
            .metric("successCount", successCount)
            .metric("errorCount", errorCount)
            .info();
    }
    // Trace auto-flushed on close
}
```

### Application startup task

```java
@Component
public class StartupInitializer implements CommandLineRunner {

    @Override
    public void run(String... args) {
        try (var trace = TraceLog.startManualTrace("app.startup_init")) {

            try (var timer = TraceLog.timedEvent("cache.warmup")) {
                cacheService.warmup();
                timer.metric("entriesLoaded", cacheService.size());
            }

            try (var timer = TraceLog.timedEvent("schema.validation")) {
                schemaValidator.validate();
                timer.data("result", "passed");
            }

            TraceLog.event("startup.ready")
                .message("Application initialization complete")
                .info();
        }
    }
}
```

### Event-driven processing without annotations

```java
public void handleMessage(String topic, byte[] payload) {
    try (var trace = TraceLog.startManualTrace("custom.message_handler")) {
        TraceLog.metadata("topic", topic);

        TraceLog.event("message.received")
            .data("topic", topic)
            .metric("payloadSize", payload.length)
            .info();

        MyEvent event = deserialize(payload);
        processEvent(event);

        TraceLog.event("message.processed")
            .data("eventType", event.getType())
            .info();
    }
}
```

---

## Async and Thread Pool Propagation

### @Async methods (automatic)

The starter automatically propagates trace context to `@Async` methods:

```java
@Service
public class NotificationService {

    @Async
    public CompletableFuture<Void> sendOrderConfirmation(String orderId, String email) {
        // This runs on an async thread, but the trace context is preserved
        TraceLog.event("notification.sending")
            .data("orderId", orderId)
            .data("email", email)
            .data("type", "order_confirmation")
            .info();

        emailClient.send(email, buildTemplate(orderId));

        TraceLog.event("notification.sent")
            .data("orderId", orderId)
            .info();

        return CompletableFuture.completedFuture(null);
    }
}
```

### Custom thread pools

Wrap your `ExecutorService` with `TraceableExecutorService` to propagate context:

```java
@Configuration
public class ThreadPoolConfig {

    @Bean
    public ExecutorService tracedExecutor(TraceTaskDecorator decorator,
                                          TraceContextManager contextManager) {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        return new TraceableExecutorService(pool, decorator, contextManager);
    }
}
```

Then use it normally — trace context propagates automatically:

```java
@Service
public class ParallelProcessor {

    private final ExecutorService tracedExecutor;

    public void processInParallel(List<Item> items) {
        TraceLog.event("parallel.started")
            .metric("itemCount", items.size())
            .info();

        List<Future<?>> futures = items.stream()
            .map(item -> tracedExecutor.submit(() -> {
                // Runs on pool thread, but events attach to the original trace
                TraceLog.event("parallel.item_processed")
                    .data("itemId", item.getId())
                    .info();
                processItem(item);
            }))
            .toList();

        // Wait for all to complete
        for (Future<?> f : futures) {
            f.get();
        }

        TraceLog.event("parallel.completed").info();
    }
}
```

### CompletableFuture with trace propagation

```java
public void processOrder(Order order) {
    TraceLog.event("order.processing_started").data("orderId", order.getId()).info();

    // Capture the decorator for manual propagation
    TraceTaskDecorator decorator = new TraceTaskDecorator(contextManager);

    CompletableFuture.runAsync(decorator.decorate(() -> {
        TraceLog.event("async.inventory_check")
            .data("orderId", order.getId())
            .info();
        inventoryService.reserve(order.getItems());
    })).thenRunAsync(decorator.decorate(() -> {
        TraceLog.event("async.payment_charge")
            .data("orderId", order.getId())
            .info();
        paymentService.charge(order);
    })).join();
}
```

---

## Cross-Service Correlation

### Propagating trace ID to downstream REST calls

```java
// RestTemplate
restTemplate.execute(url, HttpMethod.POST, request -> {
    TraceLog.currentTraceId().ifPresent(id ->
        request.getHeaders().set("X-Trace-Id", id));
    // write body...
}, responseExtractor);

// WebClient
webClient.post()
    .uri("https://inventory-service/api/reserve")
    .header("X-Trace-Id", TraceLog.currentTraceId().orElse(""))
    .bodyValue(reserveRequest)
    .retrieve()
    .bodyToMono(ReserveResponse.class);

// RestClient (Spring 6.1+)
restClient.post()
    .uri("https://payment-service/api/charge")
    .header("X-Trace-Id", TraceLog.currentTraceId().orElse(""))
    .body(chargeRequest)
    .retrieve()
    .body(ChargeResponse.class);
```

### Propagating trace ID to Kafka messages

```java
ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
TraceLog.currentTraceId().ifPresent(id ->
    record.headers().add("X-Trace-Id", id.getBytes(StandardCharsets.UTF_8)));
kafkaTemplate.send(record);
```

### Propagating trace ID to JMS messages

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

### How correlation works across services

```
Service A (order-service)                    Service B (inventory-service)
─────────────────────────                    ──────────────────────────────
POST /api/orders                             POST /api/reserve
  traceId: "01KM76KPRQ..."                    traceId: "01KM77ABC..."
  events:                                      parentTraceId: "01KM76KPRQ..."
    - order.received                           events:
    - inventory.check  ──── X-Trace-Id ────>     - request.received
    - order.created                              - stock.reserved
                                                 - request.completed
```

Search your log aggregator for `01KM76KPRQ...` to find Service A's trace. Search `parentTraceId: "01KM76KPRQ..."` to find all downstream traces.

---

## OpenTelemetry Integration

If your services already use OpenTelemetry, trace-log automatically detects the W3C `traceparent` header and reuses the same trace ID. No configuration needed.

### How it works

When a request arrives with:

```
traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
```

trace-log extracts `0af7651916cd43dd8448eb211c80319c` and uses it as its own `traceId`. Your trace-log JSON and OTEL spans share the same ID — correlate them with a single query in DataDog, Grafana, Splunk, etc.

### Fallback behavior

| Incoming headers | `traceId` in trace-log output |
|------------------|-------------------------------|
| `traceparent` present | OTEL's 32-char hex trace ID |
| Only `X-Trace-Id` present | New ULID/UUID (custom header stored as `parentTraceId`) |
| Neither present | New ULID/UUID |

### Testing with curl

```bash
# With OTEL traceparent
curl -X POST http://localhost:8085/api/orders \
  -H "Content-Type: application/json" \
  -H "traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01" \
  -d '{"userId":"u-42","items":["ITEM-A"],"paymentMethod":"visa","total":49.99}'

# Output will have: "traceId": "0af7651916cd43dd8448eb211c80319c"
```

### Mixed environment (some services with OTEL, some without)

trace-log handles this gracefully. Services with OTEL propagate `traceparent` — trace-log reuses the ID. Services without OTEL propagate `X-Trace-Id` — trace-log generates its own ID and links via `parentTraceId`. Both patterns work in the same request chain.

---

## Custom Sinks

### Send traces to an HTTP endpoint

```java
public class HttpSink implements LogSink {

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String endpoint;

    public HttpSink(String endpoint, ObjectMapper mapper) {
        this.client = HttpClient.newHttpClient();
        this.mapper = mapper;
        this.endpoint = endpoint;
    }

    @Override
    public void write(CompletedTrace trace) {
        try {
            String json = mapper.writeValueAsString(trace);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // Log or handle — failed writes are retried once by BufferManager
        }
    }

    @Override
    public void flush() { }

    @Override
    public void close() { }
}
```

### Send traces to Kafka

```java
public class KafkaSink implements LogSink {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper;
    private final String topic;

    public KafkaSink(KafkaTemplate<String, String> kafkaTemplate,
                     ObjectMapper mapper, String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = mapper;
        this.topic = topic;
    }

    @Override
    public void write(CompletedTrace trace) {
        try {
            String json = mapper.writeValueAsString(trace);
            kafkaTemplate.send(topic, trace.getTraceId(), json);
        } catch (Exception e) {
            // handle
        }
    }

    @Override
    public void flush() { }

    @Override
    public void close() { }
}
```

### Write to multiple destinations with CompositeSink

```java
@Bean
public LogSink logSink(ObjectMapper mapper,
                       KafkaTemplate<String, String> kafkaTemplate) {
    return new CompositeSink(List.of(
        new JsonStdoutSink(mapper, false, System.out),  // local logs
        new HttpSink("https://logs.example.com/ingest", mapper),  // observability platform
        new KafkaSink(kafkaTemplate, mapper, "trace-logs")  // long-term storage
    ));
}
```

`CompositeSink` catches exceptions from each sink independently — one failing sink doesn't affect the others.

### Register your custom sink

```java
@Configuration
public class TraceSinkConfig {

    @Bean
    public LogSink logSink(ObjectMapper mapper) {
        return new HttpSink("https://logs.example.com/ingest", mapper);
    }
}
```

The `@ConditionalOnMissingBean` on the default sink means your bean automatically replaces it.

---

## Real-World Patterns

### E-commerce order flow

```java
@Service
public class OrderService {

    public Order create(OrderRequest req) {
        // Validate
        TraceLog.event("order.validation")
            .data("userId", req.getUserId())
            .data("itemCount", req.getItems().size())
            .metric("orderTotal", req.getTotal())
            .info();

        if (req.getTotal() > 10000) {
            TraceLog.event("order.high_value_flag")
                .data("userId", req.getUserId())
                .metric("amount", req.getTotal())
                .message("High-value order flagged for review")
                .warn();
        }

        // Check inventory
        try (var timer = TraceLog.timedEvent("inventory.check")) {
            boolean available = inventoryService.checkAll(req.getItems());
            timer.data("available", available);
            if (!available) {
                TraceLog.event("order.rejected")
                    .data("reason", "inventory_unavailable")
                    .warn();
                throw new InsufficientInventoryException();
            }
        }

        // Charge payment
        try (var timer = TraceLog.timedEvent("payment.charge")) {
            timer.data("paymentMethod", req.getPaymentMethod());
            timer.metric("amount", req.getTotal());
            String txnId = paymentService.charge(req.getPaymentMethod(), req.getTotal());
            timer.data("transactionId", txnId);
        }

        // Save order
        try (var timer = TraceLog.timedEvent("db.save_order")) {
            Order order = orderRepository.save(req.toOrder());
            timer.data("orderId", order.getId());
            return order;
        }
    }
}
```

### Scheduled cleanup job

```java
@Component
public class OrderCleanupTask {

    @Scheduled(fixedRate = 60_000)
    public void cleanupExpiredOrders() {
        // Trace is auto-started by @Scheduled interceptor

        TraceLog.event("cleanup.scan")
            .message("Scanning for expired orders")
            .info();

        List<Order> expired = orderRepository.findExpired();

        for (Order order : expired) {
            try (var timer = TraceLog.timedEvent("cleanup.expire_order")) {
                timer.data("orderId", order.getId());
                timer.data("createdAt", order.getCreatedAt().toString());
                orderRepository.markExpired(order.getId());
            }
        }

        TraceLog.event("cleanup.completed")
            .metric("expiredCount", expired.size())
            .info();
    }
}
```

### Kafka consumer with validation

```java
@Component
public class PaymentEventListener {

    @KafkaListener(topics = "payment-events")
    public void handlePaymentEvent(ConsumerRecord<String, String> record) {
        // Trace is auto-started by Kafka interceptor

        PaymentEvent event = deserialize(record.value());

        TraceLog.event("payment_event.received")
            .data("eventType", event.getType())
            .data("orderId", event.getOrderId())
            .metric("amount", event.getAmount())
            .info();

        if (!isValid(event)) {
            TraceLog.event("payment_event.invalid")
                .data("eventType", event.getType())
                .data("reason", "failed_schema_validation")
                .warn();
            return;
        }

        try (var timer = TraceLog.timedEvent("payment_event.process")) {
            timer.data("eventType", event.getType());
            orderService.updatePaymentStatus(event);
        }

        TraceLog.event("payment_event.completed")
            .data("orderId", event.getOrderId())
            .info();
    }
}
```

### Multi-step API orchestration

```java
@Service
public class OnboardingService {

    public OnboardingResult onboardCustomer(OnboardingRequest req) {
        TraceLog.metadata("customerId", req.getCustomerId());
        TraceLog.metadata("plan", req.getPlan());

        // Step 1: KYC verification
        try (var timer = TraceLog.timedEvent("onboarding.kyc_check")) {
            timer.data("documentType", req.getDocumentType());
            KycResult kyc = kycService.verify(req);
            timer.data("kycStatus", kyc.getStatus());
            if (!kyc.isPassed()) {
                TraceLog.event("onboarding.kyc_failed")
                    .data("reason", kyc.getRejectionReason())
                    .error();
                return OnboardingResult.rejected(kyc.getRejectionReason());
            }
        }

        // Step 2: Create account
        try (var timer = TraceLog.timedEvent("onboarding.create_account")) {
            Account account = accountService.create(req);
            timer.data("accountId", account.getId());
        }

        // Step 3: Provision resources
        try (var timer = TraceLog.timedEvent("onboarding.provision")) {
            timer.data("plan", req.getPlan());
            provisioningService.setup(req);
            timer.metric("resourceCount", req.getPlan().getResourceCount());
        }

        // Step 4: Send welcome email
        TraceLog.event("onboarding.welcome_email")
            .data("email", req.getEmail())
            .info();
        emailService.sendWelcome(req.getEmail());

        TraceLog.event("onboarding.completed")
            .data("customerId", req.getCustomerId())
            .message("Customer successfully onboarded")
            .info();

        return OnboardingResult.success();
    }
}
```

### Health check with metrics

```java
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        TraceLog.event("health.check")
            .metric("orderCount", orderRepository.count())
            .metric("activeConnections", dataSource.getActiveConnections())
            .metric("heapUsedMb", Runtime.getRuntime().totalMemory() / 1_048_576)
            .info();

        return Map.of(
            "status", "UP",
            "orderCount", orderRepository.count()
        );
    }
}
```

---

## Running with Docker

No Java or Maven required locally:

```bash
# Build and start
docker compose up --build

# View logs (trace JSON output)
docker compose logs -f

# Stop
docker compose down
```

### Test the running container

```bash
# Health check
curl http://localhost:8085/health

# Create an order
curl -X POST http://localhost:8085/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":"u-42","items":["ITEM-A","ITEM-B"],"paymentMethod":"visa","total":99.99}'

# Create an order with OTEL traceparent
curl -X POST http://localhost:8085/api/orders \
  -H "Content-Type: application/json" \
  -H "traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01" \
  -d '{"userId":"u-42","items":["ITEM-A"],"paymentMethod":"visa","total":49.99}'

# Create an order with upstream trace correlation
curl -X POST http://localhost:8085/api/orders \
  -H "Content-Type: application/json" \
  -H "X-Trace-Id: upstream-trace-123" \
  -d '{"userId":"u-42","items":["ITEM-A"],"paymentMethod":"visa","total":49.99}'
```

---

## Testing

### Disable tracing in test profiles

```properties
# application-test.properties
tracelog.enabled=false
```

### Verify trace ID in response headers

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createOrder_returnsTraceIdHeader() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/orders", orderRequest, Map.class);

        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertNotNull(traceId);
        assertEquals(26, traceId.length()); // ULID format
    }
}
```

### Unit test with TraceLog initialized

```java
class OrderServiceTest {

    private TraceContextManager contextManager;
    private BufferManager bufferManager;

    @BeforeEach
    void setUp() {
        contextManager = new TraceContextManager(new UlidGenerator(), "test-service", 1000);
        bufferManager = new BufferManager(new NoOpSink(), BufferConfig.defaults());
        TraceLog.initialize(contextManager, bufferManager);
    }

    @AfterEach
    void tearDown() {
        TraceLog.reset();
    }

    @Test
    void createOrder_logsEvents() {
        // Start a trace manually for the test
        contextManager.startTrace("test");

        Order order = orderService.create(validRequest);

        assertNotNull(order.getId());
        // Events are captured in the trace context — verify via custom sink if needed
    }
}
```

### Capture and assert trace output with a test sink

```java
class TestSink implements LogSink {
    final List<CompletedTrace> traces = new CopyOnWriteArrayList<>();

    @Override
    public void write(CompletedTrace trace) {
        traces.add(trace);
    }

    @Override
    public void flush() { }

    @Override
    public void close() { }
}

class OrderServiceTest {

    private TestSink testSink;

    @BeforeEach
    void setUp() {
        testSink = new TestSink();
        var cm = new TraceContextManager(new UlidGenerator(), "test", 1000);
        var bm = new BufferManager(testSink, BufferConfig.defaults());
        TraceLog.initialize(cm, bm);
    }

    @Test
    void createOrder_capturesPaymentEvent() {
        try (var trace = TraceLog.startManualTrace("test")) {
            orderService.create(validRequest);
        }

        // Wait for flush
        Thread.sleep(100);

        CompletedTrace trace = testSink.traces.get(0);
        assertTrue(trace.getEvents().stream()
            .anyMatch(e -> e.getEventName().equals("payment.charge")));
    }
}
```

---

## JSON Output Reference

Every completed trace produces one JSON document:

```json
{
  "traceId": "01KM76KPRQ9CQYM8Y9VHRS5WTK",
  "serviceName": "order-service",
  "entryPoint": "REST POST /api/orders (OrderController.create)",
  "startTime": "2026-03-20T10:15:30.123Z",
  "endTime": "2026-03-20T10:15:30.456Z",
  "durationMs": 333,
  "status": "SUCCESS",
  "parentTraceId": null,
  "metadata": {
    "userId": "u-42",
    "tenantId": "t-100"
  },
  "events": [
    {
      "eventName": "request.received",
      "timestamp": "2026-03-20T10:15:30.123Z",
      "severity": "INFO",
      "origin": "unknown",
      "dataPoints": {
        "method": "POST",
        "uri": "/api/orders",
        "remoteAddr": "192.168.1.10"
      }
    },
    {
      "eventName": "order.validation",
      "timestamp": "2026-03-20T10:15:30.130Z",
      "severity": "INFO",
      "origin": "com.example.service.OrderService.create",
      "dataPoints": {
        "userId": "u-42",
        "itemCount": 3
      },
      "metrics": {
        "orderTotal": 149.99
      }
    },
    {
      "eventName": "payment.charge",
      "timestamp": "2026-03-20T10:15:30.200Z",
      "severity": "INFO",
      "origin": "com.example.service.PaymentService.charge",
      "dataPoints": {
        "paymentMethod": "visa",
        "transactionId": "TXN-1234567890"
      },
      "metrics": {
        "amount": 149.99
      },
      "durationMs": 55
    },
    {
      "eventName": "request.completed",
      "timestamp": "2026-03-20T10:15:30.456Z",
      "severity": "INFO",
      "dataPoints": {
        "statusCode": 200
      }
    }
  ]
}
```
