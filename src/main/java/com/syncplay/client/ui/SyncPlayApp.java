package com.syncplay.client.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.nio.charset.StandardCharsets;

/**
 * JavaFX entry point.
 * Launches the main SyncPlay window.
 */
public class SyncPlayApp extends Application {

    public static final String CSS_PATH =
            "/com/syncplay/client/css/dark.css";

    static {
        // Force UTF-8 encoding on all platforms, especially Windows with Chinese locale
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");
        System.setProperty("stdout.encoding", "UTF-8");
        System.setProperty("stderr.encoding", "UTF-8");
    }

    @Override
    public void start(Stage stage) {
        MainWindow window = new MainWindow(stage);
        Scene scene = new Scene(window.getRoot(), 1400, 850);
        scene.getStylesheets().add(getClass().getResource(CSS_PATH).toExternalForm());

        stage.setTitle("SyncPlay");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(600);
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