package com.pierchala.synccore.model;

import java.time.Instant;
import java.util.UUID;

public record CustomerDto(
        UUID id,
        String companyName,
        String taxId,
        String email,
        String city,
        Instant updatedAt
) {
}