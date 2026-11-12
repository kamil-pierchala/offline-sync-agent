package com.pierchala.desktopclient;

import com.pierchala.desktopclient.db.DatabaseManager;
import com.pierchala.desktopclient.db.OrderDao;
import com.pierchala.desktopclient.model.LocalOrder;
import com.pierchala.desktopclient.net.SyncHttpClient;
import com.pierchala.desktopclient.sync.SyncCoordinator;
import com.pierchala.synccore.model.OrderItemDto;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientApp extends Application {

    private final OrderDao orderDao = new OrderDao();
    private final SyncCoordinator syncCoordinator = new SyncCoordinator(orderDao, new SyncHttpClient());
    private final ObservableList<LocalOrder> orderList = FXCollections.observableArrayList();
    private final ObservableList<OrderItemDto> selectedOrderItemsList = FXCollections.observableArrayList();
    private TableView<LocalOrder> tableView;
    private TableView<OrderItemDto> itemsTableView;

    // Background auto-sync scheduler and concurrency control
    private final ScheduledExecutorService autoSyncScheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);

    // Status bar controls
    private final Circle connectionDot = new Circle(5, Color.web("#9ca3af"));
    private final Label connectionStatusLabel = new Label("Connecting...");
    private final Label syncActivityLabel = new Label("Ready");

    @Override
    public void init() {
        // SQLite's initialization before loading UI
        DatabaseManager.initializeDatabase();
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Offline-First Sync Agent - Desktop Client");

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
                    } else if (item == SyncStatus.DIRTY_UPDATED) {
                        setStyle("-fx-text-fill: #2563eb; -fx-font-weight: bold;");
                    } else if (item == SyncStatus.SYNCED) {
                        setStyle("-fx-text-fill: #16a34a; -fx-font-weight: bold;");
                    } else if (item == SyncStatus.CONFLICT) {
                        setStyle("-fx-text-fill: #9333ea; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
                    }
                }
            }
        });

        // Add columns exactly once
        tableView.getColumns().addAll(colNumber, colCustomer, colAmount, colStatus, colSync);
        tableView.setItems(orderList);

        // Update detail items table when order selection changes
        Label selectedOrderLabel = new Label("Order items (Select an order above to view or add items):");
        selectedOrderLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #374151;");

        tableView.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            if (newSelection != null) {
                selectedOrderLabel.setText("Order items for " + newSelection.orderNumber() + ":");
                selectedOrderItemsList.setAll(newSelection.items());
            } else {
                selectedOrderLabel.setText("Order Items (Select an order above to view or add items):");
                selectedOrderItemsList.clear();
            }
        });

        // Order items detail table view
        itemsTableView = new TableView<>();
        itemsTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        itemsTableView.setPrefHeight(150);

        TableColumn<OrderItemDto, String> colProduct = new TableColumn<>("Product name");
        colProduct.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().productName()));

        TableColumn<OrderItemDto, Integer> colQty = new TableColumn<>("Quantity");
        colQty.setCellValueFactory(cellData ->
                new SimpleObjectProperty<>(cellData.getValue().quantity()));

        TableColumn<OrderItemDto, BigDecimal> colUnitPrice = new TableColumn<>("Unit price");
        colUnitPrice.setCellValueFactory(cellData ->
                new SimpleObjectProperty<>(cellData.getValue().unitPrice()));

        TableColumn<OrderItemDto, BigDecimal> colLineTotal = new TableColumn<>("Line total");
        colLineTotal.setCellValueFactory(cellData ->
                new SimpleObjectProperty<>(cellData.getValue().calculateLineTotal()));

        itemsTableView.getColumns().addAll(colProduct, colQty, colUnitPrice, colLineTotal);
        itemsTableView.setItems(selectedOrderItemsList);

        // Context menu for deleting line items
        itemsTableView.setRowFactory(tv -> {
            TableRow<OrderItemDto> row = new TableRow<>();
            ContextMenu itemMenu = new ContextMenu();
            MenuItem deleteLineItem = new MenuItem("Delete item");
            deleteLineItem.setStyle("-fx-text-fill: #dc2626;");

            deleteLineItem.setOnAction(event -> {
                OrderItemDto selectedItem = row.getItem();
                LocalOrder selectedOrder = tableView.getSelectionModel().getSelectedItem();
                if (selectedItem != null && selectedOrder != null) {
                    orderDao.removeItemFromOrder(selectedOrder.id(), selectedItem);
                    UUID orderId = selectedOrder.id();
                    refreshTable();
                    orderList.stream()
                            .filter(o -> o.id().equals(orderId))
                            .findFirst()
                            .ifPresent(o -> tableView.getSelectionModel().select(o));
                    syncActivityLabel.setText("Item removed locally (offline).");
                }
            });

            itemMenu.getItems().add(deleteLineItem);
            row.setOnContextMenuRequested(event -> {
                if (!row.isEmpty()) {
                    itemMenu.show(row, event.getScreenX(), event.getScreenY());
                }
            });
            return row;
        });

        // Form for adding line items to selected order
        TextField productField = new TextField();
        productField.setPromptText("Product name (e.g., Laptop)");

        TextField qtyField = new TextField();
        qtyField.setPromptText("Quantity");
        qtyField.setPrefWidth(70);

        TextField unitPriceField = new TextField();
        unitPriceField.setPromptText("Unit price");
        unitPriceField.setPrefWidth(110);

        Button addItemButton = new Button("Add item to selected order");
        addItemButton.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: bold;");

        HBox.setHgrow(productField, Priority.ALWAYS);
        HBox itemFormBox = new HBox(10, productField, qtyField, unitPriceField, addItemButton);
        itemFormBox.setAlignment(Pos.CENTER_LEFT);

        addItemButton.setOnAction(e -> {
            LocalOrder selectedOrder = tableView.getSelectionModel().getSelectedItem();
            if (selectedOrder == null) {
                showAlert("Selection required", "Please select an order from the top table first.");
                return;
            }

            String prod = productField.getText().trim();
            String qtyStr = qtyField.getText().trim();
            String priceStr = unitPriceField.getText().trim();

            if (prod.isEmpty() || qtyStr.isEmpty() || priceStr.isEmpty()) {
                showAlert("Form error", "All item fields are required.");
                return;
            }

            try {
                int qty = Integer.parseInt(qtyStr);
                BigDecimal unitPrice = new BigDecimal(priceStr);
                if (qty <= 0 || unitPrice.compareTo(BigDecimal.ZERO) < 0) {
                    showAlert("Validation error", "Quantity must be positive and price cannot be negative.");
                    return;
                }

                OrderItemDto newItem = new OrderItemDto(UUID.randomUUID(), prod, qty, unitPrice);
                orderDao.addItemToOrder(selectedOrder.id(), newItem);

                productField.clear();
                qtyField.clear();
                unitPriceField.clear();

                UUID selectedId = selectedOrder.id();
                refreshTable();
                // Restore selection to show updated items immediately
                orderList.stream()
                        .filter(o -> o.id().equals(selectedId))
                        .findFirst()
                        .ifPresent(o -> tableView.getSelectionModel().select(o));

                syncActivityLabel.setText("Item added locally (offline).");
            } catch (NumberFormatException ex) {
                showAlert("Validation error", "Quantity must be an integer and unit price must be a valid number.");
            }
        });

        // Context menu for row editing and deletion
        tableView.setRowFactory(tv -> {
            TableRow<LocalOrder> row = new TableRow<>();
            ContextMenu menu = new ContextMenu();

            MenuItem editItem = new MenuItem("Edit Order");
            MenuItem deleteItem = new MenuItem("Delete Order");
            deleteItem.setStyle("-fx-text-fill: #dc2626;");

            MenuItem forceLocalItem = new MenuItem("Resolve - force local version");
            MenuItem discardLocalItem = new MenuItem("Resolve - discard local version");
            forceLocalItem.setStyle("-fx-text-fill: #9333ea; -fx-font-weight: bold;");
            discardLocalItem.setStyle("-fx-text-fill: #9333ea; -fx-font-weight: bold;");

            editItem.setOnAction(event -> {
                LocalOrder selected = row.getItem();
                if (selected != null) {
                    showEditDialog(selected);
                }
            });

            deleteItem.setOnAction(event -> {
                LocalOrder selected = row.getItem();
                if (selected != null) {
                    orderDao.markAsDeleted(selected.id());
                    refreshTable();
                }
            });

            forceLocalItem.setOnAction(event -> {
                LocalOrder selected = row.getItem();
                if (selected != null && selected.syncStatus() == SyncStatus.CONFLICT) {
                    orderDao.forceLocalUpdate(selected.id(), selected.version() + 2);
                    refreshTable();
                }
            });

            discardLocalItem.setOnAction(event -> {
                LocalOrder selected = row.getItem();
                if (selected != null && selected.syncStatus() == SyncStatus.CONFLICT) {
                    orderDao.markForServerOverwrite(selected.id());
                    refreshTable();
                    triggerSync(false);
                }
            });

            menu.getItems().addAll(editItem, deleteItem, new SeparatorMenuItem(), forceLocalItem, discardLocalItem);

            // Dynamically show conflict resolution options only when the row is in CONFLICT status
            menu.setOnShowing(e -> {
                LocalOrder selected = row.getItem();
                boolean isConflict = selected != null && selected.syncStatus() == SyncStatus.CONFLICT;
                forceLocalItem.setVisible(isConflict);
                discardLocalItem.setVisible(isConflict);
            });

            row.setOnContextMenuRequested(e -> {
                if (!row.isEmpty()) {
                    menu.show(row, e.getScreenX(), e.getScreenY());
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
                syncActivityLabel.setText("Saved locally (offline).");
            } catch (NumberFormatException ex) {
                showAlert("Validation Error", "Amount must be a valid number (e.g., 150.50).");
            }
        });

        // Manual sync action
        syncButton.setOnAction(e -> triggerSync(false));

        // Bottom status bar layout (Online/Offline indicator + activity description)
        connectionStatusLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        syncActivityLabel.setStyle("-fx-text-fill: #4b5563; -fx-font-size: 11px;");

        HBox networkStatusBox = new HBox(6, connectionDot, connectionStatusLabel);
        networkStatusBox.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox statusBar = new HBox(12, networkStatusBox, syncActivityLabel, spacer);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 2, 4, 2));

        // Main layout container
        VBox root = new VBox(10, formBox, tableView, selectedOrderLabel, itemFormBox, itemsTableView, statusBar);
        root.setPadding(new Insets(10));
        VBox.setVgrow(tableView, Priority.ALWAYS);

        Scene scene = new Scene(root, 920, 680);
        stage.setScene(scene);
        stage.show();

        // Load persisted orders from the local SQLite database
        refreshTable();

        // Start background auto-sync every 10 seconds (initial delay: 2 seconds)
        autoSyncScheduler.scheduleWithFixedDelay(() -> triggerSync(true), 2, 10, TimeUnit.SECONDS);
    }

    private void triggerSync(boolean isBackground) {
        if (!isSyncing.compareAndSet(false, true)) {
            return; // Skip cycle if another synchronization is already running
        }

        if (!isBackground) {
            syncActivityLabel.setText("Synchronizing with server...");
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                return syncCoordinator.performFullSync();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }).thenAccept(result -> Platform.runLater(() -> {
            setConnectionState(true);
            LocalOrder previousSelection = tableView.getSelectionModel().getSelectedItem();
            refreshTable();
            if (previousSelection != null) {
                orderList.stream()
                        .filter(o -> o.id().equals(previousSelection.id()))
                        .findFirst()
                        .ifPresent(o -> tableView.getSelectionModel().select(o));
            }
            isSyncing.set(false);

            if (result.pushedCount() > 0 || result.pulledCount() > 0 || !isBackground) {
                syncActivityLabel.setText(String.format("Last sync: %02d:%02d:%02d (Pushed: %d, Pulled: %d)",
                        LocalTime.now().getHour(),
                        LocalTime.now().getMinute(),
                        LocalTime.now().getSecond(),
                        result.pushedCount(), result.pulledCount()));
            }
        })).exceptionally(throwable -> {
            Platform.runLater(() -> {
                setConnectionState(false);
                isSyncing.set(false);
                syncActivityLabel.setText("Server unreachable. Working in offline mode.");

                if (!isBackground) {
                    showAlert("Sync Error", "Could not reach server: " + throwable.getCause().getMessage());
                }
            });
            return null;
        });
    }

    private void setConnectionState(boolean isOnline) {
        if (isOnline) {
            connectionDot.setFill(Color.web("#16a34a"));
            connectionStatusLabel.setText("Online");
            connectionStatusLabel.setStyle("-fx-text-fill: #16a34a; -fx-font-weight: bold; -fx-font-size: 11px;");
        } else {
            connectionDot.setFill(Color.web("#dc2626"));
            connectionStatusLabel.setText("Offline");
            connectionStatusLabel.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold; -fx-font-size: 11px;");
        }
    }

    @Override
    public void stop() {
        // Shutdown background scheduler gracefully on window close
        autoSyncScheduler.shutdownNow();
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

    private void showEditDialog(LocalOrder order) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit Order - " + order.orderNumber());
        dialog.setHeaderText("Modify order details (saved locally in SQLite)");

        ButtonType saveButtonType = new ButtonType("Save Changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        TextField customerField = new TextField(order.customerName());
        TextField amountField = new TextField(order.totalAmount().toPlainString());

        ComboBox<String> statusBox = new ComboBox<>();
        statusBox.getItems().addAll("NEW", "IN_PROGRESS", "COMPLETED", "CANCELLED");
        statusBox.setValue(order.status());

        VBox content = new VBox(10);
        content.setPadding(new Insets(15));
        content.getChildren().addAll(
                new Label("Customer Name:"), customerField,
                new Label("Total Amount:"), amountField,
                new Label("ERP Status:"), statusBox
        );

        dialog.getDialogPane().setContent(content);

        dialog.showAndWait().ifPresent(response -> {
            if (response == saveButtonType) {
                try {
                    String cust = customerField.getText().trim();
                    BigDecimal amt = new BigDecimal(amountField.getText().trim());
                    String status = statusBox.getValue();

                    if (cust.isEmpty()) {
                        showAlert("Validation Error", "Customer name cannot be empty.");
                        return;
                    }

                    orderDao.update(order.id(), cust, amt, status);
                    refreshTable();
                } catch (NumberFormatException e) {
                    showAlert("Validation Error", "Amount must be a valid number.");
                }
            }
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}