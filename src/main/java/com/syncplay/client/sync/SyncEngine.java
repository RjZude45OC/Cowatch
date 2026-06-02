package com.syncplay.client.sync;

import com.syncplay.client.mpv.MPVController;
import com.syncplay.client.network.ServerConnection;
import org.json.*;
import java.util.logging.*;

/**
 * Core sync engine — bridges MPV ↔ relay server.
 *
 * Two directions:
 *  1. Local user acts in MPV  → detect it → tell server → broadcast to others
 *  2. Remote event from server → apply to local MPV
 *
 * Detects skips done DIRECTLY inside MPV (arrow keys, progress bar click etc.)
 * via MPV's observe_property on "time-pos" — so yes, skipping in MPV syncs too.
 */
public class SyncEngine {

    private static final Logger log = Logger.getLogger(SyncEngine.class.getName());

    // If position jumps more than this in one step → user skipped
    private static final double SKIP_THRESHOLD = 2.5;

    // If we're within this many seconds of target → don't re-seek (avoid jitter)
    private static final double SEEK_DEAD_ZONE = 0.8;

    private final MPVController    mpv;
    private final ServerConnection server;

    private double  lastPos         = 0.0;
    private boolean paused          = true;
    private boolean suppressMpvEvents = false; // prevent feedback loops

    public SyncEngine(MPVController mpv, ServerConnection server) {
        this.mpv    = mpv;
        this.server = server;
        hookEvents();
    }

    // ─── Wire up both sides ───────────────────────────────────────────────

    private void hookEvents() {
        mpv.setEventListener(this::onMpvEvent);
        server.addMessageHandler(this::onServerEvent);
    }

    // ─── MPV → Server (local user did something) ──────────────────────────

    private void onMpvEvent(String rawJson) {
        if (rawJson.isBlank()) return;
        try {
            JSONObject msg = new JSONObject(rawJson);

            // ── Property change pushed by MPV automatically ───────────────
            if ("property-change".equals(msg.optString("event"))) {
                String name = msg.optString("name");

                switch (name) {
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

            // ── Reply to get_property request ─────────────────────────────
            if (msg.has("request_id") && msg.has("data")
                    && !msg.has("event")) {
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
     * Called every ~1s with MPV's current time-pos.
     * If it jumped by more than SKIP_THRESHOLD → user skipped inside MPV.
     */
    private void handlePositionChange(double newPos) {
        if (suppressMpvEvents) {
            lastPos = newPos;
            return;
        }

        double delta = Math.abs(newPos - lastPos);

        if (delta > SKIP_THRESHOLD) {
            // This is a skip — tell everyone
            log.info("Skip detected in MPV: " + lastPos + " → " + newPos
                     + " (Δ" + String.format("%.1f", delta) + "s)");
            server.sendSeek(newPos);
        }

        lastPos = newPos;
    }

    /**
     * Called when MPV's pause state changes (space bar, right-click menu etc.)
     */
    private void handlePauseChange(boolean nowPaused) {
        if (suppressMpvEvents) {
            paused = nowPaused;
            return;
        }

        if (nowPaused && !paused) {
            log.info("MPV paused at " + lastPos + " (detected via property-change)");
            server.sendPause(lastPos);
        } else if (!nowPaused && paused) {
            log.info("MPV playing at " + lastPos + " (detected via property-change)");
            server.sendPlay(lastPos);
        }

        paused = nowPaused;
    }

    // ─── Server → MPV (remote user did something) ────────────────────────

    private void onServerEvent(String rawJson) {
        if (rawJson.isBlank()) return;
        log.info("Server → " + rawJson);

        try {
            JSONObject msg    = new JSONObject(rawJson);
            String event      = msg.getString("event");
            String from       = msg.optString("from", "?");
            double position   = msg.optDouble("position", -1);
            long   serverTime = msg.optLong("serverTime", System.currentTimeMillis());

            // Compensate for message travel time
            double ageSeconds = (System.currentTimeMillis() - serverTime) / 1000.0;

            switch (event) {
                case "play" -> {
                    log.info(from + " → play at " + position);
                    double target = position + ageSeconds;
                    applyRemote(() -> {
                        if (Math.abs(lastPos - target) > SEEK_DEAD_ZONE)
                            mpv.seekTo(target);
                        mpv.play();
                    });
                }
                case "pause" -> {
                    log.info(from + " → pause at " + position);
                    applyRemote(() -> {
                        mpv.pause();
                        // Snap to exact pause point so both freeze at same frame
                        if (Math.abs(lastPos - position) > SEEK_DEAD_ZONE)
                            mpv.seekTo(position);
                    });
                }
                case "seek" -> {
                    log.info(from + " → seek to " + position);
                    double target = position + ageSeconds;
                    if (Math.abs(lastPos - target) > SEEK_DEAD_ZONE) {
                        applyRemote(() -> mpv.seekTo(target));
                    }
                }
                // chat / system / joined → handled by ChatPanel via its own listener
            }

        } catch (Exception e) {
            log.warning("Bad server message: " + rawJson);
        }
    }

    /**
     * Run an MPV command while suppressing the resulting property-change
     * events so we don't echo them back to the server.
     */
    private void applyRemote(Runnable action) {
        suppressMpvEvents = true;
        action.run();
        // Re-enable after a short delay (MPV IPC is async)
        new Thread(() -> {
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}
            suppressMpvEvents = false;
        }).start();
    }

    // ─── Public controls (called by PlaybackControls UI buttons) ─────────

    public void localPlay() {
        mpv.play();

        if (server != null && server.isConnected()) {
            server.sendPlay(lastPos);
        }
    }

    public void localPause() {
        mpv.pause();

        if (server != null && server.isConnected()) {
            server.sendPause(lastPos);
        }
    }

    public void localSeek(double position) {
        mpv.seekTo(position);

        if (server != null && server.isConnected()) {
            server.sendSeek(position);
        }

        lastPos = position;
    }

    /** Skip forward by N seconds */
    public void localSkipForward(double seconds) {
        localSeek(lastPos + seconds);
    }

    /** Skip backward by N seconds */
    public void localSkipBackward(double seconds) {
        localSeek(Math.max(0, lastPos - seconds));
    }

    public double getLastPos() { return lastPos; }
    public boolean isPaused()  { return paused; }
    public ServerConnection  getServerConnection() { return server; }
}
