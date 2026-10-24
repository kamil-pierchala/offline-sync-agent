package com.pierchala.synccore.model;

import java.util.List;
import java.util.UUID;

public record SyncBatchResponse(
        List<SyncedItem> processedItems
) {
    public record SyncedItem(
            UUID id,
            Long serverVersion,
            boolean success,
            String errorMessage
    ) {}
}