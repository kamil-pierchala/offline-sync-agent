package com.pierchala.desktopclient;

import com.pierchala.desktopclient.db.DatabaseManager;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class ClientApp extends Application {

    @Override
    public void init() {
        // SQLite's initialization before loading UI
        DatabaseManager.initializeDatabase();
    }

    @Override
    public void start(Stage stage) {
        Label label = new Label("Offline-First Sync Agent Client");
        Scene scene = new Scene(new StackPane(label), 640, 480);
        stage.setTitle("Offline Sync Agent");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}