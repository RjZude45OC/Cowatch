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
        VBox leftContent = new VBox(8,
                configPanel.getNode(),
                offsetPanel.getNode(),
                playbackControls.getNode());
        leftContent.setPadding(new Insets(10));
        leftContent.setFillWidth(true);

        ScrollPane leftScroll = new ScrollPane(leftContent);
        leftScroll.setFitToWidth(true);
        leftScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        leftScroll.getStyleClass().add("left-scroll");

        // ── Centre column ─────────────────────────────────────────────
        VBox centre = new VBox(mediaPanel.getNode());
        centre.setPadding(new Insets(10, 8, 10, 0));
        centre.setFillWidth(true);
        VBox.setVgrow(mediaPanel.getNode(), Priority.ALWAYS);

        // ── Right column ──────────────────────────────────────────────
        VBox right = new VBox(chatPanel.getNode());
        right.setPadding(new Insets(10, 10, 10, 0));
        right.setFillWidth(true);
        VBox.setVgrow(chatPanel.getNode(), Priority.ALWAYS);

        // ── SplitPane — all three columns user-resizable ──────────────
        SplitPane split = new SplitPane(leftScroll, centre, right);
        split.getStyleClass().add("app-split");
        // Initial divider positions as fractions of total width
        // left ~27%, centre ~47%, right ~26%
        split.setDividerPositions(0.27, 0.73);

        root.setCenter(split);
    }

    public void onConnect(SessionConfig config) {
        boolean ok = server.connect(config.serverHost, config.serverPort);
        if (!ok) {
            showError("Could not connect to " + config.serverHost + ":" + config.serverPort);
            return;
        }

        syncEngine = new SyncEngine(mpv, server);
        offsetPanel.setSyncEngine(syncEngine);

        server.addMessageHandler(msg ->
                Platform.runLater(() -> chatPanel.appendMessage(msg)));

        server.joinRoom(config.roomId, config.username);
        headerBar.setConnected(config.serverHost, config.roomId);
        configPanel.onConnected();
        mediaPanel.setMode(config.mode);
        playbackControls.setEnabled(false);
        headerBar.setOnDisconnect(() -> {
            server.disconnect();
            syncEngine.close(); // or just stop heartbeat if you want to keep MPV running
            headerBar.setDisconnected();
            playbackControls.setEnabled(false); // or true if local mode should continue
            chatPanel.appendRaw("[system] Disconnected from server");
            configPanel.onDisconnected();
        });
    }

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

    public MediaPanel getMediaPanel() { return mediaPanel; }

    public void doPlay()                    { if (syncEngine.getServerConnection() != null) syncEngine.localPlay(); }
    public void doPause()                   { if (syncEngine.getServerConnection() != null) syncEngine.localPause(); }
    public void doSeek(double pos)          { if (syncEngine.getServerConnection() != null) syncEngine.localSeek(pos); }
    public void doSkipForward(double secs)  { if (syncEngine.getServerConnection() != null) syncEngine.localSkipForward(secs); }
    public void doSkipBackward(double secs) { if (syncEngine.getServerConnection() != null) syncEngine.localSkipBackward(secs); }

    public void showError(String msg) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
            alert.setTitle("SyncPlay Error");
            alert.initOwner(stage);
            alert.show();
        });
    }

    public void appendChat(String msg) {
        Platform.runLater(() -> chatPanel.appendRaw(msg));
    }

    private void wireShutdown() {
        stage.setOnCloseRequest(e -> {
            server.disconnect();
            mpv.close();
        });
    }

    public Region getRoot() { return root; }
}