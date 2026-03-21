package com.tracing.example.repository;

import com.tracing.core.TraceLog;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class OrderRepository {

    private final Map<String, Map<String, Object>> orders = new ConcurrentHashMap<>();

    public Map<String, Object> save(Map<String, Object> order) {
        String orderId = "ORD-" + System.currentTimeMillis();
        order.put("orderId", orderId);

        try (var timer = TraceLog.timedEvent("db.insert")) {
            timer.data("table", "orders");
            timer.data("orderId", orderId);
            // Simulate DB write
            simulateLatency(15);
            orders.put(orderId, order);
        }

        return order;
    }

    public Map<String, Object> findById(String orderId) {
        try (var timer = TraceLog.timedEvent("db.query")) {
            timer.data("table", "orders");
            timer.data("orderId", orderId);
            // Simulate DB read
            simulateLatency(8);
            return orders.get(orderId);
        }
    }

    public int count() {
        return orders.size();
    }

    private void simulateLatency(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
