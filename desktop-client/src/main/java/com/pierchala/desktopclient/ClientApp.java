package com.pierchala.desktopclient;

import com.pierchala.desktopclient.db.DatabaseManager;
import com.pierchala.desktopclient.db.OrderDao;
import com.pierchala.desktopclient.db.ProductDao;
import com.pierchala.desktopclient.model.LocalOrder;
import com.pierchala.desktopclient.net.SyncHttpClient;
import com.pierchala.desktopclient.sync.SyncCoordinator;
import com.pierchala.synccore.model.OrderDto;
import com.pierchala.synccore.model.OrderItemDto;
import com.pierchala.synccore.model.ProductDto;
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
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.stage.FileChooser;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

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
    private final ProductDao productDao = new ProductDao();
    private final SyncCoordinator syncCoordinator = new SyncCoordinator(orderDao, new SyncHttpClient());
    private final ObservableList<LocalOrder> orderList = FXCollections.observableArrayList();
    private final ObservableList<ProductDto> productList = FXCollections.observableArrayList();
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

        // Search and filter bar
        TextField searchField = new TextField();
        searchField.setPromptText("Search by order number or customer...");
        searchField.setPrefWidth(280);

        ComboBox<String> syncFilterBox = new ComboBox<>();
        syncFilterBox.getItems().addAll("ALL", "SYNCED", "DIRTY_CREATED", "DIRTY_UPDATED", "CONFLICT");
        syncFilterBox.setValue("ALL");

        Button clearFilterButton = new Button("Clear filters");
        Button exportCsvButton = new Button("Export CSV");
        exportCsvButton.setStyle("-fx-background-color: #0f766e; -fx-text-fill: white; -fx-font-weight: bold;");

        Label summaryLabel = new Label("Visible orders: 0 | Total: 0.00");
        summaryLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #1f2937;");

        Region filterSpacer = new Region();
        HBox.setHgrow(filterSpacer, Priority.ALWAYS);

        HBox filterBox = new HBox(10, new Label("Filter:"), searchField, syncFilterBox, clearFilterButton, exportCsvButton, filterSpacer, summaryLabel);
        filterBox.setPadding(new Insets(4, 10, 4, 10));
        filterBox.setAlignment(Pos.CENTER_LEFT);
        filterBox.setStyle("-fx-background-color: #f3f4f6; -fx-background-radius: 6;");

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

        TableColumn<LocalOrder, Integer> colItemsCount = new TableColumn<>("Items");
        colItemsCount.setCellValueFactory(cellData ->
                new SimpleObjectProperty<>(cellData.getValue().items() != null ? cellData.getValue().items().size() : 0));

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
        tableView.getColumns().addAll(colNumber, colCustomer, colAmount, colItemsCount, colStatus, colSync);

        // Wrap orderList in FilteredList and SortedList for real-time search and sorting
        FilteredList<LocalOrder> filteredOrders = new FilteredList<>(orderList, p -> true);

        Runnable updateFilterAndSummary = () -> {
            String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
            String selectedSyncStatus = syncFilterBox.getValue();

            filteredOrders.setPredicate(order -> {
                boolean matchesStatus = "ALL".equals(selectedSyncStatus)
                        || order.syncStatus().name().equals(selectedSyncStatus);

                boolean matchesQuery = query.isEmpty()
                        || order.orderNumber().toLowerCase().contains(query)
                        || order.customerName().toLowerCase().contains(query);

                return matchesStatus && matchesQuery;
            });

            BigDecimal visibleTotal = filteredOrders.stream()
                    .map(LocalOrder::totalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            summaryLabel.setText(String.format("Visible Orders: %d | Total: %s",
                    filteredOrders.size(), visibleTotal.toPlainString()));
        };

        searchField.textProperty().addListener((obs, oldVal, newVal) -> updateFilterAndSummary.run());
        syncFilterBox.valueProperty().addListener((obs, oldVal, newVal) -> updateFilterAndSummary.run());
        orderList.addListener((javafx.collections.ListChangeListener<LocalOrder>) c -> updateFilterAndSummary.run());

        clearFilterButton.setOnAction(e -> {
            searchField.clear();
            syncFilterBox.setValue("ALL");
        });

        SortedList<LocalOrder> sortedOrders = new SortedList<>(filteredOrders);
        sortedOrders.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sortedOrders);

        // Product catalog table view synced from central inventory
        TableView<ProductDto> productTableView = new TableView<>();
        productTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<ProductDto, String> colProductCode = new TableColumn<>("Product Code");
        colProductCode.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().productCode()));

        TableColumn<ProductDto, String> colProductName = new TableColumn<>("Product Name");
        colProductName.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().name()));

        TableColumn<ProductDto, BigDecimal> colCatalogPrice = new TableColumn<>("Unit Price");
        colCatalogPrice.setCellValueFactory(cellData ->
                new SimpleObjectProperty<>(cellData.getValue().unitPrice()));

        TableColumn<ProductDto, Integer> colStockQty = new TableColumn<>("Stock Quantity");
        colStockQty.setCellValueFactory(cellData ->
                new SimpleObjectProperty<>(cellData.getValue().stockQuantity()));

        productTableView.getColumns().addAll(colProductCode, colProductName, colCatalogPrice, colStockQty);
        productTableView.setItems(productList);

        exportCsvButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Export Visible Orders to CSV");
            fileChooser.setInitialFileName("orders_export.csv");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files (*.csv)", "*.csv"));

            File file = fileChooser.showSaveDialog(stage);
            if (file != null) {
                try {
                    StringBuilder csv = new StringBuilder("Order Number,Customer,Total Amount,ERP Status,Sync Status,Items Count\n");
                    for (LocalOrder order : sortedOrders) {
                        csv.append(String.format("\"%s\",\"%s\",%s,%s,%s,%d\n",
                                order.orderNumber().replace("\"", "\"\""),
                                order.customerName().replace("\"", "\"\""),
                                order.totalAmount().toPlainString(),
                                order.status(),
                                order.syncStatus().name(),
                                order.items() != null ? order.items().size() : 0));
                    }
                    Files.writeString(file.toPath(), csv.toString());
                    syncActivityLabel.setText("Exported " + sortedOrders.size() + " orders to " + file.getName());
                } catch (IOException ex) {
                    showAlert("Export Error", "Failed to save CSV file: " + ex.getMessage());
                }
            }
        });

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
            MenuItem editLineItem = new MenuItem("Edit item");
            MenuItem deleteLineItem = new MenuItem("Delete item");
            deleteLineItem.setStyle("-fx-text-fill: #dc2626;");

            editLineItem.setOnAction(event -> {
                OrderItemDto selectedItem = row.getItem();
                LocalOrder selectedOrder = tableView.getSelectionModel().getSelectedItem();
                if (selectedItem != null && selectedOrder != null) {
                    showEditItemDialog(selectedOrder, selectedItem);
                }
            });

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

            itemMenu.getItems().addAll(editLineItem, deleteLineItem);
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

            MenuItem compareConflictItem = new MenuItem("Compare conflict details");
            compareConflictItem.setStyle("-fx-text-fill: #7e22ce; -fx-font-weight: bold;");

            compareConflictItem.setOnAction(event -> {
                LocalOrder selected = row.getItem();
                if (selected != null && selected.syncStatus() == SyncStatus.CONFLICT) {
                    showConflictComparisonDialog(selected);
                }
            });

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

            menu.getItems().addAll(editItem, deleteItem, new SeparatorMenuItem(), compareConflictItem, forceLocalItem, discardLocalItem);

            // Dynamically show conflict resolution options only when the row is in CONFLICT status
            menu.setOnShowing(e -> {
                LocalOrder selected = row.getItem();
                boolean isConflict = selected != null && selected.syncStatus() == SyncStatus.CONFLICT;
                compareConflictItem.setVisible(isConflict);
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

        // Orders module view
        VBox ordersModuleBox = new VBox(10, formBox, filterBox, tableView, selectedOrderLabel, itemFormBox, itemsTableView);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        // ERP module tabs
        TabPane moduleTabPane = new TabPane();
        Tab ordersTab = new Tab("Sales & Orders", ordersModuleBox);
        ordersTab.setClosable(false);

        Tab inventoryTab = new Tab("Product Catalog (Inventory)", productTableView);
        inventoryTab.setClosable(false);

        moduleTabPane.getTabs().addAll(ordersTab, inventoryTab);

        // Main layout container
        VBox root = new VBox(10, moduleTabPane, statusBar);
        root.setPadding(new Insets(10));
        VBox.setVgrow(moduleTabPane, Priority.ALWAYS);

        Scene scene = new Scene(root, 920, 680);
        stage.setScene(scene);
        stage.show();

        // Load persisted orders and products from the local SQLite database
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
        productList.setAll(productDao.findAll());
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

    private void showEditItemDialog(LocalOrder parentOrder, OrderItemDto item) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit item - " + item.productName());
        dialog.setHeaderText("Modify line item for order: " + parentOrder.orderNumber());

        ButtonType saveButtonType = new ButtonType("Save changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
        TextField productField = new TextField(item.productName());
        TextField quantityField = new TextField(String.valueOf(item.quantity()));
        TextField unitPriceField = new TextField(item.unitPrice().toPlainString());

        VBox content = new VBox(10);
        content.setPadding(new Insets(15));
        content.getChildren().addAll(
            new Label("Product name: "), productField,
            new Label("Quantity: "), quantityField,
            new Label("Unit price: "), unitPriceField
        );

        dialog.getDialogPane().setContent(content);

        dialog.showAndWait().ifPresent(response -> {
            if (response == saveButtonType) {
                try {
                    String product = productField.getText().trim();
                    int quantity = Integer.parseInt(quantityField.getText().trim());
                    BigDecimal unitPrice = new BigDecimal(unitPriceField.getText().trim());

                    if (product.isEmpty() || quantity <= 0 || unitPrice.compareTo(BigDecimal.ZERO) < 0) {
                        showAlert("Validation Error", "Product name is required, quantity must be positive and price cannot be negative.");
                        return;
                    }

                    OrderItemDto updatedItem = new OrderItemDto(item.id(), product, quantity, unitPrice);
                    orderDao.updateItemInOrder(parentOrder.id(), item, updatedItem);

                    UUID orderId = parentOrder.id();
                    refreshTable();
                    orderList.stream()
                            .filter(o -> o.id().equals(orderId))
                            .findFirst()
                            .ifPresent(o -> tableView.getSelectionModel().select(o));

                    syncActivityLabel.setText("Item updated locally (offline).");
                } catch (NumberFormatException e) {
                    showAlert("Validation Error", "Quantity must be an integer and unit price must be a valid number");
                }
            }
        });
    }

    private void showConflictComparisonDialog(LocalOrder localOrder) {
        syncActivityLabel.setText("Fetching server version for conflict comparison...");

        CompletableFuture.supplyAsync(() -> {
            try {
                return new SyncHttpClient().fetchServerOrderById(localOrder.id());
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }).thenAccept(serverOrderOpt -> Platform.runLater(() -> {
            syncActivityLabel.setText("Ready");

            if (serverOrderOpt.isEmpty()) {
                showAlert("Conflict Details", "Order no longer exists on the server (it may have been deleted remotely).");
                return;
            }

            OrderDto serverOrder = serverOrderOpt.get();

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Conflict Comparison - " + localOrder.orderNumber());
            dialog.setHeaderText("Compare your local changes with the central server version");

            ButtonType keepLocalBtn = new ButtonType("Force Local Version", ButtonBar.ButtonData.LEFT);
            ButtonType keepServerBtn = new ButtonType("Discard Local (Accept Server)", ButtonBar.ButtonData.RIGHT);
            dialog.getDialogPane().getButtonTypes().addAll(keepLocalBtn, keepServerBtn, ButtonType.CANCEL);

            GridPane grid = new GridPane();
            grid.setHgap(20);
            grid.setVgap(10);
            grid.setPadding(new Insets(15));

            Label headerField = new Label("Field");
            Label headerLocal = new Label("Local (SQLite)");
            Label headerServer = new Label("Server (PostgreSQL)");
            headerField.setStyle("-fx-font-weight: bold;");
            headerLocal.setStyle("-fx-font-weight: bold; -fx-text-fill: #2563eb;");
            headerServer.setStyle("-fx-font-weight: bold; -fx-text-fill: #16a34a;");

            grid.addRow(0, headerField, headerLocal, headerServer);
            grid.addRow(1, new Label("Customer:"), new Label(localOrder.customerName()), new Label(serverOrder.customerName()));
            grid.addRow(2, new Label("Total Amount:"), new Label(localOrder.totalAmount().toPlainString()), new Label(serverOrder.totalAmount().toPlainString()));
            grid.addRow(3, new Label("ERP Status:"), new Label(localOrder.status()), new Label(serverOrder.status()));
            grid.addRow(4, new Label("Version:"), new Label(String.valueOf(localOrder.version())), new Label(String.valueOf(serverOrder.version())));
            grid.addRow(5, new Label("Items Count:"),
                    new Label(String.valueOf(localOrder.items() != null ? localOrder.items().size() : 0)),
                    new Label(String.valueOf(serverOrder.items() != null ? serverOrder.items().size() : 0)));

            dialog.getDialogPane().setContent(grid);

            dialog.showAndWait().ifPresent(response -> {
                if (response == keepLocalBtn) {
                    orderDao.forceLocalUpdate(localOrder.id(), serverOrder.version() + 1);
                    refreshTable();
                } else if (response == keepServerBtn) {
                    orderDao.markForServerOverwrite(localOrder.id());
                    refreshTable();
                    triggerSync(false);
                }
            });
        })).exceptionally(throwable -> {
            Platform.runLater(() -> {
                syncActivityLabel.setText("Failed to fetch server details.");
                showAlert("Connection Error", "Could not fetch server version: " + throwable.getCause().getMessage());
            });
            return null;
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}