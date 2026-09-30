package com.cowatch.client.ui;

import com.cowatch.client.util.Async;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;
import java.util.logging.Logger;

/**
 * JavaFX entry point. Builds the window, applies the dark theme and hands
 * control to {@link MainWindow}.
 */
public class CoWatchApp extends Application {

    private static final Logger log = Logger.getLogger(CoWatchApp.class.getName());

    public static final String CSS_PATH = "/com/cowatch/client/css/dark.css";

    @Override
    public void start(Stage stage) {
        MainWindow window = new MainWindow(stage);
        Scene scene = new Scene(window.getRoot(), 1400, 870);

        // A missing stylesheet must not take the window down with it — the app
        // is perfectly usable unstyled, and the log says what went missing.
        URL css = CoWatchApp.class.getResource(CSS_PATH);
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        else log.warning("Stylesheet not found on classpath: " + CSS_PATH);

        stage.setTitle("cowatch");
        stage.setScene(scene);
        // Minimum size is owned by MainWindow, which sets a floor low enough
        // for its single-column phone layout to be reachable. Do not raise it
        // here or that layout mode becomes dead code.
        stage.show();
    }

    @Override
    public void stop() {
        // MainWindow's close handler releases mpv and the server socket; this
        // drains the shared background pool so nothing outlives the window.
        Async.shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
