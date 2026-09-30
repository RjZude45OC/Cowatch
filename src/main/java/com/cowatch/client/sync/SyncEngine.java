package com.cowatch.client.sync;

import com.cowatch.client.mpv.MPVController;
import com.cowatch.client.network.ServerConnection;
import com.cowatch.client.util.Async;
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

    // Fraction of total duration considered "watched" for tracking purposes (AniList, etc).
    private static final double WATCHED_THRESHOLD_RATIO = 0.8;

    private final MPVController    mpv;
    private final ServerConnection server;

    private volatile double  lastPos       = 0.0;
    private volatile double  mediaDuration = -1.0;
    private volatile boolean paused        = true;
    private volatile double  offsetSeconds = 0.0;

    private long lastPosTimestamp = 0;
    private long connectTime      = System.currentTimeMillis();

    private final AtomicBoolean suppressMpvEvents = new AtomicBoolean(false);

    // Latches true the first time playback crosses WATCHED_THRESHOLD_RATIO for the
    // current file. Must be cleared via resetWatchedTracking() whenever a new file
    // is loaded, otherwise it never fires again after the first episode.
    private final AtomicBoolean watchedThresholdFired = new AtomicBoolean(false);
    private volatile WatchedThresholdListener watchedThresholdListener;
    private volatile PlaylistPositionListener playlistPositionListener;

    /** Notified once per file when playback crosses WATCHED_THRESHOLD_RATIO of duration. */
    public interface WatchedThresholdListener {
        /** Called on the mpv event-reader thread — do blocking work (network calls) off-thread. */
        void onWatchedThresholdReached();
    }

    /**
     * Notified whenever mpv's own playlist-pos changes — including mpv auto-advancing
     * through a multi-episode playlist entirely on its own, with no UI interaction.
     * The owner (e.g. MediaPanel) MUST use this to keep its own "current episode index"
     * state in sync, or it will silently go stale once mpv advances internally.
     */
    public interface PlaylistPositionListener {
        /** Called on the mpv event-reader thread. newIndex is mpv's 0-based playlist-pos. */
        void onPlaylistPositionChanged(int newIndex);
    }

    public void setWatchedThresholdListener(WatchedThresholdListener listener) {
        this.watchedThresholdListener = listener;
    }

    public void setPlaylistPositionListener(PlaylistPositionListener listener) {
        this.playlistPositionListener = listener;
    }

    /**
     * Resets the 80%-watched latch and forgets the previously known duration.
     * MUST be called every time a new file/episode is loaded into mpv — including
     * the first file of a session and every "next episode" advance — otherwise the
     * threshold will only ever fire once, for the very first file played.
     */
    public void resetWatchedTracking() {
        watchedThresholdFired.set(false);
        mediaDuration = -1.0;
    }


    private final ScheduledExecutorService suppressScheduler =
            Executors.newSingleThreadScheduledExecutor(
                    Async.namedDaemonFactory("sync-suppress-timer"));

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

    public boolean isSynced() {
        return server != null && server.isConnected();
    }

    private boolean inStartupWindow() {
        return System.currentTimeMillis() - connectTime < STARTUP_QUIET_MS;
    }

    // ── Offset / duration helpers ─────────────────────────────────

    private double toRemote(double localPos) { return localPos - offsetSeconds; }
    private double toLocal(double remotePos) { return remotePos + offsetSeconds; }
    private double clampRemote(double pos)   { return Math.max(0.0, pos); }

    private double clampLocal(double pos) {
        double hi = mediaDuration > 0 ? mediaDuration : Double.MAX_VALUE;
        return Math.max(0.0, Math.min(pos, hi));
    }

    // ── MPV → Server ──────────────────────────────────────────────

    // Tracks mpv's own internal playlist-pos so we can detect when mpv
    // auto-advances to the next entry on its own (e.g. an entire season
    // loaded as one --playlist), which the caller's launchPlaylist()/
    // handleNextEpisode() never sees and therefore never resets for.
    private volatile int lastKnownPlaylistPos = -2; // -2 = "not yet known", distinct from mpv's own -1

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
                        }
                    }
                    case "playlist-pos" -> {
                        if (!msg.isNull("data")) {
                            handlePlaylistPosChange(msg.getInt("data"));
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
     * Fires whenever mpv's playlist-pos property changes. This catches BOTH
     * user-triggered "next episode" clicks AND mpv auto-advancing through a
     * multi-episode playlist entirely on its own (no application code involved).
     * Ignores mpv's transient -1 (briefly reported during some transitions/at
     * playlist end) so it never resets onto a non-entry.
     */
    private void handlePlaylistPosChange(int newPos) {
        int prev = lastKnownPlaylistPos;
        if (newPos < 0) return; // transient/no-current-entry — ignore, don't reset on this

        if (prev != -2 && newPos != prev) {
            log.info("Playlist position changed: " + prev + " → " + newPos + " — resetting watched tracking");

            PlaylistPositionListener listener = playlistPositionListener;
            if (listener != null) listener.onPlaylistPositionChanged(newPos);

            resetWatchedTracking();
        }
        lastKnownPlaylistPos = newPos;
    }

    private void handlePositionChange(double newPos) {
        long   now  = System.currentTimeMillis();
        long   gap  = lastPosTimestamp == 0 ? Long.MAX_VALUE : now - lastPosTimestamp;
        double prev = lastPos;

        lastPos          = newPos;
        lastPosTimestamp = now;

        checkWatchedThreshold(newPos);

        if (!suppressMpvEvents.get() && newPos < offsetSeconds) {
            log.info(String.format(
                    "Below offset floor %.2fs (local=%.2fs) — snapping back",
                    offsetSeconds, newPos));
            applyRemote(() -> mpv.seekTo(offsetSeconds));
            lastPos = offsetSeconds;
            return;
        }

        if (suppressMpvEvents.get() || inStartupWindow()) return;

        double delta = newPos - prev;
        if (Math.abs(delta) > SKIP_THRESHOLD && gap < SEEK_INSTANT_WINDOW_MS) {
            log.info(String.format("Seek: %.2f→%.2f Δ%.1fs gap %dms",
                    prev, newPos, delta, gap));
            if (isSynced()) server.sendSeek(clampRemote(toRemote(newPos)));
        }
    }

    /**
     * Fires watchedThresholdListener once per file, the first time playback
     * crosses WATCHED_THRESHOLD_RATIO of the known duration. No-ops until mpv
     * has reported a real duration (mediaDuration is -1.0 until then), so this
     * never misfires off a missing/zero duration.
     */
    private void checkWatchedThreshold(double pos) {
        if (mediaDuration <= 0) return; // duration not known yet — nothing to compare against
        if (watchedThresholdFired.get()) return; // already fired for this file

        if (pos / mediaDuration >= WATCHED_THRESHOLD_RATIO) {
            if (watchedThresholdFired.compareAndSet(false, true)) {
                log.info(String.format(
                        "Watched threshold reached: %.2fs / %.2fs (%.0f%%)",
                        pos, mediaDuration, (pos / mediaDuration) * 100));
                WatchedThresholdListener listener = watchedThresholdListener;
                if (listener != null) listener.onWatchedThresholdReached();
            }
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

    /**
     * Last position mpv reported, in seconds. Read by the UI to drive the
     * position readout; updated on the mpv event-reader thread.
     */
    public double getPosition() { return lastPos; }

    /** Media length in seconds, or -1 until mpv reports one. */
    public double getDuration() { return mediaDuration; }

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