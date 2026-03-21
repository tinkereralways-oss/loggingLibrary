package com.tracing.example.service;

import com.tracing.core.TraceLog;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class InventoryService {

    public boolean checkAvailability(List<String> items) {
        try (var timer = TraceLog.timedEvent("inventory.check")) {
            timer.data("items", items);
            timer.metric("itemCount", items.size());

            // Simulate inventory lookup
            simulateLatency(25);

            int available = items.size() - ThreadLocalRandom.current().nextInt(0, 2);
            timer.metric("availableCount", available);

            boolean allAvailable = available == items.size();
            if (!allAvailable) {
                TraceLog.event("inventory.partial_availability")
                        .data("requested", items.size())
                        .data("available", available)
                        .warn();
            }
            return allAvailable;
        }
    }

    private void simulateLatency(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
