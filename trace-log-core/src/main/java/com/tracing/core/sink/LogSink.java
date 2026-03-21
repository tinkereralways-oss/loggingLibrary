package com.tracing.core.sink;

import com.tracing.core.CompletedTrace;

public interface LogSink {

    void write(CompletedTrace trace);

    void flush();

    void close();
}
