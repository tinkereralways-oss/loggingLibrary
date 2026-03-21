package com.tracing.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests that exercise the trace-log library inside a real
 * Docker-hosted Spring Boot application. Each test makes HTTP calls to the
 * containerized example app, waits for the async buffer to drain, then
 * inspects the container stdout for JSON trace output.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TraceLogE2ETest {

    private static final String APP_URL = "http://localhost:8085";
    private static final String SAMPLED_APP_URL = "http://localhost:8086";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private DockerEnvironment docker;

    @BeforeAll
    void startContainers() throws Exception {
        docker = new DockerEnvironment();
        docker.start();
    }

    @AfterAll
    void stopContainers() throws Exception {
        if (docker != null) {
            docker.stop();
        }
    }

    // -------------------------------------------------------------------
    // 1. Health check validates the app is running and tracelog is active
    // -------------------------------------------------------------------

    @Test
    @Order(1)
    void healthEndpoint_returnsUp() throws Exception {
        HttpResponse<String> response = docker.get(APP_URL + "/health");
        assertEquals(200, response.statusCode());

        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("UP", body.get("status").asText());
    }

    // -------------------------------------------------------------------
    // 2. Create order → full trace with events from controller, service,
    //    inventory, payment, repository layers
    // -------------------------------------------------------------------

    @Test
    @Order(2)
    void createOrder_producesFullTrace() throws Exception {
        String orderJson = """
                {
                  "userId": "user-e2e-1",
                  "items": ["item-a", "item-b"],
                  "paymentMethod": "credit_card",
                  "total": 99.99
                }
                """;

        HttpResponse<String> response = docker.post(APP_URL + "/api/orders", orderJson);
        // Might be 200 (success) or 400 (inventory randomly unavailable)
        assertTrue(response.statusCode() == 200 || response.statusCode() == 400,
                "Expected 200 or 400, got " + response.statusCode());

        // Wait for buffer drain
        Thread.sleep(3000);

        List<JsonNode> traces = getTraces("trace-log-app");
        assertFalse(traces.isEmpty(), "Expected at least one trace in logs");

        // Find a trace with the POST /api/orders entry point
        List<JsonNode> orderTraces = traces.stream()
                .filter(t -> t.has("entryPoint") && t.get("entryPoint").asText().contains("POST /api/orders"))
                .toList();
        assertFalse(orderTraces.isEmpty(), "Expected a trace for POST /api/orders");

        JsonNode trace = orderTraces.get(orderTraces.size() - 1);

        // Validate trace structure
        assertNotNull(trace.get("traceId"), "Trace must have traceId");
        assertNotNull(trace.get("startTime"), "Trace must have startTime");
        assertNotNull(trace.get("endTime"), "Trace must have endTime");
        assertTrue(trace.get("durationMs").asLong() > 0, "Duration must be positive");
        assertEquals("order-service-example", trace.get("serviceName").asText());

        // Validate events exist
        JsonNode events = trace.get("events");
        assertNotNull(events, "Trace must have events");
        assertTrue(events.size() >= 2, "Expected at least 2 events, got " + events.size());

        // Check that controller event is present
        boolean hasControllerEvent = false;
        for (JsonNode event : events) {
            if ("controller.create_order".equals(event.get("eventName").asText())) {
                hasControllerEvent = true;
                break;
            }
        }
        assertTrue(hasControllerEvent, "Expected controller.create_order event");
    }

    // -------------------------------------------------------------------
    // 3. GET order → trace with lookup events
    // -------------------------------------------------------------------

    @Test
    @Order(3)
    void getOrder_producesTrace() throws Exception {
        HttpResponse<String> response = docker.get(APP_URL + "/api/orders/ORD-nonexistent");
        assertTrue(response.statusCode() == 404 || response.statusCode() == 500,
                "Expected 404 or 500 for nonexistent order, got " + response.statusCode());

        Thread.sleep(3000);

        List<JsonNode> traces = getTraces("trace-log-app");
        List<JsonNode> getTraces = traces.stream()
                .filter(t -> t.has("entryPoint")
                        && t.get("entryPoint").asText().contains("GET /api/orders"))
                .toList();
        assertFalse(getTraces.isEmpty(), "Expected a trace for GET /api/orders");
    }

    // -------------------------------------------------------------------
    // 4. Trace ID propagation — response header contains trace ID
    // -------------------------------------------------------------------

    @Test
    @Order(4)
    void traceIdHeader_returnedInResponse() throws Exception {
        HttpResponse<String> response = docker.get(APP_URL + "/health");
        assertEquals(200, response.statusCode());

        String traceId = response.headers().firstValue("X-Trace-Id").orElse(null);
        assertNotNull(traceId, "Response must contain X-Trace-Id header");
        assertFalse(traceId.isBlank(), "Trace ID must not be blank");
    }

    // -------------------------------------------------------------------
    // 5. Parent trace ID propagation via X-Trace-Id header
    // -------------------------------------------------------------------

    @Test
    @Order(5)
    void parentTraceId_propagatedViaHeader() throws Exception {
        HttpResponse<String> response = docker.get(
                APP_URL + "/health", "X-Trace-Id", "parent-e2e-test-123");
        assertEquals(200, response.statusCode());

        Thread.sleep(3000);

        List<JsonNode> traces = getTraces("trace-log-app");
        List<JsonNode> healthTraces = traces.stream()
                .filter(t -> t.has("parentTraceId")
                        && "parent-e2e-test-123".equals(t.get("parentTraceId").asText()))
                .toList();

        assertFalse(healthTraces.isEmpty(), "Expected a trace with parentTraceId=parent-e2e-test-123");
    }

    // -------------------------------------------------------------------
    // 6. W3C traceparent header → trace ID reuse
    // -------------------------------------------------------------------

    @Test
    @Order(6)
    void w3cTraceparent_reusesTraceId() throws Exception {
        String expectedTraceId = "0af7651916cd43dd8448eb211c80319c";
        String traceparent = "00-" + expectedTraceId + "-b7ad6b7169203331-01";

        HttpResponse<String> response = docker.get(APP_URL + "/health", "traceparent", traceparent);
        assertEquals(200, response.statusCode());

        Thread.sleep(3000);

        List<JsonNode> traces = getTraces("trace-log-app");
        List<JsonNode> matching = traces.stream()
                .filter(t -> expectedTraceId.equals(t.get("traceId").asText()))
                .toList();

        assertFalse(matching.isEmpty(),
                "Expected a trace with traceId=" + expectedTraceId + " from traceparent header");
    }

    // -------------------------------------------------------------------
    // 7. Scheduled task produces traces automatically
    // -------------------------------------------------------------------

    @Test
    @Order(7)
    void scheduledTask_producesTrace() throws Exception {
        // The cleanup task runs every 60s, but we've been running for a while.
        // Wait a bit longer to ensure at least one scheduled execution.
        Thread.sleep(5000);

        List<JsonNode> traces = getTraces("trace-log-app");
        List<JsonNode> scheduledTraces = traces.stream()
                .filter(t -> t.has("entryPoint")
                        && t.get("entryPoint").asText().contains("SCHEDULED"))
                .toList();

        assertFalse(scheduledTraces.isEmpty(), "Expected at least one SCHEDULED trace");

        JsonNode trace = scheduledTraces.get(0);
        assertEquals("SUCCESS", trace.get("status").asText());
        assertTrue(trace.get("entryPoint").asText().contains("OrderCleanupTask"),
                "Scheduled trace should reference OrderCleanupTask");
    }

    // -------------------------------------------------------------------
    // 8. Concurrent requests → each gets a unique trace ID
    // -------------------------------------------------------------------

    @Test
    @Order(8)
    void concurrentRequests_eachGetUniqueTraceId() throws Exception {
        int concurrency = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(1);
        List<String> traceIds = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    HttpResponse<String> resp = docker.get(APP_URL + "/health");
                    String tid = resp.headers().firstValue("X-Trace-Id").orElse(null);
                    if (tid != null) {
                        traceIds.add(tid);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(concurrency, traceIds.size(), "All requests should return trace IDs");
        Set<String> unique = traceIds.stream().collect(Collectors.toSet());
        assertEquals(concurrency, unique.size(), "All trace IDs should be unique");
    }

    // -------------------------------------------------------------------
    // 9. Trace metadata — userId attached to order trace
    // -------------------------------------------------------------------

    @Test
    @Order(9)
    void traceMetadata_userIdAttached() throws Exception {
        String orderJson = """
                {
                  "userId": "metadata-test-user",
                  "items": ["item-x"],
                  "paymentMethod": "debit",
                  "total": 10.00
                }
                """;

        docker.post(APP_URL + "/api/orders", orderJson);
        Thread.sleep(3000);

        List<JsonNode> traces = getTraces("trace-log-app");
        List<JsonNode> withMetadata = traces.stream()
                .filter(t -> t.has("metadata")
                        && t.get("metadata").has("userId")
                        && "metadata-test-user".equals(t.get("metadata").get("userId").asText()))
                .toList();

        assertFalse(withMetadata.isEmpty(), "Expected a trace with userId metadata");
    }

    // -------------------------------------------------------------------
    // 10. Timed events have positive duration
    // -------------------------------------------------------------------

    @Test
    @Order(10)
    void timedEvents_havePositiveDuration() throws Exception {
        // Create an order that succeeds (might take a few retries due to random inventory)
        JsonNode successTrace = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            String orderJson = """
                    {
                      "userId": "timed-user",
                      "items": ["single-item"],
                      "paymentMethod": "credit",
                      "total": 5.00
                    }
                    """;
            docker.post(APP_URL + "/api/orders", orderJson);
            Thread.sleep(3000);

            List<JsonNode> traces = getTraces("trace-log-app");
            List<JsonNode> successful = traces.stream()
                    .filter(t -> "SUCCESS".equals(t.path("status").asText())
                            && t.path("entryPoint").asText().contains("POST /api/orders"))
                    .toList();
            if (!successful.isEmpty()) {
                successTrace = successful.get(successful.size() - 1);
                break;
            }
        }

        assertNotNull(successTrace, "Expected at least one successful order trace");

        // Check for timed events with durationMs
        boolean hasTimedEvent = false;
        for (JsonNode event : successTrace.get("events")) {
            if (event.has("durationMs") && event.get("durationMs").asLong() > 0) {
                hasTimedEvent = true;
                break;
            }
        }
        assertTrue(hasTimedEvent, "Expected at least one timed event with positive duration");
    }

    // -------------------------------------------------------------------
    // 11. Sampling — the sampled app produces fewer traces than requests
    // -------------------------------------------------------------------

    @Test
    @Order(11)
    void sampling_reducesTraceOutput() throws Exception {
        int totalRequests = 50;
        for (int i = 0; i < totalRequests; i++) {
            docker.get(SAMPLED_APP_URL + "/health");
        }

        Thread.sleep(4000);

        List<JsonNode> traces = getTraces("trace-log-app-sampled");
        List<JsonNode> healthTraces = traces.stream()
                .filter(t -> t.has("entryPoint")
                        && t.get("entryPoint").asText().contains("GET /health"))
                .toList();

        // With 50% sampling, we expect roughly 25 traces (± variance).
        // The key assertion: not all 50 requests produced traces.
        // Allow for health checks from Docker healthcheck too.
        assertTrue(healthTraces.size() < totalRequests,
                "Sampling should reduce trace count. Got " + healthTraces.size()
                        + " traces for " + totalRequests + " requests");
        assertTrue(healthTraces.size() > 0, "Some traces should still be sampled");
    }

    // -------------------------------------------------------------------
    // 12. Error trace — bad request body produces ERROR trace
    // -------------------------------------------------------------------

    @Test
    @Order(12)
    void errorTrace_capturedOnBadRequest() throws Exception {
        // Send malformed JSON that will cause a server error (missing required fields)
        HttpResponse<String> response = docker.post(APP_URL + "/api/orders", "{}");
        assertTrue(response.statusCode() >= 400, "Expected error status for empty body");

        Thread.sleep(3000);

        List<JsonNode> traces = getTraces("trace-log-app");
        List<JsonNode> errorTraces = traces.stream()
                .filter(t -> "ERROR".equals(t.path("status").asText())
                        && t.path("entryPoint").asText().contains("POST /api/orders"))
                .toList();

        assertFalse(errorTraces.isEmpty(), "Expected an ERROR trace for the bad request");
    }

    // -------------------------------------------------------------------
    // 13. Trace JSON structure completeness
    // -------------------------------------------------------------------

    @Test
    @Order(13)
    void traceJson_hasCompleteStructure() throws Exception {
        docker.get(APP_URL + "/health");
        Thread.sleep(3000);

        List<JsonNode> traces = getTraces("trace-log-app");
        assertFalse(traces.isEmpty(), "Should have traces");

        JsonNode trace = traces.get(traces.size() - 1);

        // Required top-level fields
        assertTrue(trace.has("traceId"), "Missing traceId");
        assertTrue(trace.has("serviceName"), "Missing serviceName");
        assertTrue(trace.has("entryPoint"), "Missing entryPoint");
        assertTrue(trace.has("startTime"), "Missing startTime");
        assertTrue(trace.has("endTime"), "Missing endTime");
        assertTrue(trace.has("durationMs"), "Missing durationMs");
        assertTrue(trace.has("status"), "Missing status");
        assertTrue(trace.has("events"), "Missing events");

        // Validate field types
        assertTrue(trace.get("traceId").isTextual(), "traceId should be string");
        assertTrue(trace.get("durationMs").isNumber(), "durationMs should be number");
        assertTrue(trace.get("events").isArray(), "events should be array");

        // Validate event structure
        if (trace.get("events").size() > 0) {
            JsonNode event = trace.get("events").get(0);
            assertTrue(event.has("eventName"), "Event missing eventName");
            assertTrue(event.has("timestamp"), "Event missing timestamp");
            assertTrue(event.has("severity"), "Event missing severity");
        }
    }

    // -------------------------------------------------------------------
    // 14. ULID format — trace IDs are 26-char base32
    // -------------------------------------------------------------------

    @Test
    @Order(14)
    void traceId_isValidUlidFormat() throws Exception {
        HttpResponse<String> response = docker.get(APP_URL + "/health");
        String traceId = response.headers().firstValue("X-Trace-Id").orElse(null);
        assertNotNull(traceId);

        // ULID: 26 chars, Crockford base32
        assertEquals(26, traceId.length(), "ULID should be 26 chars, got: " + traceId);
        assertTrue(traceId.matches("[0-9A-HJKMNP-TV-Z]+"),
                "ULID should use Crockford base32 charset, got: " + traceId);
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private List<JsonNode> getTraces(String service) throws Exception {
        List<String> logs = docker.getLogs(service);
        List<JsonNode> traces = new ArrayList<>();
        for (String line : logs) {
            String trimmed = line.trim();
            if (trimmed.startsWith("{") && trimmed.contains("\"traceId\"")) {
                try {
                    JsonNode node = MAPPER.readTree(trimmed);
                    if (node.has("traceId") && node.has("entryPoint")) {
                        traces.add(node);
                    }
                } catch (Exception ignored) {
                    // not a trace JSON line
                }
            }
        }
        return traces;
    }
}
