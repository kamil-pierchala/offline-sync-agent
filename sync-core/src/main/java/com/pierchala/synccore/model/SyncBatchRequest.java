package com.pierchala.synccore.model;

import java.util.List;

public record SyncBatchRequest(
        String clientId,
        List<OrderSyncPayload> items
) {
    public record OrderSyncPayload(
            OrderDto order,
            SyncStatus syncStatus
    ) {}
}