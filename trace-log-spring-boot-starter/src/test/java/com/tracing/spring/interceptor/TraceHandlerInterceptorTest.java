package com.tracing.spring.interceptor;

import com.tracing.core.TraceStatus;
import com.tracing.core.propagation.W3CTraceparentParser;
import com.tracing.core.sink.LogSink;
import com.tracing.spring.test.TestSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        classes = TraceHandlerInterceptorTest.TestApp.class,
        properties = {
                "spring.application.name=interceptor-test",
                "tracelog.buffer.orphan-scan-interval-seconds=1"
        }
)
@AutoConfigureMockMvc
class TraceHandlerInterceptorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LogSink logSink;

    private TestSink testSink() {
        return (TestSink) logSink;
    }

    @BeforeEach
    void setUp() throws InterruptedException {
        // Wait briefly for any stale traces from prior tests to drain
        Thread.sleep(200);
        testSink().clear();
    }

    @Test
    void getRequest_createsTraceWithCorrectEntryPoint() throws Exception {
        mockMvc.perform(get("/api/hello"))
                .andExpect(status().isOk());

        waitForTraces(1);
        assertThat(testSink().getTraces()).hasSize(1);
        assertThat(testSink().getTraces().get(0).getEntryPoint()).contains("REST GET /api/hello");
    }

    @Test
    void response_containsTraceIdHeader() throws Exception {
        mockMvc.perform(get("/api/hello"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void xTraceIdHeader_setsParentTraceId() throws Exception {
        mockMvc.perform(get("/api/hello")
                        .header("X-Trace-Id", "parent-123"))
                .andExpect(status().isOk());

        waitForTraces(1);
        assertThat(testSink().getTraces().get(0).getParentTraceId()).isEqualTo("parent-123");
    }

    @Test
    void traceparentHeader_reusesTraceId() throws Exception {
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        mockMvc.perform(get("/api/hello")
                        .header(W3CTraceparentParser.HEADER_NAME, traceparent))
                .andExpect(status().isOk());

        waitForTraces(1);
        assertThat(testSink().getTraces().get(0).getTraceId())
                .isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void exception_setsErrorStatus() throws Exception {
        mockMvc.perform(get("/api/error"))
                .andExpect(status().is5xxServerError());

        waitForTraces(1);
        assertThat(testSink().getTraces().get(0).getStatus()).isEqualTo(TraceStatus.ERROR);
    }

    @Test
    void multipleRequests_createSeparateTraces() throws Exception {
        mockMvc.perform(get("/api/hello")).andExpect(status().isOk());
        mockMvc.perform(get("/api/hello")).andExpect(status().isOk());

        waitForTraces(2);
        assertThat(testSink().getTraces().size()).isGreaterThanOrEqualTo(2);
        assertThat(testSink().getTraces().get(0).getTraceId())
                .isNotEqualTo(testSink().getTraces().get(1).getTraceId());
    }

    @Test
    void status200_setsSuccessStatus() throws Exception {
        mockMvc.perform(get("/api/hello"))
                .andExpect(status().isOk());

        waitForTraces(1);
        assertThat(testSink().getTraces().get(0).getStatus()).isEqualTo(TraceStatus.SUCCESS);
    }

    @Test
    void mdcCleanedUpAfterRequest() throws Exception {
        mockMvc.perform(get("/api/hello"))
                .andExpect(status().isOk());

        assertThat(org.slf4j.MDC.get("traceId")).isNull();
    }

    @Test
    void traceContainsRequestReceivedEvent() throws Exception {
        mockMvc.perform(get("/api/hello"))
                .andExpect(status().isOk());

        waitForTraces(1);
        var events = testSink().getTraces().get(0).getEvents();
        assertThat(events).anyMatch(e -> "request.received".equals(e.getEventName()));
    }

    @Test
    void traceContainsRequestCompletedEvent() throws Exception {
        mockMvc.perform(get("/api/hello"))
                .andExpect(status().isOk());

        waitForTraces(1);
        var events = testSink().getTraces().get(0).getEvents();
        assertThat(events).anyMatch(e -> "request.completed".equals(e.getEventName()));
    }

    private void waitForTraces(int expected) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            if (testSink().getTraces().size() >= expected) return;
            Thread.sleep(100);
        }
    }

    @SpringBootApplication
    static class TestApp {

        @Bean
        LogSink logSink() {
            return new TestSink();
        }

        @RestController
        static class TestController {

            @GetMapping("/api/hello")
            String hello() {
                return "hello";
            }

            @GetMapping("/api/error")
            String error() {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "test error");
            }
        }
    }
}
