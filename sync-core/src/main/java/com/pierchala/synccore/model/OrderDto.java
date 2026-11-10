package com.pierchala.synccore.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderDto(
        UUID id,
        String orderNumber,
        String customerName,
        BigDecimal totalAmount,
        String status,
        Instant updatedAt,
        Long version,
        List<OrderItemDto> items
) {
    // helper constructor (empty by default)
    public OrderDto(UUID id, String orderNumber, String customerName, BigDecimal totalAmount, String status, Instant updatedAt, Long version) {
        this(id, orderNumber, customerName, totalAmount, status, updatedAt, version, List.of());
    }
}