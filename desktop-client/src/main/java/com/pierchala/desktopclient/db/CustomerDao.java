package com.pierchala.desktopclient.db;

import com.pierchala.synccore.model.CustomerDto;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CustomerDao {

    public void upsertAll(List<CustomerDto> customers) {
        String sql = """
                INSERT INTO customers (id, company_name, tax_id, email, city, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    company_name = excluded.company_name,
                    tax_id = excluded.tax_id,
                    email = excluded.email,
                    city = excluded.city,
                    updated_at = excluded.updated_at
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            for (CustomerDto c : customers) {
                preparedStatement.setString(1, c.id().toString());
                preparedStatement.setString(2, c.companyName());
                preparedStatement.setString(3, c.taxId());
                preparedStatement.setString(4, c.email());
                preparedStatement.setString(5, c.city());
                preparedStatement.setString(6, c.updatedAt().toString());
                preparedStatement.addBatch();
            }
            preparedStatement.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert customers into SQLite", e);
        }
    }

    public List<CustomerDto> findAll() {
        String sql = "SELECT * FROM customers ORDER BY company_name ASC";
        List<CustomerDto> list = new ArrayList<>();

        try (Connection connection = DatabaseManager.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                list.add(new CustomerDto(
                        UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("company_name"),
                        resultSet.getString("tax_id"),
                        resultSet.getString("email"),
                        resultSet.getString("city"),
                        Instant.parse(resultSet.getString("updated_at"))
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load customers from SQLite", e);
        }
        return list;
    }
}
