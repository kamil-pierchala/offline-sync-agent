package com.pierchala.desktopclient;

import com.pierchala.desktopclient.db.DatabaseManager;
import com.pierchala.desktopclient.db.OrderDao;
import com.pierchala.desktopclient.model.LocalOrder;
import com.pierchala.synccore.model.SyncStatus;
import javafx.application.Application;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class ClientApp extends Application {

    private final OrderDao orderDao = new OrderDao();
    private final ObservableList<LocalOrder> orderList = FXCollections.observableArrayList();
    private TableView<LocalOrder> tableView;

    @Override
    public void init() {
        // SQLite's initialization before loading UI
        DatabaseManager.initializeDatabase();
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Offline-First Sync Agent — Desktop Client");

        // Data input form
        TextField orderNumberField = new TextField();
        orderNumberField.setPromptText("Order Number (e.g., ORD-001)");
        orderNumberField.setPrefWidth(220);

        TextField customerNameField = new TextField();
        customerNameField.setPromptText("Customer Name");
        customerNameField.setPrefWidth(180);

        TextField amountField = new TextField();
        amountField.setPromptText("Total Amount (e.g., 1500.00)");
        amountField.setPrefWidth(190);

        Button addButton = new Button("Add Order (Offline / Local DB)");
        addButton.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold;");

        HBox.setHgrow(orderNumberField, Priority.ALWAYS);
        HBox.setHgrow(customerNameField, Priority.ALWAYS);
        HBox.setHgrow(amountField, Priority.ALWAYS);

        HBox formBox = new HBox(10, orderNumberField, customerNameField, amountField, addButton);
        formBox.setPadding(new Insets(10));
        formBox.setAlignment(Pos.CENTER_LEFT);

        // Orders table view
        tableView = new TableView<>();
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<LocalOrder, String> colNumber = new TableColumn<>("Order Number");
        colNumber.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().orderNumber()));

        TableColumn<LocalOrder, String> colCustomer = new TableColumn<>("Customer");
        colCustomer.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().customerName()));

        TableColumn<LocalOrder, BigDecimal> colAmount = new TableColumn<>("Total Amount");
        colAmount.setCellValueFactory(cellData ->
                new SimpleObjectProperty<>(cellData.getValue().totalAmount()));

        TableColumn<LocalOrder, String> colStatus = new TableColumn<>("ERP Status");
        colStatus.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().status()));

        TableColumn<LocalOrder, SyncStatus> colSync = new TableColumn<>("Sync Status");
        colSync.setCellValueFactory(cellData ->
                new SimpleObjectProperty<>(cellData.getValue().syncStatus()));

        // Highlight sync status for better visibility
        colSync.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(SyncStatus item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item.name());
                    if (item == SyncStatus.DIRTY_CREATED) {
                        setStyle("-fx-text-fill: #ea580c; -fx-font-weight: bold;");
                    } else if (item == SyncStatus.SYNCED) {
                        setStyle("-fx-text-fill: #16a34a; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
                    }
                }
            }
        });

        // Add columns exactly once
        tableView.getColumns().addAll(colNumber, colCustomer, colAmount, colStatus, colSync);
        tableView.setItems(orderList);

        // Order creation action
        addButton.setOnAction(e -> {
            String num = orderNumberField.getText().trim();
            String cust = customerNameField.getText().trim();
            String amtStr = amountField.getText().trim();

            if (num.isEmpty() || cust.isEmpty() || amtStr.isEmpty()) {
                showAlert("Form Error", "All fields are required.");
                return;
            }

            try {
                BigDecimal amount = new BigDecimal(amtStr);
                LocalOrder newOrder = new LocalOrder(
                        UUID.randomUUID(),
                        num,
                        cust,
                        amount,
                        "NEW",
                        Instant.now(),
                        1L,
                        SyncStatus.DIRTY_CREATED
                );

                orderDao.insert(newOrder);
                refreshTable();

                orderNumberField.clear();
                customerNameField.clear();
                amountField.clear();
            } catch (NumberFormatException ex) {
                showAlert("Validation Error", "Amount must be a valid number (e.g., 150.50).");
            }
        });

        // Main layout container
        VBox root = new VBox(10, formBox, tableView);
        root.setPadding(new Insets(10));
        VBox.setVgrow(tableView, Priority.ALWAYS);

        Scene scene = new Scene(root, 800, 500);
        stage.setScene(scene);
        stage.show();

        // Load persisted orders from the local SQLite database
        refreshTable();
    }

    private void refreshTable() {
        orderList.setAll(orderDao.findAllActive());
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}