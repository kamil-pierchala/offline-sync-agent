package com.pierchala.desktopclient;

import com.pierchala.desktopclient.db.DatabaseManager;
import com.pierchala.desktopclient.db.OrderDao;
import com.pierchala.desktopclient.model.LocalOrder;
import com.pierchala.desktopclient.net.SyncHttpClient;
import com.pierchala.desktopclient.sync.SyncCoordinator;
import com.pierchala.synccore.model.SyncStatus;
import javafx.application.Application;
import javafx.application.Platform;
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
import java.util.concurrent.CompletableFuture;

public class ClientApp extends Application {

    private final OrderDao orderDao = new OrderDao();
    private final SyncCoordinator syncCoordinator = new SyncCoordinator(orderDao, new SyncHttpClient());
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

        Button syncButton = new Button("Sync Now");
        syncButton.setStyle("-fx-background-color: #16a34a; -fx-text-fill: white; -fx-font-weight: bold;");

        Label statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-text-fill: #4b5563; -fx-font-size: 11px;");

        HBox.setHgrow(orderNumberField, Priority.ALWAYS);
        HBox.setHgrow(customerNameField, Priority.ALWAYS);
        HBox.setHgrow(amountField, Priority.ALWAYS);

        HBox formBox = new HBox(10, orderNumberField, customerNameField, amountField, addButton, syncButton);
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

        // Context menu for row deletion
        ContextMenu contextMenu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("Delete Order");
        deleteItem.setStyle("-fx-text-fill: #dc2626;");

        deleteItem.setOnAction(event -> {
            LocalOrder selected = tableView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                orderDao.markAsDeleted(selected.id());
                refreshTable();
                statusLabel.setText("Order marked for deletion (offline).");
            }
        });

        contextMenu.getItems().add(deleteItem);

        tableView.setRowFactory(tv -> {
            TableRow<LocalOrder> row = new TableRow<>();
            row.emptyProperty().addListener((obs, wasEmpty, isEmpty) -> {
                if (isEmpty) {
                    row.setContextMenu(null);
                } else {
                    row.setContextMenu(contextMenu);
                }
            });
            return row;
        });

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
                statusLabel.setText("Saved locally (offline).");
            } catch (NumberFormatException ex) {
                showAlert("Validation Error", "Amount must be a valid number (e.g., 150.50).");
            }
        });

        // Trigger sync action in background
        syncButton.setOnAction(e -> {
            syncButton.setDisable(true);
            statusLabel.setText("Synchronizing with server...");

            CompletableFuture.supplyAsync(() -> {
                try {
                    return syncCoordinator.performFullSync();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }).thenAccept(result -> Platform.runLater(() -> {
                refreshTable();
                syncButton.setDisable(false);
                statusLabel.setText(String.format("Sync complete. Pushed: %d, Pulled: %d.",
                        result.pushedCount(), result.pulledCount()));
            })).exceptionally(throwable -> {
                Platform.runLater(() -> {
                    syncButton.setDisable(false);
                    statusLabel.setText("Sync failed: " + throwable.getCause().getMessage());
                    showAlert("Sync Error", "Could not reach server: " + throwable.getCause().getMessage());
                });
                return null;
            });
        });

        // Main layout container
        VBox root = new VBox(10, formBox, tableView, statusLabel);
        root.setPadding(new Insets(10));
        VBox.setVgrow(tableView, Priority.ALWAYS);

        Scene scene = new Scene(root, 880, 520);
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