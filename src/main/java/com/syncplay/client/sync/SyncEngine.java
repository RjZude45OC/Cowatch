package com.syncplay.client.sync;

import com.syncplay.client.mpv.MPVController;
import com.syncplay.client.network.ServerConnection;
import org.json.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.*;

public class SyncEngine {

    private static final Logger log = Logger.getLogger(SyncEngine.class.getName());

    private static final double SKIP_THRESHOLD          = 2.5;
    private static final double SEEK_DEAD_ZONE          = 0.8;
    private static final long   SUPPRESS_DURATION_MS    = 700;
    private static final long   SEEK_INSTANT_WINDOW_MS  = 300;

    private final MPVController    mpv;
    private final ServerConnection server;

    private volatile double  lastPos       = 0.0;
    private volatile double  mediaDuration = -1.0;
    private volatile boolean paused        = true;
    private volatile double  offsetSeconds = 0.0;

    private long lastPosTimestamp = 0;

    private final AtomicBoolean suppressMpvEvents = new AtomicBoolean(false);

    private final ScheduledExecutorService suppressScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sync-suppress-timer");
                t.setDaemon(true);
                return t;
            });

    // ─────────────────────────────────────────────────────────────

    public SyncEngine(MPVController mpv, ServerConnection server) {
        this.mpv    = mpv;
        this.server = server;
        hookEvents();
    }

    private void hookEvents() {
        mpv.setEventListener(this::onMpvEvent);
        if (isSynced()) server.addMessageHandler(this::onServerEvent);
    }

    public void onServerConnected() {
        if (server != null) server.addMessageHandler(this::onServerEvent);
    }

    public boolean isSynced() {
        return server != null && server.isConnected();
    }

    // ── Offset helpers ────────────────────────────────────────────
    //
    // offsetSeconds = how many seconds ahead YOUR file is vs the shared timeline.
    // User 2 has 5s of extra intro → offsetSeconds = 5.
    //
    //   local position 8s  →  remote = 8 - 5 = 3s  (what others see)
    //   remote event   3s  →  local  = 3 + 5 = 8s  (where to seek in your file)
    //
    // The minimum valid LOCAL position is offsetSeconds:
    //   seeking below that would be into the extra intro that others don't have.

    private double toRemote(double localPos)  { return localPos - offsetSeconds; }
    private double toLocal(double remotePos)  { return remotePos + offsetSeconds; }

    /**
     * Clamp a local seek target:
     *   lower = offsetSeconds  (never seek into the "extra intro" zone)
     *   upper = mediaDuration  (never seek past end of file)
     */
    private double clampLocal(double pos) {
        double lo = offsetSeconds;                                          // FIX 1
        double hi = mediaDuration > 0 ? mediaDuration : Double.MAX_VALUE;
        return Math.max(lo, Math.min(pos, hi));
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
                    case "pause"    -> handlePauseChange(msg.optBoolean("data", true));
                    case "duration" -> {
                        if (!msg.isNull("data")) {
                            mediaDuration = msg.getDouble("data");
                            log.info(String.format("Media duration: %.2fs", mediaDuration));
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
        long   gap  = now - lastPosTimestamp;
        double prev = lastPos;

        lastPos          = newPos;
        lastPosTimestamp = now;

        if (suppressMpvEvents.get()) return;

        double delta = newPos - prev;

        if (Math.abs(delta) > SKIP_THRESHOLD) {
            boolean isInstantJump = (lastPosTimestamp != 0) && (gap < SEEK_INSTANT_WINDOW_MS);
            if (isInstantJump) {
                log.info(String.format(
                        "Seek in MPV: %.2f → %.2f (Δ%.1fs, gap %dms, remote=%.2f)",
                        prev, newPos, delta, gap, toRemote(newPos)));
                if (isSynced()) server.sendSeek(toRemote(newPos));
            } else {
                log.fine(String.format(
                        "Ignored catch-up: %.2f → %.2f (gap %dms)", prev, newPos, gap));
            }
        }
    }

    private void handlePauseChange(boolean nowPaused) {
        boolean wasPaused = paused;
        paused = nowPaused;

        if (suppressMpvEvents.get()) return;

        if (nowPaused && !wasPaused) {
            log.info(String.format("MPV paused   local=%.2fs remote=%.2fs offset=%.2fs",
                    lastPos, toRemote(lastPos), offsetSeconds));
            if (isSynced()) server.sendPause(toRemote(lastPos));
        } else if (!nowPaused && wasPaused) {
            log.info(String.format("MPV playing  local=%.2fs remote=%.2fs offset=%.2fs",
                    lastPos, toRemote(lastPos), offsetSeconds));
            if (isSynced()) server.sendPlay(toRemote(lastPos));
        }
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
                    log.info(String.format("%s → play  remote=%.2f local=%.2f", from, position, localTarget));
                    applyRemote(() -> {
                        if (Math.abs(lastPos - localTarget) > SEEK_DEAD_ZONE)
                            mpv.seekTo(localTarget);
                        mpv.play();
                    });
                }
                case "pause" -> {
                    double localTarget = clampLocal(toLocal(position));
                    log.info(String.format("%s → pause remote=%.2f local=%.2f", from, position, localTarget));
                    applyRemote(() -> {
                        mpv.pause();
                        if (Math.abs(lastPos - localTarget) > SEEK_DEAD_ZONE)
                            mpv.seekTo(localTarget);
                    });
                }
                case "seek" -> {
                    double localTarget = clampLocal(toLocal(position + age));
                    log.info(String.format("%s → seek  remote=%.2f local=%.2f", from, position, localTarget));
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
                TimeUnit.MILLISECONDS);
    }

    // ── Public controls ───────────────────────────────────────────

    public void localPlay() {
        double pos = lastPos;
        mpv.play();
        if (isSynced()) server.sendPlay(toRemote(pos));
    }

    public void localPause() {
        double pos = lastPos;
        mpv.pause();
        if (isSynced()) server.sendPause(toRemote(pos));
    }

    public void localSeek(double position) {
        double clamped = clampLocal(position);                              // FIX 2
        lastPos = clamped;
        mpv.seekTo(clamped);
        if (isSynced()) server.sendSeek(toRemote(clamped));
    }

    public void localSkipForward(double seconds) {
        localSeek(lastPos + seconds);
    }

    public void localSkipBackward(double seconds) {
        localSeek(lastPos - seconds);                                       // FIX 3
        // clampLocal inside localSeek enforces the offsetSeconds floor —
        // no separate Math.max(0,...) here so the offset limit is respected.
    }

    // ── Offset ────────────────────────────────────────────────────

    /**
     * Set playback offset in seconds.
     *
     * Positive = your file has N extra seconds at the start that others don't.
     * User 2 sets offset=5 → local position 5s = remote position 0s.
     *
     * After changing offset, if MPV is already playing, snap to the new
     * equivalent of the current remote position so playback stays in sync.
     */
    public void setOffset(double seconds) {
        double remoteNow = toRemote(lastPos);   // current remote pos before change
        this.offsetSeconds = seconds;
        log.info(String.format("Offset set to %.3fs", seconds));

        // If MPV is running, re-seek to keep the remote position stable
        if (mpv.isRunning()) {
            double newLocal = clampLocal(toLocal(remoteNow));
            suppressMpvEvents.set(true);
            mpv.seekTo(newLocal);
            suppressScheduler.schedule(
                    () -> suppressMpvEvents.set(false),
                    SUPPRESS_DURATION_MS,
                    TimeUnit.MILLISECONDS);
        }
    }

    public double getOffset() { return offsetSeconds; }

    // ── Accessors ─────────────────────────────────────────────────

    public double           getLastPos()          { return lastPos; }
    public boolean          isPaused()            { return paused; }
    public boolean          isLocalMode()         { return !isSynced(); }
    public double           getMediaDuration()    { return mediaDuration; }
    public ServerConnection getServerConnection() { return server; }

    // ── Shutdown ──────────────────────────────────────────────────

    public void close() { suppressScheduler.shutdownNow(); }
}