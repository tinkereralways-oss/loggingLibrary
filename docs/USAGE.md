# trace-log Usage Guide

Practical examples for every feature of the trace-log library, illustrated across a **four-microservice payment ecosystem**:

| Service | Responsibility | Primary Interface |
|---------|---------------|-------------------|
| **payment-qualification** | Validates payment eligibility — account status, currency support, limits, duplicate detection | REST API (entry point), publishes to Kafka |
| **sanctions-screening** | Screens payer/payee against OFAC, EU, and UN sanctions lists; PEP checks | Kafka consumer/producer |
| **fraud-detection** | Real-time fraud scoring, velocity checks, device fingerprinting, ML model inference | Kafka consumer/producer, exposes REST API for score lookups |
| **money-settlement** | Executes the actual money movement — acquirer authorization, bank settlement, ledger posting, reconciliation | Kafka consumer, IBM MQ for legacy bank integrations |

**Typical payment flow:**
```
Client ──REST──▶ qualification ──Kafka──▶ sanctions ──Kafka──▶ fraud ──Kafka──▶ settlement ──MQ──▶ Bank
                                                                                    │
                                                                              Kafka ◀── settlement response
```

All examples assume the Spring Boot starter is on your classpath — traces are started automatically for REST, Kafka, JMS, and `@Scheduled` inflows.

---

## Table of Contents

