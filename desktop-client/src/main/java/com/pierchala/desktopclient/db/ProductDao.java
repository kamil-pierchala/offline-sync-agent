package com.pierchala.desktopclient.db;

import com.pierchala.synccore.model.ProductDto;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ProductDao {

    public void upsertAll(List<ProductDto> products) {
        String sql = """
            INSERT INTO products (id, product_code, name, unit_price, stock_quantity, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                product_code = excluded.product_code,
                name = excluded.name,
                unit_price = excluded.unit_price,
                stock_quantity = excluded.stock_quantity,
                updated_at = excluded.updated_at
            """;

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ProductDto p : products) {
                ps.setString(1, p.id().toString());
                ps.setString(2, p.productCode());
                ps.setString(3, p.name());
                ps.setBigDecimal(4, p.unitPrice());
                ps.setInt(5, p.stockQuantity());
                ps.setString(6, p.updatedAt().toString());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert products into SQLite", e);
        }
    }

    public List<ProductDto> findAll() {
        String sql = "SELECT * FROM products ORDER BY name ASC";
        List<ProductDto> list = new ArrayList<>();

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new ProductDto(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("product_code"),
                        rs.getString("name"),
                        rs.getBigDecimal("unit_price"),
                        rs.getInt("stock_quantity"),
                        Instant.parse(rs.getString("updated_at"))
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load products from SQLite", e);
        }
        return list;
    }
}