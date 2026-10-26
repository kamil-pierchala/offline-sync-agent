package com.pierchala.desktopclient.sync;

import com.pierchala.desktopclient.db.OrderDao;
import com.pierchala.desktopclient.model.LocalOrder;
import com.pierchala.desktopclient.net.SyncHttpClient;
import com.pierchala.synccore.model.OrderDto;
import com.pierchala.synccore.model.SyncBatchRequest;
import com.pierchala.synccore.model.SyncBatchResponse;
import com.pierchala.synccore.model.SyncPullResponse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    public SyncResult performFullSync() throws Exception {
        int pushedCount = 0;
        int pulledCount = 0;

        // Send local dirty changes
        List<LocalOrder> pendingOrders = orderDao.findPendingSync();
        if (!pendingOrders.isEmpty()) {
            List<SyncBatchRequest.OrderSyncPayload> payloadItems = pendingOrders.stream()
                    .map(order -> new SyncBatchRequest.OrderSyncPayload(order.toDto(), order.syncStatus()))
                    .toList();

            SyncBatchRequest batchRequest = new SyncBatchRequest(clientId, payloadItems);
            SyncBatchResponse pushResponse = httpClient.pushSync(batchRequest);

            for (SyncBatchResponse.SyncedItem item : pushResponse.processedItems()) {
                if (item.success()) {
                    orderDao.findById(item.id()).ifPresent(order -> {
                        switch (order.syncStatus()) {
                            case DIRTY_CREATED, DIRTY_UPDATED -> orderDao.markAsSynced(item.id(), item.serverVersion());
                            case DIRTY_DELETED -> orderDao.removeDeleted(item.id());
                            default -> {}
                        }
                    });
                    pushedCount++;
                }
            }
        }

        // Fetch remote changes since last sync
        Optional<Instant> lastSyncOpt = orderDao.getLastSyncTimestamp();
        SyncPullResponse pullResponse = httpClient.pullSync(lastSyncOpt.orElse(null));

        for (OrderDto remoteOrder : pullResponse.items()) {
            orderDao.upsertFromServer(remoteOrder);
            pulledCount++;
        }

        // Save new sync milestone timestamp
        orderDao.setLastSyncTimestamp(pullResponse.serverTimestamp());

        return new SyncResult(pushedCount, pulledCount);
    }

    public record SyncResult(int pushedCount, int pulledCount) {}
}