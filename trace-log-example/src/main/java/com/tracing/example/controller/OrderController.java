package com.tracing.example.controller;

import com.tracing.core.TraceLog;
import com.tracing.example.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody Map<String, Object> request) {
        TraceLog.metadata("userId", (String) request.get("userId"));

        String userId = (String) request.get("userId");
        @SuppressWarnings("unchecked")
        List<String> items = (List<String>) request.get("items");
        String paymentMethod = (String) request.get("paymentMethod");
        double total = ((Number) request.get("total")).doubleValue();

        TraceLog.event("controller.create_order")
                .data("userId", userId)
                .data("itemCount", items.size())
                .info();

        try {
            Map<String, Object> order = orderService.createOrder(userId, items, paymentMethod, total);
            return ResponseEntity.ok(order);
        } catch (RuntimeException e) {
            TraceLog.event("controller.order_failed")
                    .data("reason", e.getMessage())
                    .error(e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Map<String, Object>> getOrder(@PathVariable String orderId) {
        TraceLog.event("controller.get_order")
                .data("orderId", orderId)
                .info();

        Map<String, Object> order = orderService.getOrder(orderId);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(order);
    }
}
