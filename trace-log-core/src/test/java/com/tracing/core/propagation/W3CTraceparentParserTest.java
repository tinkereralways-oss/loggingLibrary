package com.tracing.core.propagation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class W3CTraceparentParserTest {

    @Test
    void extractsTraceIdFromValidTraceparent() {
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        assertEquals("0af7651916cd43dd8448eb211c80319c",
                W3CTraceparentParser.extractTraceId(traceparent));
    }

    @Test
    void returnsNullForNullInput() {
        assertNull(W3CTraceparentParser.extractTraceId(null));
    }

    @Test
    void returnsNullForBlankInput() {
        assertNull(W3CTraceparentParser.extractTraceId(""));
        assertNull(W3CTraceparentParser.extractTraceId("   "));
    }

    @Test
    void returnsNullForMalformedInput() {
        assertNull(W3CTraceparentParser.extractTraceId("not-a-valid-header"));
        assertNull(W3CTraceparentParser.extractTraceId("00-short-b7ad6b7169203331-01"));
    }

    @Test
    void returnsNullForAllZeroTraceId() {
        String traceparent = "00-00000000000000000000000000000000-b7ad6b7169203331-01";
        assertNull(W3CTraceparentParser.extractTraceId(traceparent));
    }

    @Test
    void returnsNullForNonHexTraceId() {
        assertNull(W3CTraceparentParser.extractTraceId(
                "00-ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ-b7ad6b7169203331-01"));
    }

    @Test
    void returnsNullForUppercaseHexTraceId() {
        assertNull(W3CTraceparentParser.extractTraceId(
                "00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01"));
    }

    @Test
    void handlesUnsampledTraceFlags() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00";
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736",
                W3CTraceparentParser.extractTraceId(traceparent));
    }
}
