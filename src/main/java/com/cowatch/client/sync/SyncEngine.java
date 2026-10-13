package com.cowatch.client.sync;

import com.cowatch.client.mpv.MPVController;
import com.cowatch.client.network.ServerConnection;
import com.cowatch.client.util.Async;
import org.json.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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

    // Written from the FX thread, read on the mpv reader thread.
    private volatile long lastPosTimestamp = 0;
    private volatile long connectTime      = System.currentTimeMillis();

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

    /**
     * Notified when mpv reports a different loaded file.
     *
     * <p>The only way to learn what is on screen when cowatch attached to an mpv
     * it did not launch - playback started from Seanime, say - since there is no
     * cowatch-side playlist to consult in that case.
     */
    public interface MediaPathListener {
        void onMediaPathChanged(String path);
    }

    private volatile MediaPathListener mediaPathListener;

    public void setMediaPathListener(MediaPathListener listener) {
        this.mediaPathListener = listener;
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

    /**
     * Starts a short window in which mpv state changes are taken in but not
     * relayed, and forgets what was loaded before.
     *
     * <p>Call just before connecting to a player. mpv answers each property
     * subscription with the current value, which otherwise looks exactly like
     * the viewer pressing play or seeking - attaching to a player that is
     * already running would broadcast a play to the whole room.
     */
    public void beginQuietPeriod() {
        connectTime          = System.currentTimeMillis();
        lastPosTimestamp     = 0;
        // The last player's position, until the new one reports - announcing
        // it would send the room to the wrong place.
        lastPos              = 0.0;
        lastMediaPath        = "";
        lastKnownPlaylistPos = -2;
        resetWatchedTracking();
    }

    /**
     * Records the pause state a player is about to be launched in.
     *
     * <p>mpv answers the pause subscription with its starting state. A player
     * launched to play at once would otherwise read as the viewer pressing play,
     * and broadcast a play that seeks everyone already watching back to this
     * player's start. With the baseline set, the first report is no change and
     * the room hears nothing until someone actually presses play or pause.
     */
    public void expectLaunch(boolean startPaused) {
        paused = startPaused;
    }

    /**
     * Where to put the next file once it has loaded, so this player lands where
     * someone else in the room already is.
     *
     * @param remotePos    their position, in the room's timeline, as of receivedAtMs
     * @param receivedAtMs when this client received it, by this client's clock
     * @param remotePaused whether they were paused; playing peers keep moving
     */
    private record PendingJoin(double remotePos, long receivedAtMs, boolean remotePaused) {}

    private final AtomicReference<PendingJoin> pendingJoin = new AtomicReference<>();

    /**
     * Seeks the next file mpv loads to a peer's position, and matches their
     * pause state.
     *
     * <p>Applied when mpv reports the new file's duration rather than straight
     * away: a seek sent before the file is open is dropped. Also covers attaching
     * to a player that is already open, since mpv answers the duration
     * subscription with the current value. Nothing is relayed to the room - the
     * room is already there.
     */
    public void joinAt(double remotePos, long receivedAtMs, boolean remotePaused) {
        pendingJoin.set(new PendingJoin(remotePos, receivedAtMs, remotePaused));
    }

    /** Drops a join that has not been applied, when what it was for is cancelled. */
    public void cancelJoin() { pendingJoin.set(null); }

    /** True while a join is waiting for the next file to load. */
    public boolean hasPendingJoin() { return pendingJoin.get() != null; }

    /**
     * Lines up the file already open with a peer, without waiting for a load.
     * For when both sides already have the same episode open.
     */
    public void joinNow(double remotePos, long receivedAtMs, boolean remotePaused) {
        pendingJoin.set(new PendingJoin(remotePos, receivedAtMs, remotePaused));
        applyPendingJoin();
    }

    /**
     * Pauses mpv without telling the room. For a player that started playing
     * on its own - Seanime plays at once - when the viewer asked for players
     * to open paused.
     */
    public void pauseQuietly() {
        applyRemote(mpv::pause);
        paused = true;
    }

    /** Tells the room this player is paused, at its current position. */
    public void sendPauseToRoom() {
        if (isSynced()) server.sendPause(clampRemote(toRemote(lastPos)));
    }

    private void applyPendingJoin() {
        PendingJoin join = pendingJoin.getAndSet(null);
        if (join == null) return;

        // Opening the episode can take a while (a Jellyfin stream buffering, a
        // Seanime launch), and a playing peer kept going meanwhile. Measured on
        // this client's own clock, so clock skew with the server cannot creep in.
        double elapsed = join.remotePaused() ? 0
                : Math.max(0, (System.currentTimeMillis() - join.receivedAtMs()) / 1000.0);
        double target = clampLocal(toLocal(join.remotePos() + elapsed));
        log.info(String.format("Joining the room at %.2fs (%s)", target,
                join.remotePaused() ? "paused" : "playing"));
        applyRemote(() -> {
            if (target > SEEK_DEAD_ZONE) mpv.seekTo(target);
            if (join.remotePaused()) mpv.pause(); else mpv.play();
        });
        lastPos = target;
        paused  = join.remotePaused();
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
                            applyPendingJoin();
                        }
                    }
                    case "path" -> {
                        if (!msg.isNull("data")) handleMediaPathChange(msg.optString("data", ""));
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

    /** Last path mpv reported, so an unchanged re-report does not re-fire. */
    private volatile String lastMediaPath = "";

    private void handleMediaPathChange(String path) {
        if (path == null || path.isBlank()) return;
        if (path.equals(lastMediaPath)) return;
        lastMediaPath = path;
        log.info("MPV loaded: " + path);

        MediaPathListener listener = mediaPathListener;
        if (listener != null) listener.onMediaPathChanged(path);
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
        scheduleUnsuppress();
    }

    /**
     * Lifts the echo suppression after {@link #SUPPRESS_DURATION_MS}.
     *
     * <p>Lifts it at once if this engine has been closed. The window keeps
     * using a closed engine until the next connect replaces it, and a schedule
     * on a shut-down executor would throw and leave suppression on for good -
     * every later pause and seek in mpv would be silently ignored.
     */
    private void scheduleUnsuppress() {
        try {
            suppressScheduler.schedule(
                    () -> suppressMpvEvents.set(false),
                    SUPPRESS_DURATION_MS,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            suppressMpvEvents.set(false);
        }
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

    /** Whether mpv last reported itself paused. */
    public boolean isPaused() { return paused; }

    /** Position in the room's timeline (offset removed), for announcing it. */
    public double getRemotePosition() { return clampRemote(toRemote(lastPos)); }

    /** Media length in seconds, or -1 until mpv reports one. */
    public double getDuration() { return mediaDuration; }

    // ── Offset ────────────────────────────────────────────────────

    public void setOffset(double seconds) {
        this.offsetSeconds = seconds;
        this.connectTime   = System.currentTimeMillis();
        log.info(String.format("Offset set to %.3fs", seconds));

        if (mpv.isRunning()) applyRemote(() -> mpv.seekTo(seconds));
    }

    // ── Shutdown ──────────────────────────────────────────────────

    public void close() { suppressScheduler.shutdownNow(); }
}