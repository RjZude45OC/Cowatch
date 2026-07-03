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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

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
    private final TrackingPanel    trackingPanel;

    private static final Logger log = Logger.getLogger(MainWindow.class.getName());

    public MainWindow(Stage stage) {
        this.stage = stage;

        syncEngine = new SyncEngine(mpv, server);

        headerBar        = new HeaderBar();
        configPanel      = new ConfigPanel(this);
        mediaPanel       = new MediaPanel(this);
        playbackControls = new PlaybackControls(this);
        chatPanel        = new ChatPanel(server);
        offsetPanel      = new OffsetPanel();
        trackingPanel    = new TrackingPanel(this);

        wireSyncEngine();

        buildLayout();
        wireShutdown();
    }

    /**
     * Wires every panel that depends on the current SyncEngine instance.
     * MUST be called both here (constructor) and in onConnect() — onConnect()
     * replaces syncEngine with a fresh instance (it needs the freshly-connected
     * ServerConnection), and the old instance's listeners would otherwise be
     * silently abandoned, breaking AniList's 80%-watched trigger and episode
     * tracking with no error or log output at all.
     */
    private void wireSyncEngine() {
        offsetPanel.setSyncEngine(syncEngine);
        mediaPanel.setSyncEngine(syncEngine);
    }

    private void buildLayout() {
        root.setTop(headerBar.getNode());
        root.getStyleClass().add("app-root");

        VBox.setVgrow(offsetPanel.getNode(),      Priority.ALWAYS);
        VBox.setVgrow(playbackControls.getNode(), Priority.ALWAYS);

        VBox leftContent = new VBox(8,
                configPanel.getNode(),
                offsetPanel.getNode(),
                playbackControls.getNode(),
                trackingPanel.getNode());
        leftContent.setPadding(new Insets(10));
        leftContent.setFillWidth(true);

        ScrollPane leftScroll = new ScrollPane(leftContent);
        leftScroll.setFitToWidth(true);
        leftScroll.getStyleClass().add("edge-to-edge");

        VBox centre = new VBox(mediaPanel.getNode());
        centre.setPadding(new Insets(10));
        centre.setFillWidth(true);
        VBox.setVgrow(mediaPanel.getNode(), Priority.ALWAYS);

        VBox right = new VBox(chatPanel.getNode());
        right.setPadding(new Insets(10));
        right.setFillWidth(true);
        VBox.setVgrow(chatPanel.getNode(), Priority.ALWAYS);

        SplitPane split = new SplitPane(leftScroll, centre, right);
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
        wireSyncEngine();

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

    // ── Launch (single file / URL) ────────────────────────────────────────

    public void onLaunch(String mpvUrl) {
        new Thread(() -> {
            String error = mpv.launch(mpvUrl);
            Platform.runLater(() -> {
                boolean ok = (error == null);
                mediaPanel.onMpvLaunched(ok);
                if (ok) {
                    playbackControls.setEnabled(true);
                    chatPanel.appendRaw("[system] MPV launched ✓");
                } else {
                    showError("Failed to launch MPV", error);
                }
            });
            if (error == null) {
                try { mpv.waitForExit(); } catch (InterruptedException ignored) {}
                Platform.runLater(this::onMpvClosed);
            }
        }, "mpv-launcher").start();
    }

    // ── Launch (playlist) ─────────────────────────────────────────────────

    public void onLaunchPlaylist(List<String> entries, int startIndex) {
        new Thread(() -> {
            String error = mpv.launch(entries, startIndex);
            Platform.runLater(() -> {
                boolean ok = (error == null);
                mediaPanel.onMpvLaunched(ok);
                if (ok) {
                    playbackControls.setEnabled(true);
                    chatPanel.appendRaw("[system] MPV launched ✓ (" + entries.size() + " episodes)");
                } else {
                    showError("Failed to launch MPV", error);
                }
            });
            if (error == null) {
                try { mpv.waitForExit(); } catch (InterruptedException ignored) {}
                Platform.runLater(this::onMpvClosed);
            }
        }, "mpv-launcher").start();
    }

    // ── Next episode (IPC only) ───────────────────────────────────────────

    public void onNextEpisode() {
        if (!mpv.isRunning()) {
            showError("MPV Not Running",
                    "The player closed unexpectedly. Please launch again to continue.");
            mediaPanel.onMpvClosed(true);
            return;
        }
        mpvExecutor.submit(mpv::nextChapter);
    }

    private void onMpvClosed() {
        playbackControls.setEnabled(false);
        chatPanel.appendRaw("[system] MPV closed");
        mediaPanel.onMpvClosed(true);
    }

    // ── Playback actions ──────────────────────────────────────────────────

    public MediaPanel    getMediaPanel()    { return mediaPanel; }
    public TrackingPanel getTrackingPanel() { return trackingPanel; }

    private final ExecutorService mpvExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mpv-cmd");
                t.setDaemon(true);
                return t;
            });

    public void doPlay()                 { mpvExecutor.submit(() -> syncEngine.localPlay()); }
    public void doPause()                { mpvExecutor.submit(() -> syncEngine.localPause()); }
    public void doSeek(double pos)       { mpvExecutor.submit(() -> syncEngine.localSeek(pos)); }
    public void doSkipForward(double s)  { mpvExecutor.submit(() -> syncEngine.localSkipForward(s)); }
    public void doSkipBackward(double s) { mpvExecutor.submit(() -> syncEngine.localSkipBackward(s)); }

    // ── Error dialogs ─────────────────────────────────────────────────────

    public void showError(String title, String body) { showError(title, body, null); }

    public void showError(String title, String body, Runnable onClose) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("SyncPlay — " + title);
            alert.setHeaderText(title);
            alert.initOwner(stage);

            TextArea textArea = new TextArea(body);
            textArea.setEditable(false);
            textArea.setWrapText(true);
            textArea.setMaxWidth(Double.MAX_VALUE);
            textArea.setMaxHeight(Double.MAX_VALUE);
            GridPane.setVgrow(textArea, Priority.ALWAYS);
            GridPane.setHgrow(textArea, Priority.ALWAYS);

            GridPane expContent = new GridPane();
            expContent.setMaxWidth(Double.MAX_VALUE);
            expContent.add(textArea, 0, 0);

            alert.getDialogPane().setExpandableContent(expContent);
            alert.getDialogPane().setExpanded(true);
            alert.getDialogPane().setPrefWidth(520);

            alert.showAndWait();
            if (onClose != null) onClose.run();
        });
    }

    public void showError(String msg) { showError("Error", msg, null); }

    // ── Shutdown ──────────────────────────────────────────────────────────

    private void wireShutdown() {
        stage.setOnCloseRequest(e -> {
            mpvExecutor.shutdownNow();
            server.disconnect();
            mpv.close();
        });
    }

    public Region getRoot() { return root; }
}