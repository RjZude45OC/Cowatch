package com.cowatch.client.ui;

import com.cowatch.client.model.*;
import com.cowatch.client.mpv.MPVController;
import com.cowatch.client.network.ServerConnection;
import com.cowatch.client.sync.SyncEngine;
import com.cowatch.client.ui.controllers.*;
import com.cowatch.client.util.Async;
import org.json.JSONObject;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The application shell.
 *
 * <p>The window is deliberately thin: a header, the media browser, and a
 * transport bar that appears only while mpv runs. Chat is an optional second
 * column, and the server / sync-mode / tracking settings live in
 * {@link SettingsPanel}, a drawer that slides in from the right. Everything that a session configures once is one
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
    private final SettingsPanel    settings;

    /** Made on first use, once the window has its icon. */
    private MiniPlayer miniPlayer;

    /** Wrappers created once; the centre is re-composed as chat toggles. */
    private final VBox centre = new VBox();
    private final VBox right  = new VBox();

    private boolean chatShown = false;
    private boolean sideBySide = true;

    /**
     * Bumped by every launch and attach. A waiter for a player that has since
     * been replaced - opening the episode someone else started, say - sees a
     * newer number when its player exits and leaves the UI alone, instead of
     * reporting the new player closed.
     */
    private final AtomicInteger playerGeneration = new AtomicInteger();

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
        settings         = new SettingsPanel(configPanel, trackingPanel);

        wireSyncEngine();
        // Registered once, not per connection - re-registering on every
        // connect would stack handlers and run the teardown repeatedly.
        headerBar.setOnDisconnect(this::onDisconnect);
        headerBar.setOnConnect(() -> settings.show(0));
        headerBar.setOnOpenSettings(settings::toggle);
        settings.setOnShownChanged(() -> {
            headerBar.setSettingsShown(settings.isShown());
            // Narrow windows give the drawer the whole width (see refreshCentre)
            if (settings.isShown() && !sideBySide) refreshCentre();
        });
        settings.setOnClosed(() -> { if (!sideBySide) refreshCentre(); });
        headerBar.setOnToggleChat(this::toggleChat);

        // Show the media source the saved settings pick, not always Jellyfin.
        mediaPanel.setMode(configPanel.getSelectedMode());

        buildLayout();
        wireShutdown();

        // Minimising during playback swaps the window for the mini player;
        // restoring it from the taskbar swaps back.
        stage.iconifiedProperty().addListener((obs, was, iconified) -> {
            if (iconified && mpv.isRunning()) mini().show();
            else if (!iconified && miniPlayer != null) miniPlayer.hide();
        });
    }

    private MiniPlayer mini() {
        if (miniPlayer == null) miniPlayer = new MiniPlayer(this, stage);
        return miniPlayer;
    }

    /** Shrinks to the mini player. */
    public void showMiniPlayer() {
        if (!mpv.isRunning()) return;
        stage.setIconified(true);
        mini().show();   // in case the listener did not fire (already iconified)
    }

    /** Closes the mini player and brings the window back, when playback ends. */
    private void closeMiniPlayer() {
        if (miniPlayer == null || !miniPlayer.isShowing()) return;
        miniPlayer.hide();
        stage.setIconified(false);
    }

    private void wireSyncEngine() {
        offsetPanel.setSyncEngine(syncEngine);
        mediaPanel.setSyncEngine(syncEngine);
        // Only meaningful when attached to a player cowatch did not launch, but
        // harmless otherwise - the panel ignores it unless Seanime is connected.
        syncEngine.setMediaPathListener(path ->
                Platform.runLater(() -> mediaPanel.onSeanimeMediaPath(path)));
    }

    // --- Layout -----------------------------------------------------------

    private void buildLayout() {
        root.getStyleClass().add("app-root");
        root.setTop(headerBar.getNode());
        root.setBottom(playbackControls.getNode());
        root.setRight(settings.getNode());

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
     * both into a split - two 190px columns are worse than one usable one. The
     * settings drawer does the same: narrow, it replaces the centre while open.
     */
    private void refreshCentre() {
        if (settings.isShown() && !sideBySide) {
            root.setCenter(null);
            return;
        }
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
            throw new IOException("Could not connect to " + config.serverHost + ":" + config.serverPort
                    + ". Check the server is running and the address and port are right.");
        }
        server.setOnConnectionLost(() -> Platform.runLater(this::onConnectionLost));

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
        server.addMessageHandler(this::onServerMedia);

        mediaSeenSinceJoin = false;
        server.joinRoom(config.roomId, config.username);
        announceIfRoomQuiet();
        headerBar.setConnected(config.serverHost, config.roomId);
        configPanel.onConnected();
        // The settings drawer has served its purpose once a room is joined.
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

    /**
     * The server went away without being asked to. Without this the header
     * kept saying "connected" while nothing reached the room any more.
     */
    private void onConnectionLost() {
        server.disconnect();
        syncEngine.close();
        headerBar.setDisconnected();
        configPanel.onDisconnected();
        systemMessage("Lost the connection to the server. Reconnect from Settings.");
    }

    /**
     * Someone in the room started an episode. Opens the same one here, from
     * this viewer's own source, when following the room is switched on.
     *
     * <p>Runs on the server reader thread; the age of the frame is worked out
     * here, at receipt, so later delays opening the episode are measured on
     * this machine's clock rather than compared against the server's.
     */
    private void onServerMedia(String raw) {
        if (raw.isBlank() || !raw.contains("\"media\"")) return;
        try {
            JSONObject msg = new JSONObject(raw);
            if (!"media".equals(msg.optString("event"))) return;
            mediaSeenSinceJoin = true;
            int anilistId = msg.optInt("anilistId", 0);
            int episode   = msg.optInt("episode", 0);
            if (anilistId <= 0 || episode <= 0) return;

            boolean paused   = msg.optBoolean("paused", true);
            double  position = Math.max(0, msg.optDouble("position", 0));
            long    now      = System.currentTimeMillis();
            if (!paused) {
                // Same transit allowance as play and seek frames get.
                double age = (now - msg.optLong("serverTime", now)) / 1000.0;
                position += Math.max(0, Math.min(age, 5.0));
            }
            MediaPanel.PeerMedia peer = new MediaPanel.PeerMedia(anilistId, episode,
                    msg.optString("title", ""), msg.optString("from", "someone"),
                    position, paused, now);
            Platform.runLater(() -> {
                if (configPanel.isFollowRoom()) mediaPanel.followPeer(peer);
            });
        } catch (Exception e) {
            // Chat already shows the raw frame; nothing to open.
        }
    }

    /**
     * Tells the room about a player cowatch just attached to, once it has
     * reported where it is.
     *
     * <p>Seanime names the episode before cowatch can attach, when there is no
     * position to send yet, so the announcement waits for this. With the
     * player held paused, the room hears the pause first, so anyone already
     * watching stops too instead of running on alone.
     */
    private void announceAttached(SyncEngine engine, boolean paused) {
        Async.run("seanime-announce", () -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            if (paused) engine.sendPauseToRoom();
            Platform.runLater(mediaPanel::reannounce);
        });
    }

    /** Set by any "media" frame; read once, shortly after joining a room. */
    private volatile boolean mediaSeenSinceJoin = false;

    /**
     * Tells a room that has nothing on what this player already has open.
     *
     * <p>The server replays the room's episode to anyone who joins, so a moment
     * after joining this client knows whether the room is watching something.
     * If it is, the replay lines this player up with it. If not, and a player
     * is open here, this player's episode and position become the room's - so
     * two people who started watching before connecting end up in sync instead
     * of drifting until someone pauses.
     */
    private void announceIfRoomQuiet() {
        Async.run("announce-on-join", () -> {
            try { Thread.sleep(1_500); } catch (InterruptedException e) { return; }
            Platform.runLater(() -> {
                if (!server.isConnected() || mediaSeenSinceJoin || !mpv.isRunning()) return;
                mediaPanel.reannounce();
            });
        });
    }

    /** Logs an app event to the chat transcript and flags it if chat is hidden. */
    public void systemMessage(String text) {
        chatPanel.appendRaw("[system] " + text);
        noteChatActivity();
    }

    // --- Launch -----------------------------------------------------------

    /** Launches a single file or URL in mpv. */
    public void onLaunch(String mpvUrl) {
        boolean paused = startPaused();
        syncEngine.expectLaunch(paused);
        launch(() -> mpv.launch(mpvUrl, paused), "MPV launched" + startNote(paused));
    }

    /** Launches a playlist in mpv, starting at {@code startIndex}. */
    public void onLaunchPlaylist(List<String> entries, int startIndex) {
        boolean paused = startPaused();
        syncEngine.expectLaunch(paused);
        launch(() -> mpv.launch(entries, startIndex, paused),
               "MPV launched (" + entries.size() + " episodes)" + startNote(paused));
    }

    /** Whether the next launch opens paused, per the start setting and the room. */
    private boolean startPaused() {
        return configPanel.getStartBehavior().startPaused(server.isConnected());
    }

    private static String startNote(boolean paused) {
        return paused ? ", paused. Press play when everyone is ready" : "";
    }

    /**
     * Runs an mpv launch off the FX thread, reports the outcome, then waits for
     * the player to exit so the UI can reset itself.
     *
     * @param starter   returns null on success, or the failure message
     * @param okMessage chat line shown when the launch succeeds
     */
    private void launch(Supplier<String> starter, String okMessage) {
        final int generation = playerGeneration.incrementAndGet();
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
                if (generation != playerGeneration.get()) return;   // replaced, not closed
                Platform.runLater(this::onMpvClosed);
            }
        });
    }

    // --- Seanime attach ---------------------------------------------------

    /**
     * Joins the mpv window Seanime opened instead of launching a player.
     *
     * <p>Runs off the FX thread because the connect blocks, then waits on the IPC
     * connection the same way {@link #launch} waits on a process, so closing that
     * player resets this UI too. Nothing here starts or stops mpv: the viewer owns
     * it through Seanime, and cowatch is a second IPC client on the same socket.
     *
     * @param candidates the sockets to try in order, looked up off the FX
     *                   thread; the first that answers wins
     */
    public void onAttachToSeanime(Supplier<List<String>> candidates) {
        onAttachToSeanime(candidates, false);
    }

    /**
     * @param quiet true for an attach cowatch started on its own, on seeing
     *              Seanime open a player: a failure is logged, not shown in a
     *              dialog, since nobody asked for it and it will be retried
     */
    public void onAttachToSeanime(Supplier<List<String>> candidates, boolean quiet) {
        // Attaching mid-episode reports mpv's current state as if it had just
        // changed. Nobody pressed anything, so the room must not hear about it.
        syncEngine.beginQuietPeriod();
        final int generation = playerGeneration.incrementAndGet();
        // Seanime starts its player playing. Honour "open paused" for a player
        // cowatch found by itself, unless it is opening to join someone, in
        // which case their pause state wins.
        final boolean following  = syncEngine.hasPendingJoin();
        final boolean holdPaused = quiet && startPaused() && !following;
        final SyncEngine engine  = syncEngine;
        Async.run("seanime-attach", () -> {
            List<String> socketPaths = candidates.get();
            if (quiet && socketPaths.isEmpty()) {
                Platform.runLater(() -> mediaPanel.onSeanimeAttached(false));
                return;
            }
            String found = socketPaths.isEmpty()
                    ? "No Seanime player is running." + System.lineSeparator() + System.lineSeparator()
                      + "Start the episode in Seanime first, with mpv as its media player, "
                      + "then press Attach again."
                    : null;
            for (String socket : socketPaths) {
                found = mpv.attach(socket);
                if (found == null) break;
            }
            final String error = found;
            // Pause straight from this thread, before anything queues behind
            // the FX thread: every moment here is the episode running on.
            if (error == null && holdPaused) engine.pauseQuietly();
            Platform.runLater(() -> {
                boolean ok = (error == null);
                mediaPanel.onSeanimeAttached(ok);
                if (ok) {
                    playbackControls.setEnabled(true);
                    systemMessage("Attached to the mpv window Seanime opened"
                            + (holdPaused ? startNote(true) : ""));
                    if (!following) announceAttached(engine, holdPaused);
                } else if (quiet) {
                    java.util.logging.Logger.getLogger(MainWindow.class.getName())
                            .fine("Automatic Seanime attach failed: " + error);
                } else {
                    showError("Could not attach to Seanime playback", error);
                }
            });
            if (error != null) return;

            try {
                mpv.waitForExit();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (generation != playerGeneration.get()) return;   // replaced, not closed
            Platform.runLater(() -> {
                closeMiniPlayer();
                playbackControls.setEnabled(false);
                systemMessage("Seanime's mpv window closed");
                mediaPanel.onSeanimeAttached(false);
            });
        });
    }

    /**
     * Tells the room which episode this client has started.
     *
     * <p>Carries an AniList id rather than a path or a Jellyfin item id, so a
     * viewer streaming from Jellyfin and one playing a Seanime download can be
     * talking about the same episode.
     */
    public boolean announceMedia(int anilistId, int episode, String title, String source) {
        if (!server.isConnected()) return false;
        server.sendMedia(anilistId, episode, title, source,
                syncEngine.getRemotePosition(), syncEngine.isPaused());
        return true;
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
        closeMiniPlayer();
        playbackControls.setEnabled(false);
        systemMessage("MPV closed");
        mediaPanel.onMpvClosed(true);
    }

    /** Shows what is playing on the transport bar; see {@link PlaybackControls#setNowPlaying}. */
    public void setNowPlaying(String show, String episode) {
        playbackControls.setNowPlaying(show, episode);
        mini().setNowPlaying(show, episode);
    }

    /** Whether mpv last reported itself paused. */
    public boolean isPaused() { return syncEngine.isPaused(); }

    /** True while an mpv window, launched or attached, is open. */
    public boolean isPlayerOpen() { return mpv.isRunning(); }

    // --- Panel accessors --------------------------------------------------

    public MediaPanel    getMediaPanel()    { return mediaPanel; }
    public TrackingPanel getTrackingPanel() { return trackingPanel; }

    /** Last playback position mpv reported, in seconds. */
    public double getPosition() { return syncEngine.getPosition(); }

    /** Length of what mpv is playing, in seconds, or -1 while unknown. */
    public double getDuration() { return syncEngine.getDuration(); }

    // --- Debug console ----------------------------------------------------

    /**
     * Live state for the debug console's header, as label/value pairs.
     * Called on the FX thread a couple of times a second.
     */
    public Map<String, String> debugInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("Server", server.isConnected()
                ? "connected to " + server.getEndpoint() + ", room " + server.getRoom()
                : "disconnected" + (server.getEndpoint().isEmpty() ? "" : " (last " + server.getEndpoint() + ")"));

        String mpvState = mpv.isRunning()
                ? (mpv.isAttached() ? "attached" : "launched") + " on " + mpv.getSocketPath()
                : "not running";
        info.put("mpv", mpvState);

        SyncEngine sync = syncEngine;
        double dur = sync.getDuration();
        info.put("Sync", (sync.isSynced() ? "synced to room" : "local only")
                + ", " + (sync.isPaused() ? "paused" : "playing")
                + String.format(" at %.2fs / %s", sync.getPosition(),
                        dur < 0 ? "?" : String.format("%.2fs", dur))
                + String.format(", offset %+.3fs", sync.getOffset())
                + (sync.isSuppressing() ? ", applying remote" : "")
                + (sync.isInQuietPeriod() ? ", quiet window" : ""));

        info.put("Mode", String.valueOf(mediaPanel.getMode())
                + ", start " + configPanel.getStartBehavior());
        info.put("Seanime", mediaPanel.describeSeanime());

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        info.put("JVM", "Java " + System.getProperty("java.version")
                + ", heap " + usedMb + " / " + rt.maxMemory() / (1024 * 1024) + " MB"
                + ", threads " + Thread.activeCount());
        return info;
    }

    // --- Playback actions -------------------------------------------------

    public void doPlay()                 { mpvExecutor.submit(() -> syncEngine.localPlay()); }

    /** The single play/pause button: plays when paused, pauses when playing. */
    public void doPlayPause() {
        if (syncEngine.isPaused()) doPlay(); else doPause();
    }
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
            // Dialogs get a scene of their own, which does not inherit the
            // window's stylesheet - without this they render in stock light grey.
            URL css = CoWatchApp.class.getResource(CoWatchApp.CSS_PATH);
            if (css != null) alert.getDialogPane().getStylesheets().add(css.toExternalForm());
            alert.getDialogPane().setPrefWidth(460);
            alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            alert.showAndWait();
            if (onClose != null) onClose.run();
        });
    }

    // --- Shutdown ---------------------------------------------------------

    private void wireShutdown() {
        stage.setOnCloseRequest(e -> {
            if (miniPlayer != null) miniPlayer.hide();
            mediaPanel.shutdown();
            mpvExecutor.shutdownNow();
            syncEngine.close();
            server.disconnect();
            mpv.close();
        });
    }

    public Region getRoot() { return root; }
}
