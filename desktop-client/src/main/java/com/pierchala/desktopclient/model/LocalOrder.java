package com.pierchala.desktopclient.model;

import com.pierchala.synccore.model.OrderDto;
import com.pierchala.synccore.model.SyncStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LocalOrder(
        UUID id,
        String orderNumber,
        String customerName,
        BigDecimal totalAmount,
        String status,
        Instant updatedAt,
        Long version,
        SyncStatus syncStatus
) {
    public OrderDto toDto() {
        return new OrderDto(id, orderNumber, customerName, totalAmount, status, updatedAt, version);
    }
}