package com.syncplay.client.sync;

import com.syncplay.client.mpv.MPVController;
import com.syncplay.client.network.ServerConnection;
import org.json.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.*;

/**
 * Core sync engine — bridges MPV ↔ relay server.
 *
 * Two modes:
 *  LOCAL  — no ServerConnection; MPV runs standalone, all controls work normally.
 *  SYNCED — connected to relay server; local actions are broadcast and remote
 *           actions are applied to local MPV.
 *
 * Local → Server:
 *   MPV property-change events (time-pos, pause) are observed continuously.
 *   A position jump > SKIP_THRESHOLD in the EXPECTED direction signals a seek.
 *
 * Server → Local:
 *   play / pause / seek events are applied to MPV while suppressMpvEvents is
 *   set, preventing the resulting property-change from being echoed back.
 */
public class SyncEngine {

    private static final Logger log = Logger.getLogger(SyncEngine.class.getName());

    /** Position jump larger than this (seconds) is treated as an intentional seek. */
    private static final double SKIP_THRESHOLD = 2.5;

    /**
     * If local and remote positions are within this many seconds, skip the
     * re-seek to avoid frame jitter.
     */
    private static final double SEEK_DEAD_ZONE = 0.8;

    /**
     * How long (ms) to suppress MPV→server echo after applying a remote command.
     * Must be long enough for MPV's async IPC to fire its property-change events.
     */
    private static final long SUPPRESS_DURATION_MS = 700;

    private final MPVController  mpv;
    private final ServerConnection server; // null in local mode

    private volatile double  lastPos = 0.0;
    private volatile boolean paused  = true;

    /**
     * When true, property-change events from MPV are NOT forwarded to the server.
     * Used to break the feedback loop when we apply a remote command locally.
     * AtomicBoolean so the reader thread and suppression-timer thread are safe.
     */
    private final AtomicBoolean suppressMpvEvents = new AtomicBoolean(false);

