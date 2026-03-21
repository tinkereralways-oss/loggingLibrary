# Performance Benchmarks

## Environment

- **Java:** OpenJDK 25.0.2
- **OS:** macOS Darwin 24.6.0
- **Date:** 2026-03-21
- **Library version:** 1.0.0-SNAPSHOT
- **Test class:** `com.tracing.core.TraceLogPerformanceTest`

## Target

The trace-log library must sustain **20,000 transactions per second (TPS)** without dropping traces or degrading latency. This target supports services processing up to 20k req/s with full trace instrumentation enabled.

## Results Summary

| Test | Target | Actual | Headroom |
|------|--------|--------|----------|
| Full Trace Lifecycle | 20,000 TPS | **586,968 TPS** | 29x |
| Buffer Submit (isolated) | 100,000 TPS | **414,501 TPS** | 4x |
| Sustained Load (per batch) | 20,000 TPS | **186k - 1,075k TPS** | 9-53x |
| p99 Latency | < 500 us | **2 us** | 250x |

## Test Details

### 1. Full Trace Lifecycle (586,968 TPS)

Simulates the complete hot path a real HTTP request takes through the library:

1. `startTrace()` — push TraceContext onto ThreadLocal stack, generate ULID
2. `TraceLog.event()` x3 — build LogEvent with StackWalker origin capture, data points, and metrics
3. `metadata()` — write to ConcurrentHashMap
4. `endTrace()` — snapshot events/metadata into immutable CompletedTrace
5. `bufferManager.submit()` — enqueue into ConcurrentLinkedQueue

```
Threads:         16
Total traces:    100,000
Elapsed:         0.17s
Throughput:      586,968 TPS
Sink written:    100,000
Dropped:         0
```

**Zero traces dropped.** All 100k traces were enqueued and written to the sink.

### 2. Buffer Submit Throughput (414,501 TPS)

Isolates the `BufferManager.submit()` path — pre-built CompletedTrace objects submitted by 16 concurrent threads. Measures ConcurrentLinkedQueue contention and AtomicInteger counter overhead under high concurrency.

```
Threads:         16
Total submits:   800,000
Elapsed:         1.93s
Throughput:      414,501 TPS
```

### 3. Sustained Load — No Degradation (186k - 1,075k TPS)

Four consecutive batches of 5,000 traces each, validating that throughput does not degrade over time due to GC pressure, queue growth, or memory leaks.

```
Batch 1:  186,430 TPS
Batch 2:  315,858 TPS
Batch 3:  691,021 TPS
Batch 4:  1,075,095 TPS
Last/First ratio: 5.77
```

Throughput *increases* across batches due to JIT compilation warmup. No degradation observed. All batches exceed the 20k TPS target.

### 4. Single Trace Latency (p99: 2 us)

Measures per-trace overhead on a single thread with 50,000 samples after a 5,000-iteration warmup. Each sample covers the full lifecycle (start, 2 events with data/metrics, metadata, end, submit).

```
p50:  2 us
p95:  2 us
p99:  2 us
max:  40 us
```

At 20k TPS each trace has a 50 us budget. The p99 of 2 us provides 25x headroom for real-world overhead (GC pauses, I/O, application logic).

## Architecture Enabling Performance

The library achieves these numbers through:

- **Lock-free hot path:** `ConcurrentLinkedQueue` for trace buffering, `AtomicInteger` for O(1) queue size tracking, no `synchronized` blocks on the submission path.
- **ThreadLocal context stack:** Each HTTP/worker thread maintains its own `ArrayDeque<TraceContext>` with zero cross-thread contention.
- **Async drain:** A single background thread drains traces in batches of 256, decoupling trace creation from sink I/O.
- **ULID generation:** Thread-safe ID generation without synchronization (SecureRandom + timestamp).
- **StackWalker frame cache:** Application frame detection results cached in a bounded `ConcurrentHashMap` to avoid repeated class-name scanning.

## How to Run

```bash
# Run performance tests only
mvn test -pl trace-log-core -Dtest="TraceLogPerformanceTest"

# Run all tests including performance
mvn test -pl trace-log-core
```

## Methodology Notes

- The sink is a no-op counter (`CountingSink`) to isolate library overhead from I/O costs. Real-world throughput will be bounded by sink implementation (e.g., JSON serialization, network writes).
- Each "transaction" mirrors what the Spring Boot interceptor does for a typical REST request: trace start, 2-3 events with data/metrics, metadata, trace completion, and buffer submission.
- Tests use a `CountDownLatch` barrier so all threads begin work simultaneously, maximizing contention to stress-test concurrent data structures.
- The sustained load test runs 4 sequential batches to detect throughput degradation from GC pressure or unbounded memory growth.
