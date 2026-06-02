package com.syncplay.client.sync;

import com.syncplay.client.mpv.MPVController;
import com.syncplay.client.network.ServerConnection;
import org.json.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

/**
 * Core sync engine — bridges MPV ↔ relay server.
 *
 * Fixes vs previous version:
 *  - suppressMpvEvents is now an AtomicLong timestamp (no race condition)
 *  - localPlay() no longer goes through applyRemote suppression
 *  - Mismatch offset support: each user can set a personal offset so
 *    e.g. User A's file has a 90s intro that User B's copy skips
 */
public class SyncEngine {

    private static final Logger log = Logger.getLogger(SyncEngine.class.getName());

    private static final double SKIP_THRESHOLD = 2.5;   // seconds jump = skip
    private static final double SEEK_DEAD_ZONE = 0.8;   // don't re-seek if within this

    private final MPVController    mpv;
    private final ServerConnection server;

    private volatile double  lastPos  = 0.0;
    private volatile boolean paused   = true;

    /**
     * Suppress window: if System.currentTimeMillis() < this value,
     * ignore MPV property-change events (we caused them via a remote command).
     * AtomicLong = thread-safe, no race condition.
     */
    private final AtomicLong suppressUntil = new AtomicLong(0);

    /**
     * Personal offset in seconds for this user.
     * If your file has a 90s cold-open that others skip:
     *   set offset = +90  → your position reported to others = actual - 90
     *   incoming positions from others = their pos + 90 applied to your file
     */
    private volatile double offsetSeconds = 0.0;

    public SyncEngine(MPVController mpv, ServerConnection server) {
        this.mpv    = mpv;
        this.server = server;
        hookEvents();
    }

    private void hookEvents() {
        mpv.setEventListener(this::onMpvEvent);
        server.setMessageHandler(this::onServerEvent);
    }

    // ─── MPV → Server ────────────────────────────────────────────────────

    private void onMpvEvent(String rawJson) {
        if (rawJson.isBlank()) return;
        try {
            JSONObject msg = new JSONObject(rawJson);

            if ("property-change".equals(msg.optString("event"))) {
                String name = msg.optString("name");
                switch (name) {
                    case "time-pos" -> handlePositionChange(msg.optDouble("data", lastPos));
                    case "pause"    -> handlePauseChange(msg.optBoolean("data", true));
                }
                return;
            }

            // Reply to get_property
            if (msg.has("request_id") && msg.has("data") && !msg.has("event")) {
                Object d = msg.opt("data");
                if (d instanceof Number) lastPos = ((Number) d).doubleValue();
            }

        } catch (Exception e) {
            log.finest("Non-JSON MPV output: " + rawJson);
        }
    }

    private void handlePositionChange(double newPos) {
        if (isSuppressed()) { lastPos = newPos; return; }

        double delta = Math.abs(newPos - lastPos);
        if (delta > SKIP_THRESHOLD) {
            // User skipped inside MPV directly — sync everyone
            log.info("Skip detected: " + lastPos + " → " + newPos);
            server.sendSeek(toSharedPos(newPos));
        }
        lastPos = newPos;
    }

    private void handlePauseChange(boolean nowPaused) {
        if (isSuppressed()) { paused = nowPaused; return; }

        if (nowPaused && !paused) {
            log.info("Pause detected at " + lastPos);
            server.sendPause(toSharedPos(lastPos));
        } else if (!nowPaused && paused) {
            log.info("Play detected at " + lastPos);
            server.sendPlay(toSharedPos(lastPos));
        }
        paused = nowPaused;
    }

    // ─── Server → MPV ────────────────────────────────────────────────────

    private void onServerEvent(String rawJson) {
        if (rawJson.isBlank()) return;
        log.info("← Server: " + rawJson);
        try {
            JSONObject msg  = new JSONObject(rawJson);
            String event    = msg.getString("event");
            double sharedPos = msg.optDouble("position", -1);
            long serverTime  = msg.optLong("serverTime", System.currentTimeMillis());
            double ageMs     = System.currentTimeMillis() - serverTime;
            double localTarget = toLocalPos(sharedPos) + (ageMs / 1000.0);

            switch (event) {
                case "play" -> {
                    suppress(800);
                    if (sharedPos >= 0 && Math.abs(lastPos - localTarget) > SEEK_DEAD_ZONE)
                        mpv.seekTo(localTarget);
                    mpv.play();
                    paused = false;
                }
                case "pause" -> {
                    suppress(800);
                    mpv.pause();
                    // Snap to exact pause point — no latency compensation on pause
                    double snapTarget = toLocalPos(sharedPos);
                    if (sharedPos >= 0 && Math.abs(lastPos - snapTarget) > SEEK_DEAD_ZONE)
                        mpv.seekTo(snapTarget);
                    paused = true;
                }
                case "seek" -> {
                    if (sharedPos >= 0 && Math.abs(lastPos - localTarget) > SEEK_DEAD_ZONE) {
                        suppress(800);
                        mpv.seekTo(localTarget);
                    }
                }
                // chat / system / joined handled by ChatPanel
            }
        } catch (Exception e) {
            log.warning("Bad server event: " + rawJson);
        }
    }

    // ─── Suppress helpers ────────────────────────────────────────────────

    private void suppress(long durationMs) {
        suppressUntil.set(System.currentTimeMillis() + durationMs);
    }

    private boolean isSuppressed() {
        return System.currentTimeMillis() < suppressUntil.get();
    }

    // ─── Offset helpers ──────────────────────────────────────────────────

    /**
     * Convert MY local position → shared position sent to server.
     * e.g. offset=+90 means my file is 90s ahead → subtract so others align
     */
    private double toSharedPos(double localPos) {
        return localPos - offsetSeconds;
    }

    /**
     * Convert shared position from server → MY local position.
     */
    private double toLocalPos(double sharedPos) {
        return sharedPos + offsetSeconds;
    }

    // ─── Public controls (UI buttons) ────────────────────────────────────

    public void localPlay() {
        suppress(500); // suppress the resulting property-change echo
        mpv.play();
        paused = false;
        server.sendPlay(toSharedPos(lastPos));
    }

    public void localPause() {
        suppress(500);
        mpv.pause();
        paused = true;
        server.sendPause(toSharedPos(lastPos));
    }

    public void localSeek(double localPosition) {
        suppress(800);
        mpv.seekTo(localPosition);
        lastPos = localPosition;
        server.sendSeek(toSharedPos(localPosition));
    }

    public void localSkipForward(double seconds) {
        localSeek(lastPos + seconds);
    }

    public void localSkipBackward(double seconds) {
        localSeek(Math.max(0, lastPos - seconds));
    }

    /**
     * Set this user's personal offset.
     * Call from the mismatch correction UI.
     * Positive offset = your file starts later than others (has extra content at start).
     * Negative offset = your file starts earlier.
     */
    public void setOffset(double seconds) {
        log.info("Offset set to " + seconds + "s");
        this.offsetSeconds = seconds;
    }

    public double getOffset()  { return offsetSeconds; }
    public double getLastPos() { return lastPos; }
    public boolean isPaused()  { return paused; }
}
