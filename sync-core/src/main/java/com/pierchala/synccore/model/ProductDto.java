package com.pierchala.synccore.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductDto(
        UUID id,
        String productCode,
        String name,
        BigDecimal unitPrice,
        int stockQuantity,
        Instant updatedAt
) {
}