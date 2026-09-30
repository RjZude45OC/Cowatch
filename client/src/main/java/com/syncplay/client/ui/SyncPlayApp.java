package com.cowatch.client.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * JavaFX entry point.
 * Launches the main cowatch window.
 */
public class cowatchApp extends Application {

    public static final String CSS_PATH =
        "/com/cowatch/client/css/dark.css";

    @Override
    public void start(Stage stage) {
        MainWindow window = new MainWindow(stage);
        Scene scene = new Scene(window.getRoot(), 900, 680);
        scene.getStylesheets().add(getClass().getResource(CSS_PATH).toExternalForm());

        stage.setTitle("cowatch");
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
