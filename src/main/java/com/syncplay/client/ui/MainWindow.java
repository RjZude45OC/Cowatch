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

        offsetPanel.setSyncEngine(syncEngine);

        buildLayout();
        wireShutdown();
    }

    private void buildLayout() {
        root.setTop(headerBar.getNode());
        root.getStyleClass().add("app-root");

        VBox.setVgrow(offsetPanel.getNode(),      Priority.ALWAYS);
        VBox.setVgrow(playbackControls.getNode(), Priority.ALWAYS);

        VBox leftContent = new VBox(8,
                configPanel.getNode(),
                offsetPanel.getNode(),
                playbackControls.getNode());
        leftContent.setPadding(new Insets(10));
        leftContent.setFillWidth(true);

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

    // ── Launch (single file) ────────────────────────────────────────────

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

    // ── Launch (playlist — first launch only, multi-entry) ──────────────
    // Spawns MPV once with the full --playlist + --playlist-start.
    // After this, advancing episodes should go through onNextEpisode(),
    // NOT another call to this method — that would kill and restart MPV
    // (window flicker, re-handshake, and on macOS a teardown race where
    // the old process's IPC socket isn't fully released before relaunch).

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

    // ── Next episode ─────────────────────────────────────────────────────
    // IPC-only: sends "playlist-next" to the already-running MPV instance.
    // No process kill, no relaunch, no new socket handshake.

    public void onNextEpisode() {
        if (!mpv.isRunning()) {
            // Defensive — MediaPanel disables the Next button when MPV isn't
            // running, so this is only reachable if MPV died unexpectedly
            // between the button becoming enabled and being clicked.
            showError("MPV Not Running", "The player closed unexpectedly. Please launch again to continue.");
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

    public MediaPanel getMediaPanel() { return mediaPanel; }

    private final ExecutorService mpvExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mpv-cmd");
                t.setDaemon(true);
                return t;
            });

    public void doPlay()                   { mpvExecutor.submit(() -> syncEngine.localPlay()); }
    public void doPause()                  { mpvExecutor.submit(() -> syncEngine.localPause()); }
    public void doSeek(double pos)         { mpvExecutor.submit(() -> syncEngine.localSeek(pos)); }
    public void doSkipForward(double s)    { mpvExecutor.submit(() -> syncEngine.localSkipForward(s)); }
    public void doSkipBackward(double s)   { mpvExecutor.submit(() -> syncEngine.localSkipBackward(s)); }

    // ── Error dialogs ─────────────────────────────────────────────────────

    /**
     * Show a detailed error dialog with a title and body message.
     * Body is shown in a scrollable text area so long messages (e.g. install instructions)
     * are fully readable without the dialog being cut off.
     */
    public void showError(String title, String body) {
        showError(title, body, null);
    }

    public void showError(String title, String body, Runnable onClose) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("SyncPlay — " + title);
            alert.setHeaderText(title);
            alert.initOwner(stage);

            // Use expandable text area for long messages
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

    /** Convenience overload — simple one-string error with no callback. */
    public void showError(String msg) {
        showError("Error", msg, null);
    }

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