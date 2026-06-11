package com.syncplay.client.sync;

import com.syncplay.client.mpv.MPVController;
import com.syncplay.client.network.ServerConnection;
import org.json.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.*;

public class SyncEngine {

    private static final Logger log = Logger.getLogger(SyncEngine.class.getName());

    private static final double SKIP_THRESHOLD       = 2.5;
    private static final double SEEK_DEAD_ZONE       = 0.8;
    private static final long   SUPPRESS_DURATION_MS = 700;

    /**
     * If two consecutive time-pos events arrive within this many ms AND the
     * position jumped > SKIP_THRESHOLD, it is a real seek (instant jump).
     * Buffering catch-up is gradual — it arrives in multiple small steps
     * over several seconds, never as one giant instant jump.
     * 300ms covers MPV's ~50ms IPC poll interval with plenty of margin.
     */
    private static final long SEEK_INSTANT_WINDOW_MS = 300;

    private final MPVController    mpv;
    private final ServerConnection server;

    private volatile double  lastPos       = 0.0;
    private volatile double  mediaDuration = -1.0; // -1 = not yet known
    private volatile boolean paused        = true;
    private volatile double  offsetSeconds = 0.0;

    /** Timestamp of the last time-pos event — used for seek vs catch-up detection. */
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

    // ── Wiring ────────────────────────────────────────────────────

    private void hookEvents() {
        mpv.setEventListener(this::onMpvEvent);
        if (isSynced()) server.addMessageHandler(this::onServerEvent);
    }

    /**
     * Call this after a successful server connect when MPV is already running.
     * Wires the server handler without re-creating the engine.
     */
    public void onServerConnected() {
        if (server != null) server.addMessageHandler(this::onServerEvent);
    }

    public boolean isSynced() {
        return server != null && server.isConnected();
    }

    // ── Offset / duration helpers ─────────────────────────────────

    private double toRemote(double localPos) { return localPos - offsetSeconds; }
    private double toLocal(double remotePos) { return remotePos + offsetSeconds; }

    /**
     * Clamp local seek target to valid range for THIS file.
     * Prevents a user with a longer file seeking past the end of a shorter file.
     * If duration not yet known, clamp to >= 0 only.
     */
    private double clampLocal(double pos) {
        double lo = 0.0;
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

    /**
     * BUG 3 FIX — distinguish fast-forward seek from buffering catch-up.
     *
     * MPV fires time-pos events every ~50ms during normal playback.
     * A seek (arrow key, progress bar drag, fast-forward shortcut) causes an
     * INSTANT position jump — the very next time-pos event after the seek
     * arrives within a few ms and shows a large delta.
     *
     * Buffering catch-up is different: MPV stalls, position stops updating,
     * then resumes. The "jump" appears as a large delta but the GAP between
     * the last event and this one is large (hundreds of ms to seconds) because
     * MPV wasn't firing events during the stall.
     *
     * Rule:
     *   large delta + short time gap  = instant seek   → broadcast
     *   large delta + long time gap   = catch-up/stall → ignore
     */
    private void handlePositionChange(double newPos) {
        long now   = System.currentTimeMillis();
        long gap   = now - lastPosTimestamp;   // ms since last time-pos event
        double prev = lastPos;

        lastPos          = newPos;
        lastPosTimestamp = now;

        if (suppressMpvEvents.get()) return;

        double delta = newPos - prev;

        if (Math.abs(delta) > SKIP_THRESHOLD) {
            boolean isInstantJump = (lastPosTimestamp != 0) && (gap < SEEK_INSTANT_WINDOW_MS);
            if (isInstantJump) {
                log.info(String.format(
                        "Seek in MPV: %.2f → %.2f (Δ%.1fs, gap %dms)",
                        prev, newPos, delta, gap));
                if (isSynced()) server.sendSeek(toRemote(newPos));
            } else {
                log.fine(String.format(
                        "Ignored catch-up jump: %.2f → %.2f (gap %dms)",
                        prev, newPos, gap));
            }
        }
    }

    private void handlePauseChange(boolean nowPaused) {
        boolean wasPaused = paused;
        paused = nowPaused;

        if (suppressMpvEvents.get()) return;

        if (nowPaused && !wasPaused) {
            log.info(String.format("MPV paused at %.2fs (remote %.2fs)",
                    lastPos, toRemote(lastPos)));
            if (isSynced()) server.sendPause(toRemote(lastPos));
        } else if (!nowPaused && wasPaused) {
            log.info(String.format("MPV playing at %.2fs (remote %.2fs)",
                    lastPos, toRemote(lastPos)));
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
        mpv.play();
        if (isSynced()) server.sendPlay(toRemote(pos));
    }

    public void localPause() {
        double pos = lastPos;
        mpv.pause();
        if (isSynced()) server.sendPause(toRemote(pos));
    }

    public void localSeek(double position) {
        lastPos = position;
        mpv.seekTo(position);
        if (isSynced()) server.sendSeek(toRemote(position));
    }

    public void localSkipForward(double seconds)  { localSeek(lastPos + seconds); }
    public void localSkipBackward(double seconds) { localSeek(Math.max(0, lastPos - seconds)); }

    // ── Offset ────────────────────────────────────────────────────

    public void setOffset(double seconds) {
        this.offsetSeconds = seconds;
        log.info(String.format("Offset set to %.3fs", seconds));
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