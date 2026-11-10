package com.pierchala.synccore.model;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemDto(
        UUID id,
        String productName,
        int quantity,
        BigDecimal unitPrice
) {
    public BigDecimal calculateLineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}