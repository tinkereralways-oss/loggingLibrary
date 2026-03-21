package com.tracing.spring.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tracelog")
public class TraceLogProperties {

    private boolean enabled = true;
    private String serviceName;
    private Buffer buffer = new Buffer();
    private Sink sink = new Sink();
    private TraceId traceId = new TraceId();
    private NoContext noContext = new NoContext();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public Buffer getBuffer() {
        return buffer;
    }

    public void setBuffer(Buffer buffer) {
        this.buffer = buffer;
    }

    public Sink getSink() {
        return sink;
    }

    public void setSink(Sink sink) {
        this.sink = sink;
    }

    public TraceId getTraceId() {
        return traceId;
    }

    public void setTraceId(TraceId traceId) {
        this.traceId = traceId;
    }

    public NoContext getNoContext() {
        return noContext;
    }

    public void setNoContext(NoContext noContext) {
        this.noContext = noContext;
    }

    public static class Buffer {
        private int maxEventsPerTrace = 1000;
        private int orphanScanIntervalSeconds = 5;
        private int maxTraceDurationSeconds = 300;
        private int maxPendingTraces = 10000;

        public int getMaxEventsPerTrace() {
            return maxEventsPerTrace;
        }

        public void setMaxEventsPerTrace(int maxEventsPerTrace) {
            this.maxEventsPerTrace = maxEventsPerTrace;
        }

        public int getOrphanScanIntervalSeconds() {
            return orphanScanIntervalSeconds;
        }

        public void setOrphanScanIntervalSeconds(int orphanScanIntervalSeconds) {
            this.orphanScanIntervalSeconds = orphanScanIntervalSeconds;
        }

        public int getMaxTraceDurationSeconds() {
            return maxTraceDurationSeconds;
        }

        public void setMaxTraceDurationSeconds(int maxTraceDurationSeconds) {
            this.maxTraceDurationSeconds = maxTraceDurationSeconds;
        }

        public int getMaxPendingTraces() {
            return maxPendingTraces;
        }

        public void setMaxPendingTraces(int maxPendingTraces) {
            this.maxPendingTraces = maxPendingTraces;
        }
    }

    public static class Sink {
        private String type = "json-stdout";
        private boolean prettyPrint = false;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public boolean isPrettyPrint() {
            return prettyPrint;
        }

        public void setPrettyPrint(boolean prettyPrint) {
            this.prettyPrint = prettyPrint;
        }
    }

    public static class TraceId {
        private Format format = Format.ULID;
        private String propagationHeader = "X-Trace-Id";

        public Format getFormat() {
            return format;
        }

        public void setFormat(Format format) {
            this.format = format;
        }

        public String getPropagationHeader() {
            return propagationHeader;
        }

        public void setPropagationHeader(String propagationHeader) {
            this.propagationHeader = propagationHeader;
        }

        public enum Format {
            ULID,
            UUID
        }
    }

    public static class NoContext {
        private Behavior behavior = Behavior.NOOP;

        public Behavior getBehavior() {
            return behavior;
        }

        public void setBehavior(Behavior behavior) {
            this.behavior = behavior;
        }

        public enum Behavior {
            NOOP,
            WARN,
            AUTO_START
        }
    }
}
