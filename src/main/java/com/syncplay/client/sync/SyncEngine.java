package com.syncplay.client.sync;

import com.syncplay.client.mpv.MPVController;
import com.syncplay.client.network.ServerConnection;
import com.syncplay.client.tracking.TrackingManager;
import org.json.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.*;

public class SyncEngine {

    private static final Logger log = Logger.getLogger(SyncEngine.class.getName());

    private static final double SKIP_THRESHOLD         = 2.5;
    private static final double SEEK_DEAD_ZONE         = 0.8;
    private static final long   SUPPRESS_DURATION_MS   = 700;
    private static final long   SEEK_INSTANT_WINDOW_MS = 300;
    private static final long   STARTUP_QUIET_MS       = 1_500;

    private final MPVController    mpv;
    private final ServerConnection server;
    private final TrackingManager  tracking; // nullable

    private volatile double  lastPos       = 0.0;
    private volatile double  mediaDuration = -1.0;
    private volatile boolean paused        = true;
    private volatile double  offsetSeconds = 0.0;

    private long lastPosTimestamp = 0;
    private long connectTime      = System.currentTimeMillis();

    private final AtomicBoolean suppressMpvEvents = new AtomicBoolean(false);

    private final ScheduledExecutorService suppressScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sync-suppress-timer");
                t.setDaemon(true);
                return t;
            });

    // ─────────────────────────────────────────────────────────────

    /** Constructor without tracking (backwards-compatible). */
    public SyncEngine(MPVController mpv, ServerConnection server) {
        this(mpv, server, null);
    }

    public SyncEngine(MPVController mpv, ServerConnection server, TrackingManager tracking) {
        this.mpv      = mpv;
        this.server   = server;
        this.tracking = tracking;
        hookEvents();
    }

    // ── Wiring ────────────────────────────────────────────────────

    private void hookEvents() {
        mpv.setEventListener(this::onMpvEvent);
        if (isSynced()) server.addMessageHandler(this::onServerEvent);
    }

    public boolean isSynced() {
        return server != null && server.isConnected();
    }

    private boolean inStartupWindow() {
        return System.currentTimeMillis() - connectTime < STARTUP_QUIET_MS;
    }

    // ── Offset / duration helpers ─────────────────────────────────

    private double toRemote(double localPos) { return localPos - offsetSeconds; }
    private double toLocal(double remotePos) { return remotePos + offsetSeconds; }

    private double clampRemote(double remotePos) { return Math.max(0.0, remotePos); }

    private double clampLocal(double pos) {
        double hi = mediaDuration > 0 ? mediaDuration : Double.MAX_VALUE;
        return Math.max(0.0, Math.min(pos, hi));
    }

    // ── MPV → Server ──────────────────────────────────────────────

    private void onMpvEvent(String rawJson) {
        if (rawJson.isBlank()) return;
        try {
            JSONObject msg = new JSONObject(rawJson);

            if ("property-change".equals(msg.optString("event"))) {
                switch (msg.optString("name")) {
                    case "time-pos" -> {
                        if (!msg.isNull("data"))
                            handlePositionChange(msg.getDouble("data"));
                    }
                    case "pause" -> handlePauseChange(msg.optBoolean("data", true));
                    case "duration" -> {
                        if (!msg.isNull("data")) {
                            mediaDuration = msg.getDouble("data");
                            log.info(String.format("Media duration: %.2fs", mediaDuration));
                            // Tell TrackingManager the duration
                            if (tracking != null) tracking.onDurationKnown(mediaDuration);
                        }
                    }
                }
                return;
            }

            if (msg.has("request_id") && !msg.has("event")) {
                Object data = msg.opt("data");
                if (data instanceof Number n) lastPos = n.doubleValue();
            }

        } catch (Exception e) {
            log.finest("Non-JSON MPV output: " + rawJson);
        }
    }

    private void handlePositionChange(double newPos) {
        long   now  = System.currentTimeMillis();
        long   gap  = lastPosTimestamp == 0 ? Long.MAX_VALUE : now - lastPosTimestamp;
        double prev = lastPos;

        lastPos          = newPos;
        lastPosTimestamp = now;

        // Enforce offset floor
        if (!suppressMpvEvents.get() && newPos < offsetSeconds) {
            log.info(String.format(
                    "Below offset floor %.2fs (local=%.2fs) — snapping back",
                    offsetSeconds, newPos));
            applyRemote(() -> mpv.seekTo(offsetSeconds));
            lastPos = offsetSeconds;
            return;
        }

        if (suppressMpvEvents.get() || inStartupWindow()) return;

        // Forward progress to tracking (throttled internally by TrackingManager)
        if (tracking != null) tracking.onPlaybackProgress(newPos, paused);

        double delta = newPos - prev;
        if (Math.abs(delta) > SKIP_THRESHOLD && gap < SEEK_INSTANT_WINDOW_MS) {
            log.info(String.format("Seek: %.2f→%.2f Δ%.1fs gap %dms",
                    prev, newPos, delta, gap));
            if (isSynced()) server.sendSeek(clampRemote(toRemote(newPos)));
        }
    }

    private void handlePauseChange(boolean nowPaused) {
        boolean wasPaused = paused;
        paused = nowPaused;

        if (suppressMpvEvents.get() || inStartupWindow()) return;

        if (nowPaused && !wasPaused) {
            log.info(String.format("Paused at %.2fs (remote %.2fs)",
                    lastPos, toRemote(lastPos)));
            if (isSynced()) server.sendPause(clampRemote(toRemote(lastPos)));
        } else if (!nowPaused && wasPaused) {
            log.info(String.format("Playing at %.2fs (remote %.2fs)",
                    lastPos, toRemote(lastPos)));
            if (isSynced()) server.sendPlay(clampRemote(toRemote(lastPos)));
        }

        // Forward pause state to tracking
        if (tracking != null) tracking.onPlaybackProgress(lastPos, nowPaused);
    }

    // ── Server → MPV ──────────────────────────────────────────────

    private void onServerEvent(String rawJson) {
        if (rawJson.isBlank()) return;
        log.info("Server → " + rawJson);
        try {
            JSONObject msg    = new JSONObject(rawJson);
            String event      = msg.getString("event");
            String from       = msg.optString("from", "?");
            double position   = msg.optDouble("position", -1);
            long   serverTime = msg.optLong("serverTime", System.currentTimeMillis());

            double age = Math.max(0, Math.min(
                    (System.currentTimeMillis() - serverTime) / 1000.0, 5.0));

            switch (event) {
                case "play" -> {
                    double localTarget = clampLocal(toLocal(position + age));
                    log.info(from + " → play remote=" + position + " local=" + localTarget);
                    applyRemote(() -> {
                        if (Math.abs(lastPos - localTarget) > SEEK_DEAD_ZONE)
                            mpv.seekTo(localTarget);
                        mpv.play();
                    });
                }
                case "pause" -> {
                    double localTarget = clampLocal(toLocal(position));
                    log.info(from + " → pause remote=" + position + " local=" + localTarget);
                    applyRemote(() -> {
                        mpv.pause();
                        if (Math.abs(lastPos - localTarget) > SEEK_DEAD_ZONE)
                            mpv.seekTo(localTarget);
                    });
                }
                case "seek" -> {
                    double localTarget = clampLocal(toLocal(position + age));
                    log.info(from + " → seek remote=" + position + " local=" + localTarget);
                    if (Math.abs(lastPos - localTarget) > SEEK_DEAD_ZONE)
                        applyRemote(() -> mpv.seekTo(localTarget));
                }
            }

        } catch (Exception e) {
            log.warning("Bad server message: " + rawJson);
        }
    }

    private void applyRemote(Runnable action) {
        suppressMpvEvents.set(true);
        action.run();
        suppressScheduler.schedule(
                () -> suppressMpvEvents.set(false),
                SUPPRESS_DURATION_MS,
                TimeUnit.MILLISECONDS
        );
    }

    // ── Public controls ───────────────────────────────────────────

    public void localPlay() {
        double pos = lastPos;
        log.info("localPlay — lastPos=" + lastPos + " mpv=" + mpv);
        applyRemote(() -> mpv.play());
        if (isSynced()) server.sendPlay(clampRemote(toRemote(pos)));
    }

    public void localPause() {
        double pos = lastPos;
        applyRemote(() -> mpv.pause());
        if (isSynced()) server.sendPause(clampRemote(toRemote(pos)));
    }

    public void localSeek(double position) {
        lastPos = position;
        mpv.seekTo(position);
        if (isSynced()) server.sendSeek(clampRemote(toRemote(position)));
    }

    public void localSkipForward(double seconds)  { localSeek(lastPos + seconds); }
    public void localSkipBackward(double seconds) {
        localSeek(Math.max(offsetSeconds, lastPos - seconds));
    }

    // ── Offset ────────────────────────────────────────────────────

    public void setOffset(double seconds) {
        this.offsetSeconds = seconds;
        this.connectTime   = System.currentTimeMillis();
        log.info(String.format("Offset set to %.3fs", seconds));

        if (mpv.isRunning()) {
            suppressMpvEvents.set(true);
            mpv.seekTo(seconds);
            suppressScheduler.schedule(
                    () -> suppressMpvEvents.set(false),
                    SUPPRESS_DURATION_MS,
                    TimeUnit.MILLISECONDS);
        }
    }

    // ── Shutdown ──────────────────────────────────────────────────

    public void close() { suppressScheduler.shutdownNow(); }
}