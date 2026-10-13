package com.cowatch.client.ui;

import com.cowatch.client.util.Async;
import com.cowatch.client.util.LogBuffer;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.io.InputStream;
import java.net.URL;
import java.util.logging.Logger;

/**
 * JavaFX entry point. Builds the window, applies the dark theme and hands
 * control to {@link MainWindow}.
 */
public class CoWatchApp extends Application {

    private static final Logger log = Logger.getLogger(CoWatchApp.class.getName());

    public static final String CSS_PATH = "/com/cowatch/client/css/dark.css";

    /** Window icon sizes; the OS picks the closest for title bar and taskbar. */
    private static final int[] ICON_SIZES = { 16, 24, 32, 48, 64, 128, 256 };

    @Override
    public void start(Stage stage) {
        MainWindow window = new MainWindow(stage);
        // Opens at roughly two thirds of the old 1400x870: the settings column
        // is gone, chat starts hidden and the transport bar only appears
        // while mpv runs, so there is nothing left to fill that space.
        Scene scene = new Scene(window.getRoot(), 960, 620);

        // A missing stylesheet must not take the window down with it — the app
        // is perfectly usable unstyled, and the log says what went missing.
        URL css = CoWatchApp.class.getResource(CSS_PATH);
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        else log.warning("Stylesheet not found on classpath: " + CSS_PATH);

        // Ctrl+` (or F12) opens the log and live state. It must not outlive the
        // main window, or closing cowatch would leave the JVM running behind it.
        DebugConsole console = new DebugConsole(stage, window::debugInfo);
        DebugConsole.installToggleKey(scene, console::toggle);
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> console.close());

        stage.setTitle("cowatch");
        addIcons(stage);
        stage.setScene(scene);
        // Minimum size is owned by MainWindow, which sets a floor low enough
        // for its single-column phone layout to be reachable. Do not raise it
        // here or that layout mode becomes dead code.
        stage.show();
    }

    /** Missing icons are logged and skipped, like a missing stylesheet. */
    private static void addIcons(Stage stage) {
        for (int size : ICON_SIZES) {
            String path = "/icons/cowatch-" + size + ".png";
            try (InputStream in = CoWatchApp.class.getResourceAsStream(path)) {
                if (in != null) stage.getIcons().add(new Image(in));
                else log.warning("Icon not found on classpath: " + path);
            } catch (Exception e) {
                log.warning("Could not load icon " + path + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void stop() {
        // MainWindow's close handler releases mpv and the server socket; this
        // drains the shared background pool so nothing outlives the window.
        Async.shutdown();
    }

    public static void main(String[] args) {
        LogBuffer.install();
        launch(args);
    }
}
