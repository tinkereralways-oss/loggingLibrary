package com.tracing.example.service;

import com.tracing.core.TraceLog;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderCleanupTask {

    @Scheduled(fixedRate = 60000)
    public void cleanupExpiredOrders() {
        TraceLog.event("cleanup.scan")
                .message("Scanning for expired orders")
                .info();

        // Simulate cleanup work
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        TraceLog.event("cleanup.completed")
                .metric("expiredCount", 0)
                .info();
    }
}
