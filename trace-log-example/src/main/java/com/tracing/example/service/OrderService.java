package com.tracing.example.service;

import com.tracing.core.TraceLog;
import com.tracing.example.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrderService {

    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final OrderRepository orderRepository;

    public OrderService(InventoryService inventoryService,
                        PaymentService paymentService,
                        OrderRepository orderRepository) {
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
        this.orderRepository = orderRepository;
    }

    public Map<String, Object> createOrder(String userId, List<String> items,
                                           String paymentMethod, double total) {
        TraceLog.event("order.validation")
                .data("userId", userId)
                .data("itemCount", items.size())
                .metric("orderTotal", total)
                .info();

        // Check inventory
        boolean available = inventoryService.checkAvailability(items);
        if (!available) {
            TraceLog.event("order.rejected")
                    .data("reason", "inventory_unavailable")
                    .warn();
            throw new RuntimeException("Some items are not available");
        }

        // Process payment
        String transactionId = paymentService.charge(paymentMethod, total);

        // Save order
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("userId", userId);
        order.put("items", items);
        order.put("total", total);
        order.put("paymentMethod", paymentMethod);
        order.put("transactionId", transactionId);
        order.put("status", "CONFIRMED");

        Map<String, Object> saved = orderRepository.save(order);

        TraceLog.event("order.confirmed")
                .data("orderId", saved.get("orderId"))
                .data("transactionId", transactionId)
                .metric("totalAmount", total)
                .info();

        return saved;
    }

    public Map<String, Object> getOrder(String orderId) {
        TraceLog.event("order.lookup")
                .data("orderId", orderId)
                .info();

        Map<String, Object> order = orderRepository.findById(orderId);
        if (order == null) {
            TraceLog.event("order.not_found")
                    .data("orderId", orderId)
                    .warn();
        }
        return order;
    }
}
