# trace-log Configuration Reference

All properties are optional. The library works with zero configuration.

## Properties

### Core

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `tracelog.enabled` | boolean | `true` | Enable/disable the library entirely |
| `tracelog.service-name` | String | `${spring.application.name}` | Service identifier in trace output |

### Trace ID

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `tracelog.trace-id.format` | `ULID` / `UUID` | `ULID` | ID generation strategy |
| `tracelog.trace-id.propagation-header` | String | `X-Trace-Id` | Header name for cross-service correlation |

**ULID** (recommended): 26-character, time-sortable, Crockford Base32. Example: `01KM76KPRQ9CQYM8Y9VHRS5WTK`

**UUID**: Standard 36-character UUID v4. Example: `550e8400-e29b-41d4-a716-446655440000`

**Note:** When a W3C `traceparent` header is present on an incoming request, the OTEL trace ID is used regardless of this setting. The format setting only applies when generating new trace IDs (i.e., when no `traceparent` header is found).

### Buffer

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `tracelog.buffer.max-events-per-trace` | int | `1000` | Events per trace before capping |
| `tracelog.buffer.orphan-scan-interval-seconds` | int | `5` | Flush cycle interval |
| `tracelog.buffer.max-trace-duration-seconds` | int | `300` | Unused (reserved for future orphan detection) |
| `tracelog.buffer.max-pending-traces` | int | `10000` | Queue size before backpressure drops traces |

### Sampling

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `tracelog.sampling.rate` | double | `1.0` | Fraction of traces to keep (0.0 - 1.0). Error traces are always sampled regardless of rate. |

**How it works:**
- `1.0` — sample everything (default, backward compatible)
- `0.0` — only sample traces with `TraceStatus.ERROR`
- `0.1` — sample ~10% of traces + all errors
- Uses deterministic hash of trace ID, so the same trace ID always produces the same sampling decision

**Custom strategy:** Provide a `SamplingStrategy` bean to override the default rate-based strategy:

```java
@Bean
public SamplingStrategy samplingStrategy() {
    return trace -> {
        // Always sample errors
        if (trace.getStatus() == TraceStatus.ERROR) return true;
        // Custom logic: sample all payment traces, 10% of health checks
        if (trace.getEntryPoint().contains("/api/payments")) return true;
        return Math.random() < 0.1;
    };
}
```

### Sink

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `tracelog.sink.type` | String | `json-stdout` | Reserved for future sink types |
| `tracelog.sink.pretty-print` | boolean | `false` | Indent JSON output |

### No-Context Behavior

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `tracelog.no-context.behavior` | `NOOP` / `WARN` / `AUTO_START` | `NOOP` | What happens when `TraceLog.event()` is called with no active trace |

- `NOOP`: Silently discard (default)
- `WARN`: Log a warning via SLF4J
- `AUTO_START`: Create an ad-hoc trace

## Example Configurations

### Development (readable output)

```properties
tracelog.sink.pretty-print=true
tracelog.trace-id.format=ULID
```

### Production (minimal overhead)

```properties
tracelog.sink.pretty-print=false
tracelog.buffer.max-events-per-trace=500
tracelog.buffer.max-pending-traces=50000
tracelog.sampling.rate=0.1
```

### High-throughput production (>5k req/s)

```properties
tracelog.sink.pretty-print=false
tracelog.buffer.max-events-per-trace=200
tracelog.buffer.max-pending-traces=100000
tracelog.buffer.orphan-scan-interval-seconds=2
tracelog.sampling.rate=0.05
```

### Disabled (e.g., in test profiles)

```properties
tracelog.enabled=false
```

## Auto-Configuration Conditions

The library conditionally registers components based on classpath detection:

| Component | Registered When |
|-----------|----------------|
| REST interceptor | `spring-webmvc` + servlet web application |
| Kafka interceptor | `spring-kafka` on classpath |
| JMS interceptor | `spring-jms` + `aspectjweaver` on classpath |
| `@Scheduled` aspect | `aspectjweaver` on classpath |
| `@Async` decorator | Always (Spring core) |

All beans use `@ConditionalOnMissingBean` — provide your own implementation to override any component.

## Overriding Beans

```java
@Configuration
public class MyTraceConfig {

    // Custom ID generator
    @Bean
    public TraceIdGenerator traceIdGenerator() {
        return () -> "SVC-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // Custom sink
    @Bean
    public LogSink logSink() {
        return new MyKafkaSink(kafkaTemplate);
    }

    // Custom interceptor
    @Bean
    public TraceHandlerInterceptor traceHandlerInterceptor(
            TraceContextManager cm, BufferManager bm) {
        return new MyInterceptor(cm, bm, "X-Request-Id");
    }
}
```
