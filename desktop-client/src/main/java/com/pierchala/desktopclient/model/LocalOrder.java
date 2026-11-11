package com.pierchala.desktopclient.model;

import com.pierchala.synccore.model.OrderDto;
import com.pierchala.synccore.model.OrderItemDto;
import com.pierchala.synccore.model.SyncStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LocalOrder(
        UUID id,
        String orderNumber,
        String customerName,
        BigDecimal totalAmount,
        String status,
        Instant updatedAt,
        Long version,
        SyncStatus syncStatus,
        List<OrderItemDto> items
) {
    // Convenience constructor defaulting to empty item list
    public LocalOrder(UUID id, String orderNumber, String customerName, BigDecimal totalAmount, String status, Instant updatedAt, Long version, SyncStatus syncStatus) {
        this(id, orderNumber, customerName, totalAmount, status, updatedAt, version, syncStatus, List.of());
    }

    public OrderDto toDto() {
        return new OrderDto(id, orderNumber, customerName, totalAmount, status, updatedAt, version, items);
    }
}