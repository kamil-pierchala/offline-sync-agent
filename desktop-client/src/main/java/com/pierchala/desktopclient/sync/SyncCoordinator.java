package com.pierchala.desktopclient.sync;

import com.pierchala.desktopclient.db.OrderDao;
import com.pierchala.desktopclient.model.LocalOrder;
import com.pierchala.desktopclient.net.SyncHttpClient;
import com.pierchala.synccore.model.SyncBatchRequest;
import com.pierchala.synccore.model.SyncBatchResponse;

import java.util.List;
import java.util.UUID;

public class SyncCoordinator {

    private final OrderDao orderDao;
    private final SyncHttpClient httpClient;
    private final String clientId;

    public SyncCoordinator(OrderDao orderDao, SyncHttpClient httpClient) {
        this.orderDao = orderDao;
        this.httpClient = httpClient;
        this.clientId = "desktop-client-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public int performPushSync() throws Exception {
        // Fetch local dirty records
        List<LocalOrder> pendingOrders = orderDao.findPendingSync();
        if (pendingOrders.isEmpty()) {
            return 0;
        }

        // Map to sync batch payload
        List<SyncBatchRequest.OrderSyncPayload> payloadItems = pendingOrders.stream()
                .map(order -> new SyncBatchRequest.OrderSyncPayload(order.toDto(), order.syncStatus()))
                .toList();

        SyncBatchRequest batchRequest = new SyncBatchRequest(clientId, payloadItems);

        // Send over HTTP to central server
        SyncBatchResponse response = httpClient.pushSync(batchRequest);

        // Update local statuses based on server confirmation
        int syncedCount = 0;
        for (SyncBatchResponse.SyncedItem item : response.processedItems()) {
            if (item.success()) {
                orderDao.findById(item.id()).ifPresent(order -> {
                    switch (order.syncStatus()) {
                        case DIRTY_CREATED, DIRTY_UPDATED -> orderDao.markAsSynced(item.id(), item.serverVersion());
                        case DIRTY_DELETED -> orderDao.removeDeleted(item.id());
                        default -> {}
                    }
                });
                syncedCount++;
            }
        }

        return syncedCount;
    }
}