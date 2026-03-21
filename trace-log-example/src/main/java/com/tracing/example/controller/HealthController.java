package com.tracing.example.controller;

import com.tracing.core.TraceLog;
import com.tracing.example.repository.OrderRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final OrderRepository orderRepository;

    public HealthController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        TraceLog.event("health.check")
                .metric("orderCount", orderRepository.count())
                .info();

        return Map.of(
                "status", "UP",
                "orderCount", orderRepository.count()
        );
    }
}
