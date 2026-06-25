package com.syncplay.client.tracking;

import com.syncplay.client.jellyfin.JellyfinClient;
import com.syncplay.client.jellyfin.JellyfinItem;

import java.util.concurrent.*;
import java.util.logging.*;

/**
 * Coordinates playback tracking across Jellyfin, Seanime, and AniList.
 *
 * Call flow:
 *   onPlaybackStarted()   — when MPV opens a file
 *   onPlaybackProgress()  — every ~10s while playing (from SyncEngine position events)
 *   onPlaybackStopped()   — when MPV closes or user advances to next episode
 *
 * Each backend is optional — null means disabled/not configured.
 * All network calls are off the JavaFX thread via trackingExecutor.
 */
public class TrackingManager {

    private static final Logger log = Logger.getLogger(TrackingManager.class.getName());

    // Backends — any can be null if not configured
    private JellyfinClient jellyfinClient;
    private SeanimeClient  seanimeClient;
    private AniListClient  aniListClient;

    // Currently tracked item state
    private String  currentJellyfinItemId  = null;
    private int     currentAniListMediaId  = -1;
    private String  currentEpisodeNumber   = null;
    private String  currentPath            = null;
    private double  lastReportedPositionSec = 0;
    private double  durationSec            = 0;
    private boolean currentlyTracking      = false;

    // Progress report throttle — report at most once per 10 seconds
    private long lastProgressReportMs = 0;
    private static final long PROGRESS_INTERVAL_MS = 10_000;

    private final ExecutorService trackingExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tracking");
        t.setDaemon(true);
        return t;
    });

    // ─── Configuration ────────────────────────────────────────────────────

    public void setJellyfinClient(JellyfinClient client) { this.jellyfinClient = client; }
    public void setSeanimeClient(SeanimeClient client)   { this.seanimeClient  = client; }
    public void setAniListClient(AniListClient client)   { this.aniListClient  = client; }

    // ─── Jellyfin episode launch ──────────────────────────────────────────

    /**
     * Call when launching a Jellyfin episode in MPV.
     * Starts Jellyfin session reporting.
     */
    public void onJellyfinEpisodeLaunched(JellyfinItem item) {
        currentJellyfinItemId  = item.id();
        currentEpisodeNumber   = String.valueOf(item.episodeNumber());
        currentPath            = null; // Jellyfin stream URL, not a local path
        currentAniListMediaId  = -1;  // not known from Jellyfin side
        currentlyTracking      = true;

        trackingExecutor.submit(() -> {
            if (jellyfinClient != null) {
                jellyfinClient.reportPlaybackStart(item.id(), 0, true);
            }
        });
        log.info("Tracking started: Jellyfin itemId=" + item.id()
                + " ep=" + item.episodeNumber());
    }

    /**
     * Call when launching a Seanime/local episode in MPV.
     * Starts Seanime and/or AniList tracking.
     *
     * @param mediaId       AniList media ID (-1 if unknown)
     * @param episodeNumber episode number string e.g. "5"
     * @param path          local file path or stream URL
     */
    public void onLocalEpisodeLaunched(int mediaId, String episodeNumber, String path) {
        currentJellyfinItemId  = null;
        currentAniListMediaId  = mediaId;
        currentEpisodeNumber   = episodeNumber;
        currentPath            = path;
        currentlyTracking      = true;

        trackingExecutor.submit(() -> {
            if (seanimeClient != null && mediaId > 0) {
                seanimeClient.reportStart(mediaId, episodeNumber, path);
            }
        });
        log.info("Tracking started: local mediaId=" + mediaId + " ep=" + episodeNumber);
    }

    // ─── Progress ─────────────────────────────────────────────────────────

    /**
     * Call periodically from SyncEngine's position change handler.
     * Throttled internally to avoid hammering APIs.
     *
     * @param positionSec current playback position in seconds
     * @param isPaused    current pause state
     */
    public void onPlaybackProgress(double positionSec, boolean isPaused) {
        if (!currentlyTracking) return;
        lastReportedPositionSec = positionSec;

        long now = System.currentTimeMillis();
        if (now - lastProgressReportMs < PROGRESS_INTERVAL_MS) return;
        lastProgressReportMs = now;

        final long posMs = (long)(positionSec * 1000);
        final String itemId = currentJellyfinItemId;

        trackingExecutor.submit(() -> {
            if (jellyfinClient != null && itemId != null) {
                jellyfinClient.reportPlaybackProgress(itemId, posMs, isPaused);
            }
            // Seanime tracks progress automatically — no polling needed
        });
    }

    /**
     * Set the media duration. Used to determine if an episode was completed
     * when playback stops.
     */
    public void onDurationKnown(double durationSec) {
        this.durationSec = durationSec;
    }

    // ─── Stop ─────────────────────────────────────────────────────────────

    /**
     * Call when MPV closes or before advancing to the next episode.
     * Reports stop to all backends and optionally updates AniList progress
     * if enough of the episode was watched (>= 80% of duration).
     */
    public void onPlaybackStopped() {
        if (!currentlyTracking) return;
        currentlyTracking = false;

        final long   posMs      = (long)(lastReportedPositionSec * 1000);
        final long   durMs      = (long)(durationSec * 1000);
        final String jfItemId   = currentJellyfinItemId;
        final int    alMediaId  = currentAniListMediaId;
        final String epNumber   = currentEpisodeNumber;
        final String path       = currentPath;

        // Consider episode "completed" if watched >= 80% of duration
        boolean completed = durationSec > 0
                && lastReportedPositionSec / durationSec >= 0.80;

        trackingExecutor.submit(() -> {
            // Jellyfin stop
            if (jellyfinClient != null && jfItemId != null) {
                jellyfinClient.reportPlaybackStopped(jfItemId, posMs, durMs);
            }

            // Seanime stop — it handles AniList update on its end
            if (seanimeClient != null && alMediaId > 0 && epNumber != null) {
                seanimeClient.reportStopped(alMediaId, epNumber);
            }

            // Direct AniList update (used when Seanime is not running)
            if (aniListClient != null && aniListClient.hasToken()
                    && alMediaId > 0 && epNumber != null && completed) {
                try {
                    int epNum = Integer.parseInt(epNumber);
                    aniListClient.updateProgress(alMediaId, epNum);
                    log.info("AniList: marked ep " + epNum + " watched for mediaId=" + alMediaId);
                } catch (NumberFormatException ignored) {}
            }
        });

        log.info("Tracking stopped at " + (int)lastReportedPositionSec + "s"
                + (completed ? " (completed)" : " (not completed)"));
    }

    // ─── Shutdown ─────────────────────────────────────────────────────────

    public void close() {
        onPlaybackStopped(); // flush final state
        trackingExecutor.shutdown();
    }
}