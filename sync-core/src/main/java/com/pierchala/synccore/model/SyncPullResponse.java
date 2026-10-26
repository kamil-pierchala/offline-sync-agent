package com.pierchala.synccore.model;

import java.time.Instant;
import java.util.List;

public record SyncPullResponse(
        List<OrderDto> items,
        Instant serverTimestamp
) {
}