    /** Single-thread scheduler for the suppress-reset timer (replaces raw Thread). */
    private final ScheduledExecutorService suppressScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sync-suppress-timer");
                t.setDaemon(true);
                return t;
            });

    // ─────────────────────────────────────────────────────────────

    /**
     * @param mpv    always required
     * @param server pass null for local-only mode
     */
    public SyncEngine(MPVController mpv, ServerConnection server) {
        this.mpv    = mpv;
        this.server = server;
        hookEvents();
    }

    // ─────────────────────────────────────────────────────────────
    // WIRING
    // ─────────────────────────────────────────────────────────────

    private void hookEvents() {
        mpv.setEventListener(this::onMpvEvent);

        // Only wire server listener when actually connected
        if (isSynced()) {
            server.addMessageHandler(this::onServerEvent);
        }
    }

    private boolean isSynced() {
        return server != null && server.isConnected();
    }

    // ─────────────────────────────────────────────────────────────
    // MPV → SERVER
    // ─────────────────────────────────────────────────────────────

    private void onMpvEvent(String rawJson) {
        if (rawJson.isBlank()) return;
        try {
            JSONObject msg = new JSONObject(rawJson);

            if ("property-change".equals(msg.optString("event"))) {
                switch (msg.optString("name")) {
                    case "time-pos" -> {
                        double newPos = msg.optDouble("data", lastPos);
                        handlePositionChange(newPos);
                    }
                    case "pause" -> {
                        boolean nowPaused = msg.optBoolean("data", true);
                        handlePauseChange(nowPaused);
                    }
                }
                return;
            }

            // Reply to get_property / request_id queries
            if (msg.has("request_id") && !msg.has("event")) {
                Object data = msg.opt("data");
                if (data instanceof Number) {
                    lastPos = ((Number) data).doubleValue();
                }
            }

        } catch (Exception e) {
            log.finest("Non-JSON MPV output: " + rawJson);
        }
    }

    /**
     * Called ~every second with MPV's current playback position.
     *
     * Skip detection requires:
     *   1. Jump is larger than SKIP_THRESHOLD
     *   2. We weren't already suppressing (i.e. we didn't cause it)
     *   3. The jump is in the FORWARD direction — backward jumps of any size
     *      are always intentional seeks; forward jumps of exactly 1s are normal
     *      playback ticks and must be filtered by threshold alone.
     *
     * The direction check avoids false positives during buffering stalls where
     * MPV may report a 2-3 s jump forward after catching up.
     * To handle that edge-case we additionally check that the video is currently
     * playing (not paused), because buffer-catchup only happens while playing.
     */
    private void handlePositionChange(double newPos) {
        double prev = lastPos;
        lastPos = newPos;

        if (suppressMpvEvents.get()) return;

        double delta = newPos - prev; // signed: positive = forward

        // A large backward jump, or a large forward jump while paused → seek
        boolean isBackwardSeek = delta < -SKIP_THRESHOLD;
        boolean isForwardSeek  = delta >  SKIP_THRESHOLD && paused;
        // Large forward jump while playing could be buffering catch-up — ignore
        boolean isBufferingCatchup = delta > SKIP_THRESHOLD && !paused;

        if ((isBackwardSeek || isForwardSeek) && !isBufferingCatchup) {
            log.info(String.format(
                    "Seek detected in MPV: %.2f → %.2f (Δ%.1fs)",
                    prev, newPos, delta));
            if (isSynced()) {
                server.sendSeek(newPos);
            }
        }
    }

    /**
     * Called when MPV's pause property changes (space bar, UI click, etc.).
     */
    private void handlePauseChange(boolean nowPaused) {
        boolean wasPaused = paused;
        paused = nowPaused;

        if (suppressMpvEvents.get()) return;

        if (nowPaused && !wasPaused) {
            log.info(String.format("MPV paused at %.2fs", lastPos));
            if (isSynced()) server.sendPause(lastPos);

        } else if (!nowPaused && wasPaused) {
            log.info(String.format("MPV playing at %.2fs", lastPos));
            if (isSynced()) server.sendPlay(lastPos);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // SERVER → MPV
    // ─────────────────────────────────────────────────────────────

    private void onServerEvent(String rawJson) {
        if (rawJson.isBlank()) return;
        log.info("Server → " + rawJson);

        try {
            JSONObject msg      = new JSONObject(rawJson);
            String event        = msg.getString("event");
            String from         = msg.optString("from", "?");
            double position     = msg.optDouble("position", -1);
            long   serverTime   = msg.optLong("serverTime", System.currentTimeMillis());

            // Compensate for network latency
            double ageSeconds = (System.currentTimeMillis() - serverTime) / 1000.0;
            // Clamp age — wildly wrong clocks should not seek to garbage positions
            double clampedAge = Math.max(0, Math.min(ageSeconds, 5.0));

            switch (event) {
                case "play" -> {
                    log.info(from + " → play at " + position);
                    double target = position + clampedAge;
                    applyRemote(() -> {
                        if (Math.abs(lastPos - target) > SEEK_DEAD_ZONE) {
                            mpv.seekTo(target);
                        }
                        mpv.play();
                    });
                }
                case "pause" -> {
                    log.info(from + " → pause at " + position);
                    applyRemote(() -> {
                        mpv.pause();
                        // Snap to the exact frame the remote user paused on
                        if (Math.abs(lastPos - position) > SEEK_DEAD_ZONE) {
                            mpv.seekTo(position);
                        }
                    });
                }
                case "seek" -> {
                    log.info(from + " → seek to " + position);
                    double target = position + clampedAge;
                    if (Math.abs(lastPos - target) > SEEK_DEAD_ZONE) {
                        applyRemote(() -> mpv.seekTo(target));
                    }
                }
                // chat / joined / system → handled by ChatPanel via its own listener
            }

        } catch (Exception e) {
            log.warning("Bad server message: " + rawJson);
        }
    }

    /**
     * Runs an MPV command while suppressing the resulting property-change
     * events so we don't echo them back to the server.
     *
     * Uses a ScheduledExecutorService instead of a raw Thread to avoid
     * unbounded thread creation under rapid sync events.
     */
    private void applyRemote(Runnable action) {
        suppressMpvEvents.set(true);
        action.run();
        suppressScheduler.schedule(
                () -> suppressMpvEvents.set(false),
                SUPPRESS_DURATION_MS,
                TimeUnit.MILLISECONDS
        );
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC CONTROLS  (called by PlaybackControls UI)
    // ─────────────────────────────────────────────────────────────

    /**
     * User pressed Play in the UI.
     * Tells MPV to play, then broadcasts to server if connected.
     * We read lastPos AFTER queuing the MPV command so both use the same value.
     */
    public void localPlay() {
        // Snapshot position before the async MPV play command changes it
        double pos = lastPos;
        mpv.play();
        if (isSynced()) {
            server.sendPlay(pos);
        }
    }

    public void localPause() {
        double pos = lastPos;
        mpv.pause();
        if (isSynced()) {
            server.sendPause(pos);
        }
    }

    public void localSeek(double position) {
        lastPos = position;          // update immediately so delta calc is correct
        mpv.seekTo(position);
        if (isSynced()) {
            server.sendSeek(position);
        }
    }

    public void localSkipForward(double seconds) {
        localSeek(lastPos + seconds);
    }

    public void localSkipBackward(double seconds) {
        localSeek(Math.max(0, lastPos - seconds));
    }

    // ─────────────────────────────────────────────────────────────
    // ACCESSORS
    // ─────────────────────────────────────────────────────────────

    public double          getLastPos()          { return lastPos; }
    public boolean         isPaused()            { return paused; }
    public boolean         isLocalMode()         { return !isSynced(); }
    public ServerConnection getServerConnection() { return server; }

    // ─────────────────────────────────────────────────────────────
    // SHUTDOWN
    // ─────────────────────────────────────────────────────────────

    public void close() {
        suppressScheduler.shutdownNow();
    }
}