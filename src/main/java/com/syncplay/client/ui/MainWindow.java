package com.syncplay.client.ui;

import com.syncplay.client.model.*;
import com.syncplay.client.mpv.MPVController;
import com.syncplay.client.network.ServerConnection;
import com.syncplay.client.sync.SyncEngine;
import com.syncplay.client.ui.controllers.*;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/**
 * Root window layout.
 *
 * ┌──────────────────────────────────────────────────────┐
 * │  Header: logo + connection status                    │
 * ├──────────────────────────┬───────────────────────────┤
 * │  Left panel              │  Right panel              │
 * │  • Mode selector         │  • Jellyfin browser OR    │
 * │  • Server config         │    local file picker      │
 * │  • Room config           │  • Playback controls      │
 * │  • User config           │  • Chat                   │
 * └──────────────────────────┴───────────────────────────┘
 */
public class MainWindow {

    private final Stage stage;
    private final BorderPane root = new BorderPane();

    // Core services
    private final MPVController    mpv    = new MPVController();
    private final ServerConnection server = new ServerConnection();
    private SyncEngine syncEngine;

    // Sub-controllers
    private final HeaderBar        headerBar;
    private final ConfigPanel      configPanel;
    private final MediaPanel       mediaPanel;
    private final ChatPanel        chatPanel;
    private final PlaybackControls playbackControls;

    public MainWindow(Stage stage) {
        this.stage = stage;

        syncEngine = new SyncEngine(mpv, server);

        headerBar        = new HeaderBar();
        configPanel      = new ConfigPanel(this);
        mediaPanel       = new MediaPanel(this);
        playbackControls = new PlaybackControls(this);
        chatPanel        = new ChatPanel(server);

        buildLayout();
        wireShutdown();
    }

    private void buildLayout() {
        // ── Header ────────────────────────────────────────────────────────
        root.setTop(headerBar.getNode());

        // ── Left panel: config + playback, scrollable ─────────────────────
        VBox leftContent = new VBox(12, configPanel.getNode(), playbackControls.getNode());
        leftContent.setPadding(new Insets(14));
        leftContent.setFillWidth(true);

        ScrollPane leftScroll = new ScrollPane(leftContent);
        leftScroll.setFitToWidth(true);
        leftScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        leftScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        leftScroll.setMinWidth(320);
        leftScroll.setPrefWidth(380);

        // ── Right panel: media browser grows, chat fixed at bottom ────────
        VBox right = new VBox(12, mediaPanel.getNode(), chatPanel.getNode());
        right.setPadding(new Insets(14, 14, 14, 0));
        right.setFillWidth(true);
        VBox.setVgrow(mediaPanel.getNode(), Priority.ALWAYS);
        VBox.setVgrow(chatPanel.getNode(),  Priority.NEVER);

        // ── Body: left fixed-ish, right takes all remaining space ─────────
        HBox body = new HBox(0, leftScroll, right);
        body.setFillHeight(true);
        HBox.setHgrow(leftScroll, Priority.NEVER);
        HBox.setHgrow(right,      Priority.ALWAYS);

        root.setCenter(body);
        root.setStyle("-fx-background-color: #0f1117;");
    }

    // ─── Called by ConfigPanel when user clicks Connect ───────────────────

    public void onConnect(SessionConfig config) {
        boolean ok = server.connect(config.serverHost, config.serverPort);
        if (!ok) {
            showError("Could not connect to " + config.serverHost + ":" + config.serverPort);
            return;
        }

        syncEngine = new SyncEngine(mpv, server);       // registers its handler via setMessageHandler
        server.addMessageHandler(msg -> {               // ADDS chat handler on top — doesn't replace
            Platform.runLater(() -> chatPanel.appendMessage(msg));
        });

        server.joinRoom(config.roomId, config.username);
        headerBar.setConnected(config.serverHost, config.roomId);
        configPanel.onConnected();
        mediaPanel.setMode(config.mode);
        playbackControls.setEnabled(false);
    }

    // ─── Called by MediaPanel when user launches MPV ──────────────────────

    public void onLaunch(String mpvUrl) {
        new Thread(() -> {
            boolean ok = mpv.launch(mpvUrl);
            Platform.runLater(() -> {
                mediaPanel.onMpvLaunched(ok);
                if (ok) {
                    playbackControls.setEnabled(true);
                    chatPanel.appendRaw("[system] MPV launched ✓");
                } else {
                    showError("Failed to launch MPV.\n" +
                              "Make sure MPV is installed and on your PATH.");
                }
            });
        }, "mpv-launcher").start();
    }

    public MediaPanel getMediaPanel() { return mediaPanel; }

    // ─── Playback actions (called by PlaybackControls) ────────────────────

    public void doPlay()                    { if (syncEngine.getServerConnection() != null) syncEngine.localPlay();}
    public void doPause()                   { if (syncEngine.getServerConnection() != null) syncEngine.localPause();}
    public void doSeek(double pos)          { if (syncEngine.getServerConnection() != null) syncEngine.localSeek(pos);}
    public void doSkipForward(double secs)  { if (syncEngine.getServerConnection() != null) syncEngine.localSkipForward(secs);}
    public void doSkipBackward(double secs) { if (syncEngine.getServerConnection() != null) syncEngine.localSkipBackward(secs);}

    // ─── Helpers ──────────────────────────────────────────────────────────

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
