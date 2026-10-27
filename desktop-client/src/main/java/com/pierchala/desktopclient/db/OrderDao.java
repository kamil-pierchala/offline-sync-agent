package com.pierchala.desktopclient.db;

import com.pierchala.desktopclient.model.LocalOrder;
import com.pierchala.synccore.model.OrderDto;
import com.pierchala.synccore.model.SyncStatus;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class OrderDao {

    public void insert(LocalOrder order) {
        String sql = """
            INSERT INTO orders (id, order_number, customer_name, total_amount, status, updated_at, version, sync_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setStatementParams(ps, order);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert order: " + order.id(), e);
        }
    }

    public void update(LocalOrder order) {
        String sql = """
            UPDATE orders
            SET order_number = ?, customer_name = ?, total_amount = ?, status = ?, updated_at = ?, version = ?, sync_status = ?
            WHERE id = ?
            """;

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, order.orderNumber());
            ps.setString(2, order.customerName());
            ps.setBigDecimal(3, order.totalAmount());
            ps.setString(4, order.status());
            ps.setString(5, order.updatedAt().toString());
            ps.setLong(6, order.version());
            ps.setString(7, order.syncStatus().name());
            ps.setString(8, order.id().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update order: " + order.id(), e);
        }
    }

    public List<LocalOrder> findAllActive() {
        String sql = "SELECT * FROM orders WHERE sync_status != 'DIRTY_DELETED' ORDER BY updated_at DESC";
        return queryOrders(sql);
    }

    public List<LocalOrder> findPendingSync() {
        String sql = "SELECT * FROM orders WHERE sync_status IN ('DIRTY_CREATED', 'DIRTY_UPDATED', 'DIRTY_DELETED')";
        return queryOrders(sql);
    }

    public Optional<LocalOrder> findById(UUID id) {
        String sql = "SELECT * FROM orders WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find order by id: " + id, e);
        }
        return Optional.empty();
    }

    public void markAsSynced(UUID id, Long newVersion) {
        String sql = "UPDATE orders SET sync_status = 'SYNCED', version = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, newVersion);
            ps.setString(2, id.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark order as synced: " + id, e);
        }
    }

    public void removeDeleted(UUID id) {
        String sql = "DELETE FROM orders WHERE id = ? AND sync_status = 'DIRTY_DELETED'";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to purge deleted order: " + id, e);
        }
    }

    private List<LocalOrder> queryOrders(String sql) {
        List<LocalOrder> orders = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                orders.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query orders", e);
        }
        return orders;
    }

    private LocalOrder mapRow(ResultSet rs) throws SQLException {
        return new LocalOrder(
                UUID.fromString(rs.getString("id")),
                rs.getString("order_number"),
                rs.getString("customer_name"),
                rs.getBigDecimal("total_amount"),
                rs.getString("status"),
                Instant.parse(rs.getString("updated_at")),
                rs.getLong("version"),
                SyncStatus.valueOf(rs.getString("sync_status"))
        );
    }

    private void setStatementParams(PreparedStatement ps, LocalOrder order) throws SQLException {
        ps.setString(1, order.id().toString());
        ps.setString(2, order.orderNumber());
        ps.setString(3, order.customerName());
        ps.setBigDecimal(4, order.totalAmount());
        ps.setString(5, order.status());
        ps.setString(6, order.updatedAt().toString());
        ps.setLong(7, order.version());
        ps.setString(8, order.syncStatus().name());
    }

    public Optional<Instant> getLastSyncTimestamp() {
        String sql = "SELECT val FROM sync_metadata WHERE key = 'last_sync_timestamp'";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return Optional.of(Instant.parse(rs.getString("val")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    public void setLastSyncTimestamp(Instant timestamp) {
        String sql = """
            INSERT INTO sync_metadata (key, val) VALUES ('last_sync_timestamp', ?)
            ON CONFLICT(key) DO UPDATE SET val = excluded.val
        """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, timestamp.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void upsertFromServer(OrderDto dto) {
        // Upsert record from server ensuring local status is SYNCED
        String sql = """
            INSERT INTO orders (id, order_number, customer_name, total_amount, status, updated_at, version, sync_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'SYNCED')
            ON CONFLICT(id) DO UPDATE SET
                order_number = excluded.order_number,
                customer_name = excluded.customer_name,
                total_amount = excluded.total_amount,
                status = excluded.status,
                updated_at = excluded.updated_at,
                version = excluded.version,
                sync_status = 'SYNCED'
            WHERE orders.sync_status = 'SYNCED' OR excluded.version >= orders.version
        """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dto.id().toString());
            ps.setString(2, dto.orderNumber());
            ps.setString(3, dto.customerName());
            ps.setBigDecimal(4, dto.totalAmount());
            ps.setString(5, dto.status());
            ps.setString(6, dto.updatedAt().toString());
            ps.setLong(7, dto.version());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void markAsDeleted(UUID id) {
        String sql = "UPDATE orders SET sync_status = 'DIRTY_DELETED' WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark order as deleted: " + id, e);
        }
    }
}