package com.pierchala.desktopclient.sync;

import com.pierchala.desktopclient.db.OrderDao;
import com.pierchala.desktopclient.model.LocalOrder;
import com.pierchala.desktopclient.net.SyncHttpClient;
import com.pierchala.synccore.model.SyncBatchRequest;
import com.pierchala.synccore.model.SyncBatchResponse;
import com.pierchala.synccore.model.SyncPullResponse;
import com.pierchala.synccore.model.SyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SyncCoordinatorTest {

    private OrderDao orderDao;
    private SyncHttpClient httpClient;
    private SyncCoordinator coordinator;

    @BeforeEach
    void setUp() {
        // Create mocks for the database and HTTP client
        orderDao = mock(OrderDao.class);
        httpClient = mock(SyncHttpClient.class);

        // Inject mocks into the coordinator
        coordinator = new SyncCoordinator(orderDao, httpClient);
    }

    @Test
    void shouldMarkAsConflictWhenServerRejectsUpdate() throws Exception {
        // Prepare initial data
        UUID orderId = UUID.randomUUID();
        LocalOrder dirtyOrder = new LocalOrder(
                orderId, "ORD-TEST", "Test Customer", new BigDecimal("100.00"),
                "NEW", Instant.now(), 1L, SyncStatus.DIRTY_UPDATED
        );

        // Mock database behavior - return our "dirty" order for syncing
        when(orderDao.findPendingSync()).thenReturn(List.of(dirtyOrder));
        when(orderDao.getLastSyncTimestamp()).thenReturn(Optional.empty());

        // Mock HTTP client behavior (simulate server rejecting the order with a CONFLICT message)
        SyncBatchResponse.SyncedItem conflictItem = new SyncBatchResponse.SyncedItem(
                orderId, 1L, false, "CONFLICT: Server has a newer version"
        );
        when(httpClient.pushSync(any(SyncBatchRequest.class)))
                .thenReturn(new SyncBatchResponse(List.of(conflictItem)));

        // Simulate an empty response during pull
        when(httpClient.pullSync(any())).thenReturn(new SyncPullResponse(List.of(), Instant.now()));

        // Perform full synchronization
        SyncCoordinator.SyncResult result = coordinator.performFullSync();

        // Verify the coordinator behaved correctly
        // Check if markAsConflict was called exactly once for our UUID
        verify(orderDao, times(1)).markAsConflict(orderId);

        // Ensure markAsSynced was never called
        verify(orderDao, never()).markAsSynced(any(), any());

        // No items were successfully pushed
        assertEquals(0, result.pushedCount());
    }
}