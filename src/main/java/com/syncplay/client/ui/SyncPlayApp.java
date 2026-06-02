package com.syncplay.client.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * JavaFX entry point.
 * Launches the main SyncPlay window.
 */
public class SyncPlayApp extends Application {

    public static final String CSS_PATH =
        "/com/syncplay/client/css/dark.css";

    @Override
    public void start(Stage stage) {
        MainWindow window = new MainWindow(stage);
        Scene scene = new Scene(window.getRoot(), 1100, 750);
        scene.getStylesheets().add(getClass().getResource(CSS_PATH).toExternalForm());

        stage.setTitle("SyncPlay");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(650);
        stage.show();
    }

    @Override
    public void stop() {
        // Cleanup handled by MainWindow's shutdown hook
    }

    public static void main(String[] args) {
        launch(args);
    }
}
