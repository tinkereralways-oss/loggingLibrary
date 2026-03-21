package com.tracing.core.id;

import java.util.UUID;

public final class UuidGenerator implements TraceIdGenerator {

    @Override
    public String generate() {
        return UUID.randomUUID().toString();
    }
}
