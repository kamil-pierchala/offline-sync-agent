package com.pierchala.synccore.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderDto(
        UUID id,
        String orderNumber,
        String customerName,
        BigDecimal totalAmount,
        String status,
        Instant updatedAt,
        Long version
) {
}