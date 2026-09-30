package com.cowatch.client.ui;

import com.cowatch.client.model.*;
import com.cowatch.client.mpv.MPVController;
import com.cowatch.client.network.ServerConnection;
import com.cowatch.client.sync.SyncEngine;
import com.cowatch.client.ui.controllers.*;
import com.cowatch.client.util.Async;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Side;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class MainWindow {

    // ── Responsive breakpoints (px of window width) ──────────────────────
    private static final double PHONE_MAX  = 760;
    private static final double TABLET_MAX = 1180;

    private enum LayoutMode { PHONE, TABLET, DESKTOP }

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

    // Containers are created once; panels move between them as the
    // layout mode changes (JavaFX auto-reparents nodes).
    private ScrollPane leftScroll;
    private VBox       leftContent;
    private VBox       centre;
    private VBox       right;

    private LayoutMode layoutMode;

    /** Serialises mpv IPC commands so UI clicks never block the FX thread. */
    private final ExecutorService mpvExecutor =
            Executors.newSingleThreadExecutor(Async.namedDaemonFactory("mpv-cmd"));

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
        // Registered once, not per connection — re-registering on every
        // connect would stack handlers and run the teardown repeatedly.
        headerBar.setOnDisconnect(this::onDisconnect);

        buildLayout();
        wireShutdown();
    }

    private void wireSyncEngine() {
        offsetPanel.setSyncEngine(syncEngine);
        mediaPanel.setSyncEngine(syncEngine);
    }

    // ── Responsive layout ───────────────────────────────────────────────

    private void buildLayout() {
        root.setTop(headerBar.getNode());
        root.getStyleClass().add("app-root");

        VBox.setVgrow(offsetPanel.getNode(),      Priority.ALWAYS);
        VBox.setVgrow(playbackControls.getNode(), Priority.ALWAYS);

        leftContent = new VBox(8,
                configPanel.getNode(),
                offsetPanel.getNode(),
                playbackControls.getNode(),
                trackingPanel.getNode());
        leftContent.setPadding(new Insets(10));
        leftContent.setFillWidth(true);

        leftScroll = new ScrollPane(leftContent);
        leftScroll.setFitToWidth(true);
        // dark.css keys the column's tighter card padding and transparent
        // viewport off .left-scroll — the name must match, or that whole
        // block silently does nothing.
        leftScroll.getStyleClass().add("left-scroll");

        centre = new VBox(mediaPanel.getNode());
        centre.setPadding(new Insets(10));
        centre.setFillWidth(true);
        VBox.setVgrow(mediaPanel.getNode(), Priority.ALWAYS);

        right = new VBox(chatPanel.getNode());
        right.setPadding(new Insets(10));
        right.setFillWidth(true);
        VBox.setVgrow(chatPanel.getNode(), Priority.ALWAYS);

        // Floor low enough that the single-column phone layout is reachable.
        // CoWatchApp must not raise this.
        stage.setMinWidth(340);
        stage.setMinHeight(520);

        // Re-classify on every resize — cheap, only swaps on threshold crossing
        root.widthProperty().addListener((obs, oldW, newW) ->
                applyLayout(classify(newW.doubleValue())));

        // Tactile press-squish for every button, once a Scene exists
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) Motion.installPressSquish(newScene);
        });

        applyLayout(LayoutMode.DESKTOP);  // default; listener corrects on first layout
    }

    private LayoutMode classify(double width) {
        if (width < PHONE_MAX)  return LayoutMode.PHONE;
        if (width < TABLET_MAX) return LayoutMode.TABLET;
        return LayoutMode.DESKTOP;
    }

    private void applyLayout(LayoutMode mode) {
        if (mode == layoutMode) return;
        boolean firstLayout = (layoutMode == null);
        layoutMode = mode;

        root.getStyleClass().removeAll(
                "layout-phone", "layout-tablet", "layout-desktop", "compact");

        switch (mode) {
            case PHONE -> {
                // Single column + bottom tab navigation
                root.getStyleClass().addAll("layout-phone", "compact");
                TabPane tabs = new TabPane(
                        new Tab("Controls", leftScroll),
                        new Tab("Media",    centre),
                        new Tab("Chat",     right));
                tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
                tabs.setSide(Side.BOTTOM);
                tabs.setTabMinWidth(72);
                tabs.setTabMinHeight(44);
                root.setCenter(tabs);
            }
            case TABLET -> {
                // Controls column  |  media stacked over chat
                root.getStyleClass().add("layout-tablet");
                SplitPane mediaChat = new SplitPane(centre, right);
                mediaChat.setOrientation(Orientation.VERTICAL);
                mediaChat.setDividerPositions(0.62);
                SplitPane split = new SplitPane(leftScroll, mediaChat);
                split.getStyleClass().add("app-split");
                // Deliberately narrow: the shows/episodes lists in the media
                // column need the horizontal room more than the controls do.
                split.setDividerPositions(0.30);
                root.setCenter(split);
            }
            case DESKTOP -> {
                // Classic three-column theatre
                root.getStyleClass().add("layout-desktop");
                SplitPane split = new SplitPane(leftScroll, centre, right);
                split.getStyleClass().add("app-split");
                split.setDividerPositions(0.27, 0.72);
                root.setCenter(split);
            }
        }

        if (firstLayout) {
            // One orchestrated curtain-up, after the first layout pass
            Platform.runLater(() -> Motion.staggeredReveal(root));
        } else {
            Motion.crossfade(root.getCenter());
        }
    }

    // ── Connect / disconnect ──────────────────────────────────────────────

    public void onConnect(SessionConfig config) throws IOException {
        boolean ok = server.connect(config.serverHost, config.serverPort);
        if (!ok) {
            throw new IOException("Could not connect to " + config.serverHost + ":" + config.serverPort);
        }

        double savedOffset = offsetPanel.getOffset();

        // A SyncEngine binds its server-event handler at construction time, so
        // a new connection needs a new engine. Release the old one's scheduler
        // first, or every reconnect leaks a timer thread.
        syncEngine.close();
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
    }

    private void onDisconnect() {
        server.disconnect();
        syncEngine.close();
        headerBar.setDisconnected();
        if (!mpv.isRunning()) playbackControls.setEnabled(false);
        chatPanel.appendRaw("[system] Disconnected from server");
        configPanel.onDisconnected();
    }

    // ── Launch ────────────────────────────────────────────────────────────

    /** Launches a single file or URL in mpv. */
    public void onLaunch(String mpvUrl) {
        launch(() -> mpv.launch(mpvUrl), "MPV launched ✓");
    }

    /** Launches a playlist in mpv, starting at {@code startIndex}. */
    public void onLaunchPlaylist(List<String> entries, int startIndex) {
        launch(() -> mpv.launch(entries, startIndex),
               "MPV launched ✓ (" + entries.size() + " episodes)");
    }

    /**
     * Runs an mpv launch off the FX thread, reports the outcome, then waits for
     * the player to exit so the UI can reset itself.
     *
     * @param starter   returns null on success, or the failure message
     * @param okMessage chat line shown when the launch succeeds
     */
    private void launch(Supplier<String> starter, String okMessage) {
        Async.run("mpv-launcher", () -> {
            String error = starter.get();
            Platform.runLater(() -> {
                boolean ok = (error == null);
                mediaPanel.onMpvLaunched(ok);
                if (ok) {
                    playbackControls.setEnabled(true);
                    chatPanel.appendRaw("[system] " + okMessage);
                } else {
                    showError("Failed to launch MPV", error);
                }
            });
            if (error == null) {
                try {
                    mpv.waitForExit();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Platform.runLater(this::onMpvClosed);
            }
        });
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

    // ── Panel accessors ───────────────────────────────────────────────────

    public MediaPanel    getMediaPanel()    { return mediaPanel; }
    public TrackingPanel getTrackingPanel() { return trackingPanel; }

    /** Last playback position mpv reported, in seconds. */
    public double getPosition() { return syncEngine.getPosition(); }

    // ── Playback actions ──────────────────────────────────────────────────

    public void doPlay()                 { mpvExecutor.submit(() -> syncEngine.localPlay()); }
    public void doPause()                { mpvExecutor.submit(() -> syncEngine.localPause()); }
    public void doSeek(double pos)       { mpvExecutor.submit(() -> syncEngine.localSeek(pos)); }
    public void doSkipForward(double s)  { mpvExecutor.submit(() -> syncEngine.localSkipForward(s)); }
    public void doSkipBackward(double s) { mpvExecutor.submit(() -> syncEngine.localSkipBackward(s)); }

    // ── Error dialogs ─────────────────────────────────────────────────────

    public void showError(String msg) { showError("Error", msg, null); }

    public void showError(String title, String body) { showError(title, body, null); }

    public void showError(String title, String body, Runnable onClose) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("cowatch — " + title);
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

    // ── Shutdown ──────────────────────────────────────────────────────────

    private void wireShutdown() {
        stage.setOnCloseRequest(e -> {
            mpvExecutor.shutdownNow();
            syncEngine.close();
            server.disconnect();
            mpv.close();
        });
    }

    public Region getRoot() { return root; }
}
