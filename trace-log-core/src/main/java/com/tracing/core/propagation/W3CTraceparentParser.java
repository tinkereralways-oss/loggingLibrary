package com.tracing.core.propagation;

/**
 * Parses W3C Trace Context {@code traceparent} header values.
 * Format: {@code version-traceid-parentid-traceflags} (e.g. {@code 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01})
 */
public final class W3CTraceparentParser {

    /** The W3C Trace Context header name. */
    public static final String HEADER_NAME = "traceparent";

    private static final int TRACE_ID_HEX_LENGTH = 32;
    private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";
    // Minimum length: version(2) + '-' + traceId(32) + '-' + parentId(16) + '-' + flags(2) = 55
    private static final int MIN_HEADER_LENGTH = 55;
    private static final int TRACE_ID_START = 3;
    private static final int TRACE_ID_END = TRACE_ID_START + TRACE_ID_HEX_LENGTH;

    private W3CTraceparentParser() {
    }

    /**
     * Extracts the trace ID from a W3C {@code traceparent} header value.
     *
     * @param traceparent the raw header value
     * @return the 32-char hex trace ID, or {@code null} if the header is missing, malformed, or contains an invalid trace ID
     */
    public static String extractTraceId(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }

        String trimmed = traceparent.trim();
        if (trimmed.length() < MIN_HEADER_LENGTH) {
            return null;
        }

        if (trimmed.charAt(2) != '-' || trimmed.charAt(TRACE_ID_END) != '-') {
            return null;
        }

        String traceId = trimmed.substring(TRACE_ID_START, TRACE_ID_END);

        if (INVALID_TRACE_ID.equals(traceId)) {
            return null;
        }

        if (!isLowercaseHex(traceId)) {
            return null;
        }

        return traceId;
    }

    private static boolean isLowercaseHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}
