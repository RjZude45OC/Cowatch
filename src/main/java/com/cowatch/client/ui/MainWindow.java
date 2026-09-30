package com.cowatch.client.ui;

import com.cowatch.client.model.*;
import com.cowatch.client.mpv.MPVController;
import com.cowatch.client.network.ServerConnection;
import com.cowatch.client.sync.SyncEngine;
import com.cowatch.client.ui.controllers.*;
import com.cowatch.client.util.Async;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The application shell.
 *
 * <p>The window is deliberately thin: a header, the media browser, and a
 * transport bar that appears only while mpv runs. Chat is an optional second
 * column, and the server / sync-mode / tracking settings live in
 * {@link SettingsDialog}. Everything that a session configures once is one
 * click away rather than permanently on screen, which is what lets the window
 * open at roughly two thirds of its old size.
 */
public class MainWindow {

    /** Below this width the chat column stacks over the media panel instead. */
    private static final double SIDE_BY_SIDE_MIN = 900;

    /** Below this width paddings and control sizes tighten (.compact). */
    private static final double COMPACT_MAX = 720;

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
    private final SettingsDialog   settings;

    /** Wrappers created once; the centre is re-composed as chat toggles. */
    private final VBox centre = new VBox();
    private final VBox right  = new VBox();

    private boolean chatShown = false;
    private boolean sideBySide = true;

    /** Serialises mpv IPC commands so UI clicks never block the FX thread. */
    private final ExecutorService mpvExecutor =
            Executors.newSingleThreadExecutor(Async.namedDaemonFactory("mpv-cmd"));

    public MainWindow(Stage stage) {
        this.stage = stage;

        syncEngine = new SyncEngine(mpv, server);

        headerBar        = new HeaderBar();
        configPanel      = new ConfigPanel(this);
        mediaPanel       = new MediaPanel(this);
        offsetPanel      = new OffsetPanel();
        playbackControls = new PlaybackControls(this, offsetPanel);
        chatPanel        = new ChatPanel(server);
        trackingPanel    = new TrackingPanel(this);
        settings         = new SettingsDialog(stage, configPanel, trackingPanel);

        wireSyncEngine();
        // Registered once, not per connection - re-registering on every
        // connect would stack handlers and run the teardown repeatedly.
        headerBar.setOnDisconnect(this::onDisconnect);
        headerBar.setOnConnect(() -> settings.show(0));
        headerBar.setOnOpenSettings(settings::show);
        headerBar.setOnToggleChat(this::toggleChat);

        buildLayout();
        wireShutdown();
    }

    private void wireSyncEngine() {
        offsetPanel.setSyncEngine(syncEngine);
        mediaPanel.setSyncEngine(syncEngine);
    }

    // --- Layout -----------------------------------------------------------