- [Discovery — Planning Your Integration](#discovery--planning-your-integration)
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
- [Sampling](#sampling)
- [Real-World Patterns](#real-world-patterns)
- [Running with Docker](#running-with-docker)
- [Testing](#testing)

---

## Discovery — Planning Your Integration

Before adding the dependency, run the discovery skill against your target microservice to get a tailored integration plan. It scans your codebase and tells you exactly what's auto-instrumented, what code changes to make, where thread handoffs need manual traces, and what outbound calls need trace ID propagation.

### Using Claude Code

Navigate to your target service repo and run the slash command:

```
/tracelog-discover
```

Or point it at a specific path:

```
/tracelog-discover /path/to/payment-service
```

### What you get

The skill produces a file-specific integration plan with nine sections:

| Section | What it tells you |
|---------|-------------------|
| **A. Dependency** | Exact Maven/Gradle snippet for your build file |
| **B. Configuration** | Recommended `application.properties` settings based on your service's volume and profile |
| **C. Auto-Instrumented** | Every REST endpoint, Kafka listener, JMS listener, and @Scheduled task that traces automatically — zero code changes |
| **D. Events to Add** | Copy-paste `TraceLog.event()` calls for each business decision, external call, and DB operation — with file, method, and line number |
| **E. Metadata** | Which trace-level metadata to attach (userId, orderId, etc.) mapped to actual fields in your code |
| **F. Thread Handoffs** | Every `executor.submit()` or Kafka-to-pool handoff that needs a `startManualTrace` block, with exact before/after code |
| **G. Outbound Propagation** | Every RestTemplate, KafkaTemplate, JmsTemplate call that needs `X-Trace-Id` header propagation |
| **H. Compatibility** | Conflicts with existing OpenTelemetry, MDC keys, or correlation ID headers |
| **I. Risk Assessment** | Classpath conflicts, interceptor collisions, and effort estimate (files changed vs auto-instrumented) |

### Without Claude Code

The full discovery prompt is also available at [`docs/DISCOVERY_PROMPT.md`](DISCOVERY_PROMPT.md). Copy it into any AI assistant with codebase access.

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
@RequestMapping("/api/payments")
public class PaymentQualificationController {

    @PostMapping("/qualify")
    public QualificationResponse qualify(@RequestBody PaymentRequest req) {
        TraceLog.metadata("paymentId", req.getPaymentId());
        TraceLog.metadata("payerId", req.getPayerId());

        TraceLog.event("qualification.received")
            .data("payerId", req.getPayerId())
            .data("payeeId", req.getPayeeId())
            .data("currency", req.getCurrency())
            .metric("amount", req.getAmount())
            .info();

        QualificationResult result = qualificationService.evaluate(req);

        TraceLog.event("qualification.completed")
            .data("paymentId", req.getPaymentId())
            .data("decision", result.getDecision())
            .info();

        return new QualificationResponse(result);
    }
}
```

The interceptor automatically starts a trace when the request arrives and flushes the completed trace (with all your events) as a single JSON document when the request ends.

---

## Logging Events

### Simple event

```java
TraceLog.event("qualification.received").info();
```

### Event with structured data

```java
TraceLog.event("sanctions.screening_started")
    .data("payerId", "CUST-44210")
    .data("payeeId", "MERCH-8891")
    .data("listSource", "OFAC")
    .info();
```

### Event with business metrics

```java
TraceLog.event("fraud.score_computed")
    .data("paymentId", paymentId)
    .data("model", "gradient-boost-v3")
    .metric("score", 82.5)
    .metric("velocityCount", 7)
    .metric("modelLatencyMs", 14)
    .info();
```

### Event with a message

```java
TraceLog.event("fraud.score_elevated")
    .data("paymentId", paymentId)
    .metric("score", 82.5)
    .message("Score above threshold — routing to manual review queue")
    .warn();
```

### Event with all options

```java
TraceLog.event("settlement.bank_credit_posted")
    .data("paymentId", paymentId)
    .data("payeeId", payeeId)
    .data("acquirer", "jpmorgan")
    .metric("settledAmount", 1250.00)
    .metric("fxRate", 1.08)
    .message("Settled in EUR, converted from USD at ECB daily rate")
    .info();
```

### Severity levels

```java
TraceLog.event("settlement.bank_heartbeat").trace();          // TRACE
TraceLog.event("sanctions.cache_hit").debug();                 // DEBUG
TraceLog.event("qualification.approved").info();               // INFO
TraceLog.event("fraud.velocity_spike").warn();                 // WARN
TraceLog.event("settlement.acquirer_declined").error();        // ERROR
TraceLog.event("settlement.bank_connection_lost").error(ex);   // ERROR with exception
```

### Logging from anywhere on the call stack

No need to pass context objects. `TraceLog` uses ThreadLocal — call it from your controller, service, repository, or utility class:

```java
@RestController
public class PaymentQualificationController {
    @PostMapping("/api/payments/qualify")
    public QualificationResponse qualify(@RequestBody PaymentRequest req) {
        TraceLog.event("controller.qualify_received")
            .data("payerId", req.getPayerId())
            .metric("amount", req.getAmount())
            .info();
        return qualificationService.evaluate(req);  // no trace object passed
    }
}

@Service
public class QualificationService {
    public QualificationResult evaluate(PaymentRequest req) {
        TraceLog.event("service.account_status_check")
            .data("payerId", req.getPayerId())
            .info();
        accountService.validateActive(req.getPayerId());  // still no object passing
        return limitsService.checkLimits(req);
    }
}

@Repository
public class PaymentRepository {
    public void save(Payment payment) {
        TraceLog.event("repo.payment_persisted")
            .data("table", "payments")
            .data("paymentId", payment.getId())
            .info();
        // All three events end up in the same trace document
        jdbcTemplate.update("INSERT INTO payments ...", payment);
    }
}
```

---

## Timed Operations

### Basic timed event

```java
try (var timer = TraceLog.timedEvent("sanctions.ofac_lookup")) {
    ScreeningResult result = ofacClient.screen(payerName, payerCountry);
    timer.data("matchCount", result.getMatchCount());
    timer.data("listVersion", result.getListVersion());
}
// Output includes: "durationMs": 38
```

### Timed event with metrics

```java
try (var timer = TraceLog.timedEvent("fraud.model_inference")) {
    timer.data("model", "gradient-boost-v3");
    timer.data("featureSet", "realtime-v2");
    timer.metric("featureCount", 47);

    FraudScore score = fraudModel.predict(features);

    timer.data("decision", score.getDecision());
    timer.metric("score", score.getValue());
    timer.metric("confidenceInterval", score.getConfidence());
}
```

### Timed event with custom severity

```java
try (var timer = TraceLog.timedEvent("settlement.acquirer_call").severity(Severity.WARN)) {
    response = acquirerClient.authorize(request);
    timer.data("acquirer", "adyen");
    timer.data("responseCode", response.getCode());
    timer.metric("latencyMs", response.getLatency());
}
```

### Nested timed events

```java
try (var outer = TraceLog.timedEvent("qualification.full_evaluation")) {
    outer.data("paymentId", paymentId);

    try (var inner1 = TraceLog.timedEvent("qualification.account_lookup")) {
        inner1.data("payerId", payerId);
        Account account = accountService.findById(payerId);
        inner1.data("accountStatus", account.getStatus());
        inner1.data("accountTier", account.getTier());
    }

    try (var inner2 = TraceLog.timedEvent("qualification.limits_check")) {
        inner2.data("limitType", "daily");
        LimitsResult limits = limitsService.check(payerId, amount, currency);
        inner2.metric("dailyUsed", limits.getDailyUsed());
        inner2.metric("dailyMax", limits.getDailyMax());
        inner2.data("withinLimits", limits.isWithinLimits());
    }

    try (var inner3 = TraceLog.timedEvent("qualification.currency_validation")) {
        inner3.data("currency", currency);
        inner3.data("corridor", fromCountry + "->" + toCountry);
        currencyService.validateCorridor(fromCountry, toCountry, currency);
    }

    outer.data("decision", "APPROVED");
}
// Each timed event gets its own durationMs in the output
```

### Timing a database operation

```java
public void save(Payment payment) {
    try (var timer = TraceLog.timedEvent("db.insert")) {
        timer.data("table", "payments");
        timer.data("paymentId", payment.getId());
        timer.data("status", payment.getStatus());
        jdbcTemplate.update("INSERT INTO payments ...", payment);
    }
}

public Optional<Payment> findByPaymentId(String paymentId) {
    try (var timer = TraceLog.timedEvent("db.query")) {
        timer.data("table", "payments");
        timer.data("paymentId", paymentId);
        return jdbcTemplate.queryForObject(
            "SELECT * FROM payments WHERE payment_id = ?", paymentId);
    }
}
```

---

## Trace-Level Metadata

Metadata is attached to the entire trace, not to individual events. Use it for contextual information that applies to the whole request.

```java
@PostMapping("/api/payments/qualify")
public QualificationResponse qualify(@RequestBody PaymentRequest req, HttpServletRequest httpReq) {
    // These appear at the trace level in the JSON output
    TraceLog.metadata("paymentId", req.getPaymentId());
    TraceLog.metadata("payerId", req.getPayerId());
    TraceLog.metadata("payeeId", req.getPayeeId());
    TraceLog.metadata("region", httpReq.getHeader("X-Region"));
    TraceLog.metadata("idempotencyKey", req.getIdempotencyKey());

    return qualificationService.evaluate(req);
}
```

Output:

```json
{
  "traceId": "01KM76KPRQ9CQYM8Y9VHRS5WTK",
  "metadata": {
    "paymentId": "PAY-20260321-44210",
    "payerId": "CUST-44210",
    "payeeId": "MERCH-8891",
    "region": "eu-west-1",
    "idempotencyKey": "idem-abc-123"
  },
  "events": [ ... ]
}
```

---

## Error Handling

### Error with exception details

```java
try {
    acquirerClient.authorize(paymentId, amount);
} catch (AcquirerException e) {
    TraceLog.event("settlement.acquirer_authorization_failed")
        .data("paymentId", paymentId)
        .data("acquirer", "jpmorgan")
        .data("declineCode", e.getDeclineCode())
        .metric("amount", amount)
        .error(e);  // captures exception type, message, and truncated stack trace
    throw e;
}
```

### Error without exception

```java
if (sanctionsResult.isMatch()) {
    TraceLog.event("sanctions.blocked")
        .data("paymentId", paymentId)
        .data("payerId", payerId)
        .data("matchedList", sanctionsResult.getListName())
        .data("matchedEntity", sanctionsResult.getMatchedName())
        .metric("confidenceScore", sanctionsResult.getConfidence())
        .error();
}
```

### Warning for degraded scenarios

```java
int retries = 0;
while (retries < 3) {
    try {
        return ofacClient.screen(payerName, payerCountry);
    } catch (ScreeningTimeoutException e) {
        retries++;
        TraceLog.event("sanctions.ofac_retry")
            .data("attempt", retries)
            .data("paymentId", paymentId)
            .message("Retrying after OFAC service timeout")
            .warn();
    }
}
```

### Full controller error handling pattern

```java
@PostMapping("/api/payments/qualify")
public ResponseEntity<?> qualify(@RequestBody PaymentRequest req) {
    TraceLog.metadata("paymentId", req.getPaymentId());
    TraceLog.metadata("payerId", req.getPayerId());

    TraceLog.event("controller.qualify_received")
        .data("payerId", req.getPayerId())
        .data("payeeId", req.getPayeeId())
        .data("currency", req.getCurrency())
        .metric("amount", req.getAmount())
        .info();

    try {
        QualificationResult result = qualificationService.evaluate(req);

        TraceLog.event("controller.qualify_approved")
            .data("paymentId", req.getPaymentId())
            .data("decision", result.getDecision())
            .info();

        return ResponseEntity.ok(result);

    } catch (AccountSuspendedException e) {
        TraceLog.event("controller.qualify_account_suspended")
            .data("payerId", req.getPayerId())
            .data("reason", e.getSuspensionReason())
            .warn();
        return ResponseEntity.status(403).body(Map.of("error", "Account suspended"));

    } catch (LimitsExceededException e) {
        TraceLog.event("controller.qualify_limits_exceeded")
            .data("limitType", e.getLimitType())
            .metric("requested", e.getRequestedAmount())
            .metric("remaining", e.getRemainingLimit())
            .error(e);
        return ResponseEntity.unprocessableEntity().body(Map.of(
            "error", "Limit exceeded", "limitType", e.getLimitType()));

    } catch (RuntimeException e) {
        TraceLog.event("controller.qualify_error")
            .error(e);
        return ResponseEntity.internalServerError().body(Map.of("error", "Internal error"));
    }
}
```

---

## Manual Traces

For code paths not covered by auto-instrumentation (batch jobs, CLI tools, startup tasks, custom message handlers).

### Sanctions list update batch

```java
public void refreshSanctionsLists() {
    try (var trace = TraceLog.startManualTrace("batch.sanctions_list_refresh")) {
        TraceLog.metadata("jobType", "sanctions-refresh");

        TraceLog.event("sanctions.refresh_started")
            .message("Pulling latest OFAC, EU, and UN consolidated lists")
            .info();

        int ofacCount = 0, euCount = 0, unCount = 0;

        try (var timer = TraceLog.timedEvent("sanctions.ofac_download")) {
            List<SanctionsEntry> entries = ofacClient.downloadConsolidatedList();
            ofacCount = entries.size();
            sanctionsRepository.replaceList("OFAC", entries);
            timer.metric("entryCount", ofacCount);
        }

        try (var timer = TraceLog.timedEvent("sanctions.eu_download")) {
            List<SanctionsEntry> entries = euClient.downloadConsolidatedList();
            euCount = entries.size();
            sanctionsRepository.replaceList("EU", entries);
            timer.metric("entryCount", euCount);
        }

        try (var timer = TraceLog.timedEvent("sanctions.un_download")) {
            List<SanctionsEntry> entries = unClient.downloadConsolidatedList();
            unCount = entries.size();
            sanctionsRepository.replaceList("UN", entries);
            timer.metric("entryCount", unCount);
        }

        TraceLog.event("sanctions.refresh_completed")
            .metric("ofacEntries", ofacCount)
            .metric("euEntries", euCount)
            .metric("unEntries", unCount)
            .info();
    }
}
```

### Payment service startup — downstream connectivity check

```java
@Component
public class DownstreamHealthCheck implements CommandLineRunner {

    @Override
    public void run(String... args) {
        try (var trace = TraceLog.startManualTrace("startup.downstream_check")) {

            try (var timer = TraceLog.timedEvent("startup.kafka_broker_check")) {
                kafkaAdmin.describeCluster();
                timer.data("status", "reachable");
            }

            try (var timer = TraceLog.timedEvent("startup.sanctions_api_check")) {
                sanctionsClient.healthCheck();
                timer.data("status", "reachable");
            }

            try (var timer = TraceLog.timedEvent("startup.mq_broker_check")) {
                mqConnectionFactory.createConnection().close();
                timer.data("status", "reachable");
            }

            TraceLog.event("startup.all_downstream_healthy")
                .message("Kafka, sanctions API, and MQ broker all reachable")
                .info();
        }
    }
}
```

### Custom bank message handler (no annotation)

```java
public void handleBankSettlementConfirmation(String channel, byte[] payload) {
    try (var trace = TraceLog.startManualTrace("bank.settlement_confirmation")) {
        TraceLog.metadata("channel", channel);

        TraceLog.event("bank.confirmation_received")
            .data("channel", channel)
            .metric("payloadSize", payload.length)
            .info();

        BankConfirmation confirmation = bankMessageParser.parse(payload);
        settlementService.confirmSettlement(confirmation);

        TraceLog.event("bank.confirmation_processed")
            .data("bankRef", confirmation.getBankRef())
            .data("status", confirmation.getStatus())
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
    public CompletableFuture<Void> notifyPayerOfBlock(String paymentId, String payerId, String reason) {
        // This runs on an async thread, but the trace context is preserved
        TraceLog.event("notification.payer_block_generating")
            .data("paymentId", paymentId)
            .data("payerId", payerId)
            .data("reason", reason)
            .info();

        Notification notification = notificationBuilder.buildBlockNotice(paymentId, reason);
        notificationGateway.send(payerId, notification);

        TraceLog.event("notification.payer_block_sent")
            .data("paymentId", paymentId)
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
public class BatchSettlementProcessor {

    private final ExecutorService tracedExecutor;

    public void settleInParallel(List<Payment> payments) {
        TraceLog.event("settlement.batch_started")
            .metric("paymentCount", payments.size())
            .info();

        List<Future<?>> futures = payments.stream()
            .map(payment -> tracedExecutor.submit(() -> {
                // Runs on pool thread, but events attach to the original trace
                TraceLog.event("settlement.item_processed")
                    .data("paymentId", payment.getId())
                    .metric("amount", payment.getAmount())
                    .info();
                settlePayment(payment);
            }))
            .toList();

        for (Future<?> f : futures) {
            f.get();
        }

        TraceLog.event("settlement.batch_completed").info();
    }
}
```

### CompletableFuture with trace propagation

```java
public void processQualification(PaymentRequest req) {
    TraceLog.event("qualification.pipeline_started")
        .data("paymentId", req.getPaymentId())
        .info();

    TraceTaskDecorator decorator = new TraceTaskDecorator(contextManager);

    CompletableFuture.runAsync(decorator.decorate(() -> {
        TraceLog.event("async.account_validation")
            .data("payerId", req.getPayerId())
            .info();
        accountService.validateActive(req.getPayerId());
    })).thenRunAsync(decorator.decorate(() -> {
        TraceLog.event("async.limits_check")
            .data("payerId", req.getPayerId())
            .metric("amount", req.getAmount())
            .info();
        limitsService.check(req.getPayerId(), req.getAmount(), req.getCurrency());
    })).join();
}
```

### Kafka consumer with thread handoff

A common pattern: the Kafka listener thread receives the message, then hands off to a processing thread pool. The library auto-traces the receive, but the processing thread needs its own trace.

**What happens without intervention:** The `TraceKafkaInterceptor` starts a trace when `intercept()` runs and ends it when `afterRecord()` fires — both on the Kafka listener thread. If you hand work to another thread, that thread has no trace context, and the original trace closes before processing finishes.

#### What the adopting engineer does

**Step 1:** In your `@KafkaListener`, grab the current trace ID before handing off:

```java
String originatingTraceId = TraceLog.currentTraceId().orElse(null);
```

**Step 2:** In your processing method, wrap the work in `TraceLog.startManualTrace()` and stash the originating trace ID as metadata:

```java
try (var trace = TraceLog.startManualTrace("payment.async_processing")) {
    TraceLog.metadata("originatingTraceId", originatingTraceId);
    // ... your processing code — use TraceLog.event() exactly as you would anywhere else ...
}
```

That's it. Two lines of setup (`startManualTrace` + `metadata`), plus the `try` block. Everything inside the block — `TraceLog.event()`, `TraceLog.timedEvent()`, `TraceLog.metadata()` — works exactly the same as in any auto-traced endpoint.

#### Full example

```java
@Component
public class PaymentEventListener {

    private final ExecutorService processingPool = Executors.newFixedThreadPool(10);

    @KafkaListener(topics = "payment.qualified", groupId = "settlement-service")
    public void handleQualifiedPayment(ConsumerRecord<String, String> record) {
        // ── Kafka listener thread (auto-traced by the library) ──

        QualifiedPayment payment = deserialize(record.value());

        TraceLog.event("kafka.received_for_handoff")
            .data("paymentId", payment.getPaymentId())
            .info();

        // Step 1: grab the trace ID
        String originatingTraceId = TraceLog.currentTraceId().orElse(null);

        // Hand off to processing thread pool
        processingPool.submit(() -> processPayment(payment, originatingTraceId));
    }

    private void processPayment(QualifiedPayment payment, String originatingTraceId) {
        // ── Processing thread ──

        // Step 2: wrap processing in startManualTrace()
        try (var trace = TraceLog.startManualTrace("payment.async_processing")) {
            TraceLog.metadata("originatingTraceId", originatingTraceId);
            TraceLog.metadata("paymentId", payment.getPaymentId());

            // From here on, use TraceLog exactly as normal:

            TraceLog.event("processing.started")
                .data("paymentId", payment.getPaymentId())
                .data("payerId", payment.getPayerId())
                .metric("amount", payment.getAmount())
                .info();

            try (var timer = TraceLog.timedEvent("processing.validation")) {
                timer.data("paymentId", payment.getPaymentId());
                validationService.validate(payment);
            }

            try (var timer = TraceLog.timedEvent("processing.settlement")) {
                timer.data("paymentId", payment.getPaymentId());
                timer.metric("amount", payment.getAmount());
                settlementService.settle(payment);
            }

            TraceLog.event("processing.completed")
                .data("paymentId", payment.getPaymentId())
                .data("status", "SETTLED")
                .info();
        }
        // ← trace auto-submitted when the try block closes
    }
}
```

#### What you get: two linked traces

**Trace 1 — Kafka receive (automatic, no code needed):**
```json
{
  "traceId": "01KM76KPRQ9CQYM8Y9VHRS5WTK",
  "entryPoint": "KAFKA payment.qualified",
  "durationMs": 2,
  "status": "SUCCESS",
  "events": [
    { "eventName": "kafka.message.received", "dataPoints": { "topic": "payment.qualified" } },
    { "eventName": "kafka.received_for_handoff", "dataPoints": { "paymentId": "PAY-123" } },
    { "eventName": "kafka.message.processed" }
  ]
}
```

**Trace 2 — Processing (your `startManualTrace` block):**
```json
{
  "traceId": "01KM76MPVW3TK8A2B7N5XE9RHJ",
  "entryPoint": "payment.async_processing",
  "durationMs": 145,
  "status": "SUCCESS",
  "metadata": {
    "originatingTraceId": "01KM76KPRQ9CQYM8Y9VHRS5WTK",
    "paymentId": "PAY-123"
  },
  "events": [
    { "eventName": "processing.started" },
    { "eventName": "processing.validation", "durationMs": 12 },
    { "eventName": "processing.settlement", "durationMs": 120 },
    { "eventName": "processing.completed", "dataPoints": { "status": "SETTLED" } }
  ]
}
```

**To find the full story:** Search your log aggregator for `originatingTraceId: "01KM76KPRQ9CQYM8Y9VHRS5WTK"` to find all processing traces spawned from that Kafka message.

#### Error handling

`startManualTrace()` always closes with `TraceStatus.SUCCESS`. If you need the trace-level status to reflect failures (important for sampling — error traces are always captured even at 0% rate), catch exceptions and end the trace yourself:

```java
private void processPayment(QualifiedPayment payment, String originatingTraceId) {
    TraceContextManager contextManager = TraceLog.getContextManager();
    BufferManager bufferManager = TraceLog.getBufferManager();

    contextManager.startTrace("payment.async_processing");
    TraceLog.metadata("originatingTraceId", originatingTraceId);
    TraceLog.metadata("paymentId", payment.getPaymentId());

    TraceStatus status = TraceStatus.SUCCESS;
    try {
        settlementService.settle(payment);
        TraceLog.event("processing.completed").info();
    } catch (Exception e) {
        status = TraceStatus.ERROR;
        TraceLog.event("processing.failed")
            .data("paymentId", payment.getPaymentId())
            .error(e);
    } finally {
        contextManager.endTrace(status).ifPresent(bufferManager::submit);
    }
}
```

The difference: `TraceStatus.ERROR` guarantees the trace is captured even when sampling rate is 0.0.

If you don't need error-aware sampling, the simpler `startManualTrace()` form works — the error **event** (with full exception details) is still inside the trace, it's just the trace-level `status` field that says SUCCESS.

---

## Cross-Service Correlation

### Propagating trace ID to downstream REST calls (e.g., qualification calling fraud API)

```java
// RestTemplate — calling fraud scoring API for real-time lookup
restTemplate.execute(fraudServiceUrl + "/api/scores/" + paymentId, HttpMethod.GET, request -> {
    TraceLog.currentTraceId().ifPresent(id ->
        request.getHeaders().set("X-Trace-Id", id));
}, responseExtractor);

// WebClient — calling sanctions screening API
webClient.post()
    .uri("https://sanctions-service/api/screen")
    .header("X-Trace-Id", TraceLog.currentTraceId().orElse(""))
    .bodyValue(screeningRequest)
    .retrieve()
    .bodyToMono(ScreeningResult.class);

// RestClient (Spring 6.1+) — calling settlement status API
restClient.get()
    .uri("https://settlement-service/api/settlements/{id}", paymentId)
    .header("X-Trace-Id", TraceLog.currentTraceId().orElse(""))
    .retrieve()
    .body(SettlementStatus.class);
```

### Propagating trace ID to Kafka messages (e.g., qualification publishing to sanctions topic)

```java
ProducerRecord<String, String> record = new ProducerRecord<>(
    "payment.qualified", paymentId, qualifiedPaymentJson);
TraceLog.currentTraceId().ifPresent(id ->
    record.headers().add("X-Trace-Id", id.getBytes(StandardCharsets.UTF_8)));
kafkaTemplate.send(record);
```

### Propagating trace ID to JMS/IBM MQ messages (e.g., settlement sending to bank)

```java
jmsTemplate.convertAndSend("BANK.SETTLEMENT.REQUEST", settlementPayload, message -> {
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

### How correlation works across the payment ecosystem

```
payment-qualification                   sanctions-screening
─────────────────────                   ───────────────────
POST /api/payments/qualify              KAFKA payment.qualified
  traceId: "01KM76KPRQ..."               traceId: "01KM77ABC..."
  events:                                 parentTraceId: "01KM76KPRQ..."
    - qualification.received              events:
    - account.validated                     - sanctions.event_received
    - limits.checked                        - sanctions.ofac_screened
    - qualification.approved                - sanctions.eu_screened
        │                                   - sanctions.cleared
        │ (Kafka: payment.qualified)            │
        ▼                                       │ (Kafka: payment.sanctions_cleared)
                                                ▼
fraud-detection                         money-settlement
───────────────                         ────────────────
KAFKA payment.sanctions_cleared         KAFKA payment.fraud_cleared
  traceId: "01KM78DEF..."                traceId: "01KM79GHI..."
  parentTraceId: "01KM76KPRQ..."          parentTraceId: "01KM76KPRQ..."
  events:                                 events:
    - fraud.event_received                  - settlement.event_received
    - fraud.velocity_checked                - acquirer.authorized
    - fraud.model_scored                    - bank.settlement_requested (MQ)
    - fraud.cleared                         - settlement.completed
        │                                       │
        │ (Kafka: payment.fraud_cleared)        │ (MQ: BANK.SETTLEMENT.REQUEST)
        ▼                                       ▼
                                          Bank Gateway (IBM MQ)
                                          ────────────────────
                                          JMS BANK.SETTLEMENT.REQUEST
                                            traceId: "01KM80JKL..."
                                            parentTraceId: "01KM79GHI..."
                                            events:
                                              - jms.message.received
                                              - bank.credit_posted
                                              - jms.message.processed
```

Search your log aggregator for `01KM76KPRQ...` to find the originating qualification trace. Search `parentTraceId: "01KM76KPRQ..."` to find all downstream traces across sanctions, fraud, settlement, and bank gateway services.

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
curl -X POST http://localhost:8080/api/payments/qualify \
  -H "Content-Type: application/json" \
  -H "traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01" \
  -d '{"payerId":"CUST-44210","payeeId":"MERCH-8891","amount":1500.00,"currency":"USD"}'

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

## Sampling

For high-throughput services (>5k req/s), sampling reduces trace volume while preserving error visibility.

### Rate-based sampling (built-in)

```properties
# Sample 10% of traces — error traces are always captured
tracelog.sampling.rate=0.1
```

Error traces (`TraceStatus.ERROR`) are **always sampled** regardless of the configured rate. This ensures you never miss a failure.

### Custom sampling strategy

```java
@Bean
public SamplingStrategy samplingStrategy() {
    return trace -> {
        // Always keep errors
        if (trace.getStatus() == TraceStatus.ERROR) return true;
        // Always keep payment traces
        if (trace.getEntryPoint().contains("/api/payments")) return true;
        // Sample 5% of health checks
        if (trace.getEntryPoint().contains("/health")) {
            return trace.getTraceId().hashCode() % 20 == 0;
        }
        // 20% of everything else
        return trace.getTraceId().hashCode() % 5 == 0;
    };
}
```

### Monitoring sampled-out traces

```java
@Scheduled(fixedRate = 60_000)
public void reportSamplingStats() {
    BufferManager bm = TraceLog.getBufferManager();
    TraceLog.event("sampling.stats")
        .metric("written", bm.getWrittenCount())
        .metric("sampledOut", bm.getSampledOutCount())
        .metric("dropped", bm.getDroppedCount())
        .info();
}
```

---

## Real-World Patterns

### Payment Qualification Service: API entry point → Kafka publish

```java
@Service
public class QualificationService {

    public QualificationResult evaluate(PaymentRequest req) {
        String paymentId = req.getPaymentId();
        TraceLog.metadata("paymentId", paymentId);

        // Step 1: Idempotency check
        TraceLog.event("qualification.idempotency_check")
            .data("idempotencyKey", req.getIdempotencyKey())
            .info();
        Optional<QualificationResult> existing = paymentRepository.findByIdempotencyKey(req.getIdempotencyKey());
        if (existing.isPresent()) {
            TraceLog.event("qualification.idempotent_hit")
                .data("existingPaymentId", existing.get().getPaymentId())
                .message("Returning cached result for duplicate request")
                .info();
            return existing.get();
        }

        // Step 2: Account validation
        try (var timer = TraceLog.timedEvent("qualification.account_validation")) {
            timer.data("payerId", req.getPayerId());
            Account account = accountService.findById(req.getPayerId());
            timer.data("accountStatus", account.getStatus());
            timer.data("accountTier", account.getTier());
            if (!account.isActive()) {
                TraceLog.event("qualification.account_inactive")
                    .data("payerId", req.getPayerId())
                    .data("accountStatus", account.getStatus())
                    .error();
                throw new AccountSuspendedException(account.getStatus());
            }
        }

        // Step 3: Limits check
        try (var timer = TraceLog.timedEvent("qualification.limits_check")) {
            timer.data("payerId", req.getPayerId());
            timer.data("currency", req.getCurrency());
            timer.metric("amount", req.getAmount());
            LimitsResult limits = limitsService.check(req.getPayerId(), req.getAmount(), req.getCurrency());
            timer.metric("dailyUsed", limits.getDailyUsed());
            timer.metric("dailyMax", limits.getDailyMax());
            if (!limits.isWithinLimits()) {
                TraceLog.event("qualification.limits_exceeded")
                    .data("limitType", limits.getExceededType())
                    .metric("requested", req.getAmount())
                    .metric("remaining", limits.getRemaining())
                    .error();
                throw new LimitsExceededException(limits);
            }
        }

        // Step 4: Currency corridor validation
        try (var timer = TraceLog.timedEvent("qualification.currency_validation")) {
            timer.data("currency", req.getCurrency());
            timer.data("fromCountry", req.getFromCountry());
            timer.data("toCountry", req.getToCountry());
            currencyService.validateCorridor(req.getFromCountry(), req.getToCountry(), req.getCurrency());
        }

        // Step 5: Persist payment
        try (var timer = TraceLog.timedEvent("db.save_payment")) {
            Payment payment = Payment.builder()
                .id(paymentId).status("QUALIFIED").amount(req.getAmount()).build();
            paymentRepository.save(payment);
            timer.data("paymentId", paymentId);
        }

        // Step 6: Publish to sanctions screening via Kafka
        TraceLog.event("qualification.published_for_screening")
            .data("topic", "payment.qualified")
            .data("paymentId", paymentId)
            .info();
        kafkaTemplate.send(buildRecord("payment.qualified", paymentId, req));

        TraceLog.event("qualification.completed")
            .data("paymentId", paymentId)
            .data("decision", "QUALIFIED")
            .metric("amount", req.getAmount())
            .info();

        return new QualificationResult(paymentId, "QUALIFIED");
    }
}
```

### Sanctions Screening Service: Kafka in → Kafka out

```java
@Component
public class SanctionsScreeningListener {

    @KafkaListener(topics = "payment.qualified", groupId = "sanctions-screening")
    public void handleQualifiedPayment(ConsumerRecord<String, String> record) {
        // Trace auto-started by Kafka interceptor — X-Trace-Id header reused if present

        QualifiedPayment payment = deserialize(record.value());
        TraceLog.metadata("paymentId", payment.getPaymentId());
        TraceLog.metadata("payerId", payment.getPayerId());

        TraceLog.event("sanctions.event_received")
            .data("paymentId", payment.getPaymentId())
            .data("payerId", payment.getPayerId())
            .data("payeeId", payment.getPayeeId())
            .metric("amount", payment.getAmount())
            .info();

        // Screen payer
        try (var timer = TraceLog.timedEvent("sanctions.payer_screening")) {
            timer.data("entity", "payer");
            timer.data("payerId", payment.getPayerId());
            ScreeningResult payerResult = sanctionsEngine.screen(
                payment.getPayerName(), payment.getPayerCountry());
            timer.data("listChecked", "OFAC,EU,UN");
            timer.metric("matchCount", payerResult.getMatchCount());
            timer.data("decision", payerResult.getDecision());

            if (payerResult.isMatch()) {
                TraceLog.event("sanctions.payer_blocked")
                    .data("matchedList", payerResult.getListName())
                    .data("matchedEntity", payerResult.getMatchedName())
                    .metric("confidence", payerResult.getConfidence())
                    .error();
                publishBlocked(payment, "PAYER_SANCTIONS_MATCH", payerResult);
                return;
            }
        }

        // Screen payee
        try (var timer = TraceLog.timedEvent("sanctions.payee_screening")) {
            timer.data("entity", "payee");
            timer.data("payeeId", payment.getPayeeId());
            ScreeningResult payeeResult = sanctionsEngine.screen(
                payment.getPayeeName(), payment.getPayeeCountry());
            timer.metric("matchCount", payeeResult.getMatchCount());
            timer.data("decision", payeeResult.getDecision());

            if (payeeResult.isMatch()) {
                TraceLog.event("sanctions.payee_blocked")
                    .data("matchedList", payeeResult.getListName())
                    .data("matchedEntity", payeeResult.getMatchedName())
                    .metric("confidence", payeeResult.getConfidence())
                    .error();
                publishBlocked(payment, "PAYEE_SANCTIONS_MATCH", payeeResult);
                return;
            }
        }

        // PEP check
        try (var timer = TraceLog.timedEvent("sanctions.pep_check")) {
            timer.data("payerId", payment.getPayerId());
            PepResult pepResult = pepService.check(payment.getPayerName(), payment.getPayerDob());
            timer.data("pepStatus", pepResult.getStatus());
            if (pepResult.isPep()) {
                TraceLog.event("sanctions.pep_flagged")
                    .data("payerId", payment.getPayerId())
                    .data("pepCategory", pepResult.getCategory())
                    .warn();
                // PEP doesn't block — but flags for enhanced due diligence
            }
        }

        // Publish cleared event to fraud detection
        TraceLog.event("sanctions.cleared")
            .data("paymentId", payment.getPaymentId())
            .data("topic", "payment.sanctions_cleared")
            .info();
        kafkaTemplate.send(buildRecord("payment.sanctions_cleared", payment));
    }
}
```

### Fraud Detection Service: Kafka in → Kafka out + REST API

```java
@Component
public class FraudDetectionListener {

    @KafkaListener(topics = "payment.sanctions_cleared", groupId = "fraud-detection")
    public void handleSanctionsClearedPayment(ConsumerRecord<String, String> record) {
        // Trace auto-started by Kafka interceptor

        ClearedPayment payment = deserialize(record.value());
        TraceLog.metadata("paymentId", payment.getPaymentId());

        TraceLog.event("fraud.event_received")
            .data("paymentId", payment.getPaymentId())
            .data("payerId", payment.getPayerId())
            .metric("amount", payment.getAmount())
            .info();

        // Velocity checks
        try (var timer = TraceLog.timedEvent("fraud.velocity_check")) {
            timer.data("payerId", payment.getPayerId());
            VelocityResult velocity = velocityService.check(payment.getPayerId());
            timer.metric("txnCountLast1h", velocity.getCountLastHour());
            timer.metric("txnCountLast24h", velocity.getCountLast24Hours());
            timer.metric("amountLast24h", velocity.getAmountLast24Hours());
            timer.data("velocityBreached", velocity.isBreached());

            if (velocity.isBreached()) {
                TraceLog.event("fraud.velocity_breach")
                    .data("payerId", payment.getPayerId())
                    .data("breachType", velocity.getBreachType())
                    .metric("threshold", velocity.getThreshold())
                    .metric("actual", velocity.getActualValue())
                    .warn();
            }
        }

        // Device fingerprint check
        try (var timer = TraceLog.timedEvent("fraud.device_fingerprint")) {
            timer.data("deviceId", payment.getDeviceId());
            DeviceResult device = deviceService.evaluate(payment.getDeviceId(), payment.getPayerId());
            timer.data("deviceKnown", device.isKnown());
            timer.data("riskLevel", device.getRiskLevel());
        }

        // ML model scoring
        FraudScore score;
        try (var timer = TraceLog.timedEvent("fraud.model_inference")) {
            timer.data("model", "gradient-boost-v3");
            Map<String, Object> features = featureBuilder.build(payment);
            timer.metric("featureCount", features.size());
            score = fraudModel.predict(features);
            timer.metric("score", score.getValue());
            timer.data("decision", score.getDecision());
            timer.metric("modelLatencyMs", score.getInferenceLatency());
        }

        // Persist score
        try (var timer = TraceLog.timedEvent("db.save_fraud_score")) {
            timer.data("paymentId", payment.getPaymentId());
            timer.metric("score", score.getValue());
            fraudScoreRepository.save(payment.getPaymentId(), score);
        }

        // Decision routing
        if (score.isRejected()) {
            TraceLog.event("fraud.rejected")
                .data("paymentId", payment.getPaymentId())
                .metric("score", score.getValue())
                .data("reason", score.getTopRiskFactors())
                .error();
            kafkaTemplate.send(buildRecord("payment.fraud_rejected", payment, score));
        } else if (score.requiresReview()) {
            TraceLog.event("fraud.manual_review_required")
                .data("paymentId", payment.getPaymentId())
                .metric("score", score.getValue())
                .message("Score in review band — routed to analyst queue")
                .warn();
            kafkaTemplate.send(buildRecord("payment.fraud_review", payment, score));
        } else {
            TraceLog.event("fraud.cleared")
                .data("paymentId", payment.getPaymentId())
                .metric("score", score.getValue())
                .info();
            kafkaTemplate.send(buildRecord("payment.fraud_cleared", payment));
        }
    }
}

// REST API for score lookups (used by other services and dashboards)
@RestController
@RequestMapping("/api/scores")
public class FraudScoreController {

    @GetMapping("/{paymentId}")
    public FraudScoreResponse getScore(@PathVariable String paymentId) {
        TraceLog.event("fraud.score_lookup")
            .data("paymentId", paymentId)
            .info();

        FraudScore score = fraudScoreRepository.findByPaymentId(paymentId)
            .orElseThrow(() -> new ScoreNotFoundException(paymentId));

        TraceLog.event("fraud.score_returned")
            .data("paymentId", paymentId)
            .metric("score", score.getValue())
            .data("decision", score.getDecision())
            .info();

        return new FraudScoreResponse(score);
    }
}
```

### Money Settlement Service: Kafka in → acquirer + MQ to bank

```java
@Component
public class MoneySettlementListener {

    @KafkaListener(topics = "payment.fraud_cleared", groupId = "money-settlement")
    public void handleFraudClearedPayment(ConsumerRecord<String, String> record) {
        // Trace auto-started by Kafka interceptor

        ClearedPayment payment = deserialize(record.value());
        TraceLog.metadata("paymentId", payment.getPaymentId());
        TraceLog.metadata("acquirer", routingService.selectAcquirer(payment));

        TraceLog.event("settlement.event_received")
            .data("paymentId", payment.getPaymentId())
            .data("payerId", payment.getPayerId())
            .data("payeeId", payment.getPayeeId())
            .metric("amount", payment.getAmount())
            .data("currency", payment.getCurrency())
            .info();

        // Step 1: Acquirer authorization
        String acquirer = routingService.selectAcquirer(payment);
        AuthResponse auth;
        try (var timer = TraceLog.timedEvent("settlement.acquirer_authorize")) {
            timer.data("acquirer", acquirer);
            timer.metric("amount", payment.getAmount());
            timer.data("currency", payment.getCurrency());
            auth = acquirerGateway.authorize(acquirer, payment);
            timer.data("authCode", auth.getCode());
            timer.data("responseCode", auth.getResponseCode());
            timer.metric("acquirerLatencyMs", auth.getLatency());

            if (!auth.isApproved()) {
                TraceLog.event("settlement.acquirer_declined")
                    .data("acquirer", acquirer)
                    .data("declineCode", auth.getResponseCode())
                    .data("declineReason", auth.getReasonText())
                    .error();
                kafkaTemplate.send(buildRecord("payment.settlement_failed", payment, auth));
                return;
            }
        }

        // Step 2: FX conversion if cross-currency
        if (!payment.getCurrency().equals(payment.getSettlementCurrency())) {
            try (var timer = TraceLog.timedEvent("settlement.fx_conversion")) {
                timer.data("fromCurrency", payment.getCurrency());
                timer.data("toCurrency", payment.getSettlementCurrency());
                FxRate rate = fxService.getRate(payment.getCurrency(), payment.getSettlementCurrency());
                timer.metric("fxRate", rate.getRate());
                timer.metric("originalAmount", payment.getAmount());
                timer.metric("convertedAmount", rate.convert(payment.getAmount()));
            }
        }

        // Step 3: Send settlement instruction to bank via IBM MQ
        try (var timer = TraceLog.timedEvent("settlement.bank_instruction_sent")) {
            timer.data("queue", "BANK.SETTLEMENT.REQUEST");
            timer.data("paymentId", payment.getPaymentId());
            timer.data("acquirer", acquirer);
            timer.metric("amount", payment.getAmount());

            jmsTemplate.convertAndSend("BANK.SETTLEMENT.REQUEST",
                bankMessageBuilder.buildSettlement(payment, auth), message -> {
                    TraceLog.currentTraceId().ifPresent(id -> {
                        try {
                            message.setStringProperty("X-Trace-Id", id);
                        } catch (JMSException e) { /* handle */ }
                    });
                    return message;
                });
        }

        // Step 4: Create ledger entry
        try (var timer = TraceLog.timedEvent("settlement.ledger_entry")) {
            timer.data("paymentId", payment.getPaymentId());
            timer.data("type", "DEBIT");
            timer.metric("amount", payment.getAmount());
            ledgerService.createEntry(payment, auth);
        }

        // Step 5: Persist settlement record
        try (var timer = TraceLog.timedEvent("db.save_settlement")) {
            timer.data("paymentId", payment.getPaymentId());
            timer.data("status", "PENDING_BANK_CONFIRMATION");
            settlementRepository.save(payment.getPaymentId(), acquirer, auth, "PENDING_BANK_CONFIRMATION");
        }

        TraceLog.event("settlement.instruction_completed")
            .data("paymentId", payment.getPaymentId())
            .data("acquirer", acquirer)
            .data("authCode", auth.getCode())
            .data("status", "PENDING_BANK_CONFIRMATION")
            .info();
    }
}
```

### IBM MQ consumer: Bank settlement confirmation

```java
@Component
public class BankSettlementResponseListener {

    @JmsListener(destination = "BANK.SETTLEMENT.RESPONSE")
    public void handleBankSettlementResponse(Message message) throws JMSException {
        // Trace auto-started by JMS aspect — X-Trace-Id property reused if present

        String payload = ((TextMessage) message).getText();
        BankSettlementResponse response = bankMessageParser.parse(payload);

        TraceLog.metadata("bankCode", response.getBankCode());
        TraceLog.metadata("paymentId", response.getPaymentId());

        TraceLog.event("settlement.bank_response_received")
            .data("bankRef", response.getBankRef())
            .data("bankCode", response.getBankCode())
            .data("responseCode", response.getResponseCode())
            .data("paymentId", response.getPaymentId())
            .info();

        // Map bank response code to internal status
        try (var timer = TraceLog.timedEvent("settlement.bank_response_mapping")) {
            timer.data("bankResponseCode", response.getResponseCode());
            String internalStatus = bankCodeMapper.toInternalStatus(response.getResponseCode());
            timer.data("internalStatus", internalStatus);

            if ("SETTLED".equals(internalStatus)) {
                TraceLog.event("settlement.bank_confirmed")
                    .data("bankRef", response.getBankRef())
                    .data("paymentId", response.getPaymentId())
                    .metric("settledAmount", response.getSettledAmount())
                    .info();
            } else {
                TraceLog.event("settlement.bank_rejected")
                    .data("bankRef", response.getBankRef())
                    .data("rejectReason", response.getReasonText())
                    .data("paymentId", response.getPaymentId())
                    .error();
            }
        }

        // Update settlement record
        try (var timer = TraceLog.timedEvent("db.update_settlement")) {
            timer.data("paymentId", response.getPaymentId());
            timer.data("newStatus", bankCodeMapper.toInternalStatus(response.getResponseCode()));
            settlementRepository.updateStatus(
                response.getPaymentId(),
                bankCodeMapper.toInternalStatus(response.getResponseCode()),
                response.getBankRef());
        }

        // Publish final status to Kafka for downstream consumers
        TraceLog.event("settlement.status_published")
            .data("topic", "payment.settlement_completed")
            .data("paymentId", response.getPaymentId())
            .info();
        kafkaTemplate.send(buildRecord("payment.settlement_completed", response));
    }
}
```

### Scheduled job: Stale settlement reconciliation

```java
@Component
public class StaleSettlementReconciler {

    @Scheduled(fixedRate = 300_000) // every 5 minutes
    public void reconcileStaleSettlements() {
        // Trace auto-started by @Scheduled interceptor

        TraceLog.event("reconciliation.scan_started")
            .message("Scanning for settlements pending bank confirmation > 1 hour")
            .info();

        List<Settlement> stale;
        try (var timer = TraceLog.timedEvent("db.find_stale_settlements")) {
            stale = settlementRepository.findPendingOlderThan(Duration.ofHours(1));
            timer.metric("staleCount", stale.size());
        }

        int resolved = 0;
        int escalated = 0;
        for (Settlement settlement : stale) {
            try (var timer = TraceLog.timedEvent("reconciliation.check_bank_status")) {
                timer.data("paymentId", settlement.getPaymentId());
                timer.data("acquirer", settlement.getAcquirer());
                timer.metric("amount", settlement.getAmount());

                BankStatus bankStatus = bankInquiryService.checkStatus(settlement);
                timer.data("bankStatus", bankStatus.getStatus());

                if (bankStatus.isResolved()) {
                    settlementRepository.updateStatus(settlement.getPaymentId(), bankStatus.getStatus(), bankStatus.getBankRef());
                    resolved++;
                } else {
                    escalated++;
                    TraceLog.event("reconciliation.escalated")
                        .data("paymentId", settlement.getPaymentId())
                        .data("reason", "no_bank_response_after_1h")
                        .warn();
                }
            } catch (Exception e) {
                TraceLog.event("reconciliation.check_failed")
                    .data("paymentId", settlement.getPaymentId())
                    .error(e);
            }
        }

        TraceLog.event("reconciliation.scan_completed")
            .metric("resolved", resolved)
            .metric("escalated", escalated)
            .metric("total", stale.size())
            .info();
    }
}
```

### Health check: Ecosystem connectivity

```java
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        TraceLog.event("health.check")
            .metric("pendingPayments", paymentRepository.countByStatus("QUALIFIED"))
            .metric("pendingSettlements", settlementRepository.countByStatus("PENDING_BANK_CONFIRMATION"))
            .metric("activeConnections", dataSource.getActiveConnections())
            .metric("mqQueueDepth", mqMonitor.getQueueDepth("BANK.SETTLEMENT.REQUEST"))
            .metric("kafkaConsumerLag", kafkaMonitor.getLag("payment.fraud_cleared"))
            .info();

        return Map.of(
            "status", "UP",
            "pendingSettlements", settlementRepository.countByStatus("PENDING_BANK_CONFIRMATION")
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
curl http://localhost:8080/health

# Qualify a payment
curl -X POST http://localhost:8080/api/payments/qualify \
  -H "Content-Type: application/json" \
  -d '{"payerId":"CUST-44210","payeeId":"MERCH-8891","amount":1500.00,"currency":"USD","fromCountry":"US","toCountry":"GB"}'

# Qualify with OTEL traceparent (reuses upstream trace ID)
curl -X POST http://localhost:8080/api/payments/qualify \
  -H "Content-Type: application/json" \
  -H "traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01" \
  -d '{"payerId":"CUST-44210","payeeId":"MERCH-8891","amount":1500.00,"currency":"USD"}'

# Qualify with upstream service correlation
curl -X POST http://localhost:8080/api/payments/qualify \
  -H "Content-Type: application/json" \
  -H "X-Trace-Id: checkout-service-trace-456" \
  -d '{"payerId":"CUST-44210","payeeId":"MERCH-8891","amount":1500.00,"currency":"USD"}'

# Look up a fraud score (fraud-detection service)
curl http://localhost:8082/api/scores/PAY-20260321-44210
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
class PaymentQualificationControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void qualifyPayment_returnsTraceIdHeader() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/payments/qualify", paymentRequest, Map.class);

        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertNotNull(traceId);
        assertEquals(26, traceId.length()); // ULID format
    }
}
```

### Unit test with TraceLog initialized

```java
class QualificationServiceTest {

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
    void evaluate_qualifiesValidPayment() {
        // Start a trace manually for the test
        contextManager.startTrace("test");

        QualificationResult result = qualificationService.evaluate(validRequest);

        assertEquals("QUALIFIED", result.getDecision());
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

class SanctionsScreeningTest {

    private TestSink testSink;

    @BeforeEach
    void setUp() {
        testSink = new TestSink();
        var cm = new TraceContextManager(new UlidGenerator(), "test", 1000);
        var bm = new BufferManager(testSink, BufferConfig.defaults());
        TraceLog.initialize(cm, bm);
    }

    @Test
    void screening_capturesSanctionsEvents() {
        try (var trace = TraceLog.startManualTrace("test")) {
            sanctionsService.screen(qualifiedPayment);
        }

        // Wait for flush
        Thread.sleep(100);

        CompletedTrace trace = testSink.traces.get(0);
        assertTrue(trace.getEvents().stream()
            .anyMatch(e -> e.getEventName().equals("sanctions.payer_screening")));
        assertTrue(trace.getEvents().stream()
            .anyMatch(e -> e.getEventName().equals("sanctions.payee_screening")));
    }
}
```

---

## JSON Output Reference

Every completed trace produces one JSON document. Here is an example from the **sanctions-screening** service processing a qualified payment via Kafka:

```json
{
  "traceId": "01KM77ABCDEF123456789ABCDE",
  "serviceName": "sanctions-screening",
  "entryPoint": "KAFKA payment.qualified (SanctionsScreeningListener.handleQualifiedPayment)",
  "startTime": "2026-03-21T10:15:30.123Z",
  "endTime": "2026-03-21T10:15:30.410Z",
  "durationMs": 287,
  "status": "SUCCESS",
  "parentTraceId": "01KM76KPRQ9CQYM8Y9VHRS5WTK",
  "metadata": {
    "paymentId": "PAY-20260321-44210",
    "payerId": "CUST-44210"
  },
  "events": [
    {
      "eventName": "sanctions.event_received",
      "timestamp": "2026-03-21T10:15:30.123Z",
      "severity": "INFO",
      "origin": "com.sanctions.listener.SanctionsScreeningListener.handleQualifiedPayment",
      "dataPoints": {
        "paymentId": "PAY-20260321-44210",
        "payerId": "CUST-44210",
        "payeeId": "MERCH-8891"
      },
      "metrics": {
        "amount": 1500.00
      }
    },
    {
      "eventName": "sanctions.payer_screening",
      "timestamp": "2026-03-21T10:15:30.150Z",
      "severity": "INFO",
      "origin": "com.sanctions.listener.SanctionsScreeningListener.handleQualifiedPayment",
      "dataPoints": {
        "entity": "payer",
        "payerId": "CUST-44210",
        "listChecked": "OFAC,EU,UN",
        "decision": "CLEAR"
      },
      "metrics": {
        "matchCount": 0
      },
      "durationMs": 38
    },
    {
      "eventName": "sanctions.payee_screening",
      "timestamp": "2026-03-21T10:15:30.220Z",
      "severity": "INFO",
      "origin": "com.sanctions.listener.SanctionsScreeningListener.handleQualifiedPayment",
      "dataPoints": {
        "entity": "payee",
        "payeeId": "MERCH-8891",
        "decision": "CLEAR"
      },
      "metrics": {
        "matchCount": 0
      },
      "durationMs": 42
    },
    {
      "eventName": "sanctions.pep_check",
      "timestamp": "2026-03-21T10:15:30.290Z",
      "severity": "INFO",
      "origin": "com.sanctions.listener.SanctionsScreeningListener.handleQualifiedPayment",
      "dataPoints": {
        "payerId": "CUST-44210",
        "pepStatus": "NOT_PEP"
      },
      "durationMs": 15
    },
    {
      "eventName": "sanctions.cleared",
      "timestamp": "2026-03-21T10:15:30.410Z",
      "severity": "INFO",
      "origin": "com.sanctions.listener.SanctionsScreeningListener.handleQualifiedPayment",
      "dataPoints": {
        "paymentId": "PAY-20260321-44210",
        "topic": "payment.sanctions_cleared"
      }
    }
  ]
}
```
