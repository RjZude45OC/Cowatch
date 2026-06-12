package com.syncplay.client.ui;

import com.syncplay.client.model.*;
import com.syncplay.client.mpv.MPVController;
import com.syncplay.client.network.ServerConnection;
import com.syncplay.client.sync.SyncEngine;
import com.syncplay.client.ui.controllers.*;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainWindow {

    private final Stage      stage;
    private final BorderPane root = new BorderPane();

    private final MPVController    mpv    = new MPVController();
    private final ServerConnection server = new ServerConnection();
    private SyncEngine syncEngine;

    private final HeaderBar        headerBar;
    private final ConfigPanel      configPanel;
    private final MediaPanel       mediaPanel;
    private final ChatPanel        chatPanel;
    private final PlaybackControls playbackControls;
    private final OffsetPanel      offsetPanel;

    public MainWindow(Stage stage) {
        this.stage = stage;

        syncEngine = new SyncEngine(mpv, server);

        headerBar        = new HeaderBar();
        configPanel      = new ConfigPanel(this);
        mediaPanel       = new MediaPanel(this);
        playbackControls = new PlaybackControls(this);
        chatPanel        = new ChatPanel(server);
        offsetPanel      = new OffsetPanel();

        offsetPanel.setSyncEngine(syncEngine);

        buildLayout();
        wireShutdown();
    }

    private void buildLayout() {
        root.setTop(headerBar.getNode());
        root.getStyleClass().add("app-root");

        // ── Left column ───────────────────────────────────────────────
        // Connection card has fixed height; offset + playback grow to fill.
        VBox.setVgrow(offsetPanel.getNode(),      Priority.ALWAYS);
        VBox.setVgrow(playbackControls.getNode(), Priority.ALWAYS);

        VBox leftContent = new VBox(8,
                configPanel.getNode(),        // connection only (no sync mode)
                offsetPanel.getNode(),
                playbackControls.getNode());
        leftContent.setPadding(new Insets(10));
        leftContent.setFillWidth(true);

        // No ScrollPane — left column resizes with the window.

        // ── Centre + Right (unchanged) ────────────────────────────────
        VBox centre = new VBox(mediaPanel.getNode());
        centre.setPadding(new Insets(10));
        centre.setFillWidth(true);
        VBox.setVgrow(mediaPanel.getNode(), Priority.ALWAYS);

        VBox right = new VBox(chatPanel.getNode());
        right.setPadding(new Insets(10));
        right.setFillWidth(true);
        VBox.setVgrow(chatPanel.getNode(), Priority.ALWAYS);

        SplitPane split = new SplitPane(leftContent, centre, right);
        split.getStyleClass().add("app-split");
        split.setDividerPositions(0.27, 0.72);
        root.setCenter(split);
    }

    // ── Connect ───────────────────────────────────────────────────────────

    public void onConnect(SessionConfig config) throws IOException {
        boolean ok = server.connect(config.serverHost, config.serverPort);
        if (!ok) {
            throw new IOException("Could not connect to " + config.serverHost + ":" + config.serverPort);
        }

        double savedOffset = offsetPanel.getOffset();

        syncEngine = new SyncEngine(mpv, server);
        syncEngine.setOffset(savedOffset);
        offsetPanel.setSyncEngine(syncEngine);

        server.addMessageHandler(msg ->
                Platform.runLater(() -> chatPanel.appendMessage(msg)));

        server.joinRoom(config.roomId, config.username);
        headerBar.setConnected(config.serverHost, config.roomId);
        configPanel.onConnected();
        mediaPanel.setMode(config.mode);

        if (!mpv.isRunning()) playbackControls.setEnabled(false);

        headerBar.setOnDisconnect(() -> {
            server.disconnect();
            syncEngine.close();
            headerBar.setDisconnected();
            if (!mpv.isRunning()) playbackControls.setEnabled(false);
            chatPanel.appendRaw("[system] Disconnected from server");
            configPanel.onDisconnected();
        });
    }

    // ── Launch ────────────────────────────────────────────────────────────

    public void onLaunch(String mpvUrl) {
        new Thread(() -> {
            boolean ok = mpv.launch(mpvUrl);
            Platform.runLater(() -> {
                mediaPanel.onMpvLaunched(ok);
                if (ok) {
                    playbackControls.setEnabled(true);
                    chatPanel.appendRaw("[system] MPV launched ✓");
                } else {
                    showError("Failed to launch MPV.\nMake sure MPV is installed and on your PATH.");
                }
            });
            if (ok) {
                try { mpv.waitForExit(); } catch (InterruptedException ignored) {}
                Platform.runLater(this::onMpvClosed);
            }
        }, "mpv-launcher").start();
    }

    private void onMpvClosed() {
        playbackControls.setEnabled(false);
        chatPanel.appendRaw("[system] MPV closed");
        mediaPanel.onMpvClosed(true);
    }

    // ── Playback actions ──────────────────────────────────────────────────

    public MediaPanel getMediaPanel() { return mediaPanel; }

    private final ExecutorService mpvExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mpv-cmd");
                t.setDaemon(true);
                return t;
            });

    public void doPlay()               { mpvExecutor.submit(() -> syncEngine.localPlay()); }
    public void doPause()              { mpvExecutor.submit(() -> syncEngine.localPause()); }
    public void doSeek(double pos)     { mpvExecutor.submit(() -> syncEngine.localSeek(pos)); }
    public void doSkipForward(double s){ mpvExecutor.submit(() -> syncEngine.localSkipForward(s)); }
    public void doSkipBackward(double s){ mpvExecutor.submit(() -> syncEngine.localSkipBackward(s)); }

    // ── Helpers ───────────────────────────────────────────────────────────

    public void showError(String msg, Runnable onClose) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
            alert.setTitle("SyncPlay Error");
            alert.initOwner(stage);
            alert.showAndWait();
            if (onClose != null) onClose.run();
        });
    }
    public void showError(String msg) {
        showError(msg, null);
    }

    private void wireShutdown() {
        stage.setOnCloseRequest(e -> {
            mpvExecutor.shutdownNow();
            server.disconnect();
            mpv.close();
        });
    }

    public Region getRoot() { return root; }
}