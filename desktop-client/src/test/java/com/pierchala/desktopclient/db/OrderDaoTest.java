package com.pierchala.desktopclient.db;

import com.pierchala.desktopclient.model.LocalOrder;
import com.pierchala.synccore.model.OrderDto;
import com.pierchala.synccore.model.OrderItemDto;
import com.pierchala.synccore.model.SyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderDaoTest {

    @TempDir
    Path tempDir;

    private OrderDao orderDao;

    @BeforeEach
    void setUp() {
        // Point DatabaseManager to an isolated temporary SQLite file for each test
        Path tempDbFile = tempDir.resolve("test_client_storage.db");
        DatabaseManager.setDatabaseFile(tempDbFile.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();
        orderDao = new OrderDao();
    }

    @Test
    void shouldInsertOrderWithItemsAndRetrieveThemById() {
        // A new local order with two line items
        UUID orderId = UUID.randomUUID();
        OrderItemDto item1 = new OrderItemDto(UUID.randomUUID(), "Mechanical Keyboard", 2, new BigDecimal("350.00"));
        OrderItemDto item2 = new OrderItemDto(UUID.randomUUID(), "USB-C dock", 1, new BigDecimal("600.00"));

        LocalOrder newOrder = new LocalOrder(
                orderId,
                "ORD-DAO-01",
                "Tech Corp",
                new BigDecimal("1300.00"),
                "NEW",
                Instant.now(),
                1L,
                SyncStatus.DIRTY_CREATED,
                List.of(item1, item2)
        );

        // Persisting the order in SQLite
        orderDao.insert(newOrder);

        // Order and its nested items should be loaded
        Optional<LocalOrder> loadedOpt = orderDao.findById(orderId);
        assertTrue(loadedOpt.isPresent());

        LocalOrder loaded = loadedOpt.get();
        assertEquals("ORD-DAO-01", loaded.orderNumber());
        assertEquals(0, new BigDecimal("1300.00").compareTo(loaded.totalAmount()));
        assertEquals(2, loaded.items().size());
        assertEquals("Mechanical Keyboard", loaded.items().get(0).productName());
    }

    @Test
    void shouldRecalculateOrderTotalWhenAddingAndRemovingItems() {
        // An existing synced order with zero items and 0.00 total amount
        UUID orderId = UUID.randomUUID();
        LocalOrder baseOrder = new LocalOrder(
                orderId,
                "ORD-DAO-02",
                "Retail Partner",
                BigDecimal.ZERO,
                "NEW",
                Instant.now(),
                1L,
                SyncStatus.SYNCED,
                List.of()
        );
        orderDao.insert(baseOrder);

        OrderItemDto addedItem = new OrderItemDto(UUID.randomUUID(), "27-inch Monitor", 3, new BigDecimal("200.00"));
        orderDao.addItemToOrder(orderId, addedItem);

        // Total amount should be 600.00 and status should be updated to DIRTY_UPDATED
        LocalOrder afterAdd = orderDao.findById(orderId).orElseThrow();
        assertEquals(0, new BigDecimal("600.00").compareTo(afterAdd.totalAmount()));
        assertEquals(SyncStatus.DIRTY_UPDATED, afterAdd.syncStatus());
        assertEquals(1, afterAdd.items().size());

        // Removing the line item
        orderDao.removeItemFromOrder(orderId, addedItem);

        // Total amount should return to 0 and items list should be empty
        LocalOrder afterRemove = orderDao.findById(orderId).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(afterRemove.totalAmount()));
        assertTrue(afterRemove.items().isEmpty());
    }

    @Test
    void shouldNotOverwriteOrderInConflictWhenUpsertingFromServer() {
        // Local order marked as CONFLICT
        UUID orderId = UUID.randomUUID();
        LocalOrder conflictOrder = new LocalOrder(
                orderId,
                "ORD-CONFLICT-99",
                "Local Unsaved Customer",
                new BigDecimal("500.00"),
                "IN_PROGRESS",
                Instant.now(),
                1L,
                SyncStatus.CONFLICT,
                List.of()
        );
        orderDao.insert(conflictOrder);

        // Server sends a newer version during background pull
        OrderDto serverDto = new OrderDto(
                orderId,
                "ORD-CONFLICT-99",
                "Server Overwrite Attempt",
                new BigDecimal("9999.00"),
                "COMPLETED",
                Instant.now(),
                5L,
                List.of(new OrderItemDto(UUID.randomUUID(), "Server Item", 1, new BigDecimal("9999.00")))
        );
        orderDao.upsertFromServer(serverDto);

        // Local conflicted record and its empty items list must remain untouched
        LocalOrder currentLocal = orderDao.findById(orderId).orElseThrow();
        assertEquals(SyncStatus.CONFLICT, currentLocal.syncStatus());
        assertEquals("Local Unsaved Customer", currentLocal.customerName());
        assertEquals(0, new BigDecimal("500.00").compareTo(currentLocal.totalAmount()));
        assertTrue(currentLocal.items().isEmpty());
    }
}