    private void buildLayout() {
        root.getStyleClass().add("app-root");
        root.setTop(headerBar.getNode());
        root.setBottom(playbackControls.getNode());

        centre.setPadding(new Insets(10));
        centre.setFillWidth(true);
        centre.getChildren().add(mediaPanel.getNode());
        VBox.setVgrow(mediaPanel.getNode(), Priority.ALWAYS);

        right.setPadding(new Insets(10, 10, 10, 0));
        right.setFillWidth(true);
        right.getChildren().add(chatPanel.getNode());
        VBox.setVgrow(chatPanel.getNode(), Priority.ALWAYS);

        // Low enough that the single-column layout stays reachable.
        // CoWatchApp must not raise this.
        stage.setMinWidth(380);
        stage.setMinHeight(460);

        root.widthProperty().addListener((obs, oldW, newW) -> applyWidth(newW.doubleValue()));

        // Tactile press-squish for every button, once a Scene exists
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) Motion.installPressSquish(newScene);
        });

        headerBar.setChatShown(false);
        refreshCentre();
        Platform.runLater(() -> Motion.staggeredReveal(root));
    }

    /**
     * Re-classifies the window on resize. Only a threshold crossing does any
     * work; a plain drag between two sizes in the same band is a no-op.
     */
    private void applyWidth(double width) {
        boolean nowSideBySide = width >= SIDE_BY_SIDE_MIN;
        boolean nowCompact    = width < COMPACT_MAX;

        boolean wasCompact = root.getStyleClass().contains("compact");
        if (nowCompact != wasCompact) {
            if (nowCompact) root.getStyleClass().add("compact");
            else            root.getStyleClass().remove("compact");
        }

        if (nowSideBySide != sideBySide) {
            sideBySide = nowSideBySide;
            refreshCentre();
        }
    }

    /**
     * Puts the right thing in the centre for the current chat state and width.
     *
     * <p>Narrow windows swap chat in for the media panel rather than squeezing
     * both into a split - two 190px columns are worse than one usable one.
     */
    private void refreshCentre() {
        if (!chatShown) {
            root.setCenter(centre);
        } else if (sideBySide) {
            SplitPane split = new SplitPane(centre, right);
            split.getStyleClass().add("app-split");
            split.setDividerPositions(0.66);
            root.setCenter(split);
        } else {
            root.setCenter(right);
        }
        Motion.crossfade(root.getCenter());
    }

    private void toggleChat() {
        chatShown = !chatShown;
        headerBar.setChatShown(chatShown);
        refreshCentre();
    }

    /**
     * Appends to the chat transcript and, when the column is hidden, marks the
     * header toggle so activity is not lost behind a closed panel.
     */
    private void noteChatActivity() {
        if (!chatShown) headerBar.setChatUnread(true);
    }

    // --- Connect / disconnect ---------------------------------------------

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
                Platform.runLater(() -> {
                    chatPanel.appendMessage(msg);
                    noteChatActivity();
                }));

        server.joinRoom(config.roomId, config.username);
        headerBar.setConnected(config.serverHost, config.roomId);
        configPanel.onConnected();
        // The settings window has served its purpose once a room is joined.
        settings.hide();
        mediaPanel.setMode(config.mode);

        if (!mpv.isRunning()) playbackControls.setEnabled(false);
    }

    private void onDisconnect() {
        server.disconnect();
        syncEngine.close();
        headerBar.setDisconnected();
        if (!mpv.isRunning()) playbackControls.setEnabled(false);
        systemMessage("Disconnected from server");
        configPanel.onDisconnected();
    }

    /** Logs an app event to the chat transcript and flags it if chat is hidden. */
    private void systemMessage(String text) {
        chatPanel.appendRaw("[system] " + text);
        noteChatActivity();
    }

    // --- Launch -----------------------------------------------------------

    /** Launches a single file or URL in mpv. */
    public void onLaunch(String mpvUrl) {
        launch(() -> mpv.launch(mpvUrl), "MPV launched");
    }

    /** Launches a playlist in mpv, starting at {@code startIndex}. */
    public void onLaunchPlaylist(List<String> entries, int startIndex) {
        launch(() -> mpv.launch(entries, startIndex),
               "MPV launched (" + entries.size() + " episodes)");
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
                    systemMessage(okMessage);
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

    // --- Next episode (IPC only) ------------------------------------------

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
        systemMessage("MPV closed");
        mediaPanel.onMpvClosed(true);
    }

    // --- Panel accessors --------------------------------------------------

    public MediaPanel    getMediaPanel()    { return mediaPanel; }
    public TrackingPanel getTrackingPanel() { return trackingPanel; }

    /** Last playback position mpv reported, in seconds. */
    public double getPosition() { return syncEngine.getPosition(); }

    // --- Playback actions -------------------------------------------------

    public void doPlay()                 { mpvExecutor.submit(() -> syncEngine.localPlay()); }
    public void doPause()                { mpvExecutor.submit(() -> syncEngine.localPause()); }
    public void doSeek(double pos)       { mpvExecutor.submit(() -> syncEngine.localSeek(pos)); }
    public void doSkipForward(double s)  { mpvExecutor.submit(() -> syncEngine.localSkipForward(s)); }
    public void doSkipBackward(double s) { mpvExecutor.submit(() -> syncEngine.localSkipBackward(s)); }

    // --- Error dialogs ----------------------------------------------------

    public void showError(String msg) { showError("Error", msg, null); }

    public void showError(String title, String body) { showError(title, body, null); }

    public void showError(String title, String body, Runnable onClose) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("cowatch \u2014 " + title);
            alert.setHeaderText(title);
            alert.setContentText(body);
            alert.initOwner(stage);
            alert.getDialogPane().setPrefWidth(460);
            alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            alert.showAndWait();
            if (onClose != null) onClose.run();
        });
    }

    // --- Shutdown ---------------------------------------------------------

    private void wireShutdown() {
        stage.setOnCloseRequest(e -> {
            settings.hide();
            mpvExecutor.shutdownNow();
            syncEngine.close();
            server.disconnect();
            mpv.close();
        });
    }

    public Region getRoot() { return root; }
}
