package com.syncplay.client.tracking;

import org.json.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.logging.*;

/**
 * Seanime REST API client for playback tracking.
 *
 * Seanime runs locally at http://localhost:43211 by default.
 * When you report playback to Seanime, it handles AniList progress
 * updates automatically using its own AniList authentication.
 *
 * API endpoints used:
 *   POST /api/v1/media-player/start          — playback started
 *   POST /api/v1/media-player/tracking-stopped — playback stopped
 *
 * mediaId   = AniList media ID (integer)
 * episodeNumber = episode number as string e.g. "1", "12"
 */
public class SeanimeClient {

    private static final Logger log = Logger.getLogger(SeanimeClient.class.getName());

    private final String baseUrl;
    private final HttpClient http;

    public SeanimeClient(String baseUrl) {
        // Default: http://localhost:43211
        this.baseUrl = baseUrl.stripTrailing();
        this.http    = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ─── Playback reporting ───────────────────────────────────────────────

    /**
     * Tell Seanime playback started. Seanime will begin tracking progress
     * and will update AniList automatically when the episode is marked done.
     *
     * @param mediaId       AniList media ID
     * @param episodeNumber episode number (e.g. "1", "12")
     * @param path          local file path or stream URL being played
     */
    public void reportStart(int mediaId, String episodeNumber, String path) {
        try {
            JSONObject body = new JSONObject();
            body.put("mediaId",       mediaId);
            body.put("episodeNumber", episodeNumber);
            body.put("path",          path);

            String response = post("/api/v1/media-player/start", body.toString());
            if (response != null) {
                log.info("Seanime: reported playback start mediaId=" + mediaId
                        + " ep=" + episodeNumber);
            }
        } catch (Exception e) {
            log.warning("Seanime reportStart failed: " + e.getMessage());
        }
    }

    /**
     * Tell Seanime playback stopped. Seanime will sync progress to AniList.
     *
     * @param mediaId       AniList media ID
     * @param episodeNumber episode number
     */
    public void reportStopped(int mediaId, String episodeNumber) {
        try {
            JSONObject body = new JSONObject();
            body.put("mediaId",       mediaId);
            body.put("episodeNumber", episodeNumber);

            String response = post("/api/v1/media-player/tracking-stopped", body.toString());
            if (response != null) {
                log.info("Seanime: reported stopped mediaId=" + mediaId
                        + " ep=" + episodeNumber);
            }
        } catch (Exception e) {
            log.warning("Seanime reportStopped failed: " + e.getMessage());
        }
    }

    /**
     * Check if Seanime is running and reachable.
     */
    public boolean ping() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/status"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ─── HTTP ─────────────────────────────────────────────────────────────

    private String post(String path, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) {
            log.warning("Seanime POST " + path + " returned " + res.statusCode()
                    + ": " + res.body());
            return null;
        }
        return res.body();
    }

    public String getBaseUrl() { return baseUrl; }
}