package com.tracing.example.service;

import com.tracing.core.TraceLog;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    public String charge(String paymentMethod, double amount) {
        try (var timer = TraceLog.timedEvent("payment.charge")) {
            timer.data("paymentMethod", paymentMethod);
            timer.metric("amount", amount);

            // Simulate payment gateway call
            simulateLatency(50);

            String transactionId = "TXN-" + System.currentTimeMillis();
            timer.data("transactionId", transactionId);

            TraceLog.event("payment.success")
                    .data("transactionId", transactionId)
                    .metric("chargedAmount", amount)
                    .info();

            return transactionId;
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
