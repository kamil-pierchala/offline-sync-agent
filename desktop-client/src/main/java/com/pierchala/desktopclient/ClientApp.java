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
    private TableView<LocalOrder> tableView;

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
                    } else {
                        setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
                    }
                }
            }
        });

        // Add columns exactly once
        tableView.getColumns().addAll(colNumber, colCustomer, colAmount, colStatus, colSync);
        tableView.setItems(orderList);

        // Context menu for row editing and deletion
        ContextMenu contextMenu = new ContextMenu();
        MenuItem editItem = new MenuItem("Edit Order");
        MenuItem deleteItem = new MenuItem("Delete Order");
        deleteItem.setStyle("-fx-text-fill: #dc2626;");

        editItem.setOnAction(event -> {
            LocalOrder selected = tableView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showEditDialog(selected);
            }
        });

        deleteItem.setOnAction(event -> {
            LocalOrder selected = tableView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                orderDao.markAsDeleted(selected.id());
                refreshTable();
                syncActivityLabel.setText("Order marked for deletion (offline).");
            }
        });

        contextMenu.getItems().addAll(editItem, deleteItem);

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
        VBox root = new VBox(10, formBox, tableView, statusBar);
        root.setPadding(new Insets(10));
        VBox.setVgrow(tableView, Priority.ALWAYS);

        Scene scene = new Scene(root, 880, 520);
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
            refreshTable();
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