package com.syncplay.client.jellyfin;

import org.json.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.logging.*;

/**
 * Talks to the Jellyfin REST API.
 * Used to authenticate, browse the library, resolve stream URLs,
 * and report playback session state so Jellyfin knows what is playing.
 */
public class JellyfinClient {

    private static final Logger log = Logger.getLogger(JellyfinClient.class.getName());

    private final String baseUrl;
    private final HttpClient http;
    private String apiKey;
    private String userId;

    public JellyfinClient(String baseUrl) {
        this.baseUrl = baseUrl.stripTrailing();
        this.http    = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ─── Auth ─────────────────────────────────────────────────────────────

    public boolean authenticate(String username, String password) {
        try {
            String body = String.format(
                    "{\"Username\":\"%s\",\"Pw\":\"%s\"}", username, password);

            // Auth request uses X-Emby-Authorization WITHOUT Token (not yet authenticated)
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/Users/AuthenticateByName"))
                    .header("Content-Type", "application/json")
                    .header("X-Emby-Authorization",
                            "MediaBrowser Client=\"SyncPlay\", Device=\"SyncPlay-Java\", " +
                                    "DeviceId=\"syncplay-java\", Version=\"1.0\"")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warning("Auth failed: " + res.statusCode() + " body=" + res.body());
                return false;
            }

            JSONObject json = new JSONObject(res.body());
            this.apiKey = json.getString("AccessToken");
            this.userId = json.getJSONObject("User").getString("Id");
            log.info("Authenticated as userId=" + userId);
            return true;

        } catch (Exception e) {
            log.severe("Auth error: " + e.getMessage());
            return false;
        }
    }

    public void setApiKey(String apiKey, String userId) {
        this.apiKey  = apiKey;
        this.userId  = userId;
    }

    // ─── Capabilities ─────────────────────────────────────────────────────

    /**
     * Register device capabilities with Jellyfin.
     *
     * MUST be called once after authentication (or after setApiKey) before
     * any session reporting. Without this, Jellyfin does not create a session
     * entry for this device and silently ignores all /Sessions/Playing calls —
     * the "Now Playing" dashboard will remain empty and plugins won't fire.
     */
    public void registerCapabilities() {
        try {
            JSONObject body = new JSONObject();
            body.put("PlayableMediaTypes",       new JSONArray().put("Video").put("Audio"));
            body.put("SupportedCommands",        new JSONArray());
            body.put("SupportsMediaControl",     true);
            body.put("SupportsPersistentIdentifier", true);
            body.put("SupportsSync",             false);

            post("/Sessions/Capabilities/Full", body.toString());
            log.info("Jellyfin: capabilities registered");
        } catch (Exception e) {
            log.warning("Jellyfin registerCapabilities failed: " + e.getMessage());
        }
    }

    // ─── Library ──────────────────────────────────────────────────────────

    public List<JellyfinItem> getTvShows() { return fetchItems("Series", ""); }
    public List<JellyfinItem> getSeasons(String seriesId) { return fetchItems("Season", seriesId); }
    public List<JellyfinItem> getEpisodes(String seasonId) { return fetchItems("Episode", seasonId); }
    public List<JellyfinItem> getMovies() { return fetchItems("Movie", ""); }

    private List<JellyfinItem> fetchItems(String type, String parentId) {
        try {
            String parentParam = parentId.isEmpty() ? "" : "&ParentId=" + parentId;
            String url = baseUrl + "/Users/" + userId
                    + "/Items?IncludeItemTypes=" + type
                    + "&Recursive=true&SortBy=SortName&SortOrder=Ascending"
                    + parentParam
                    + "&Fields=Overview,ProductionYear,IndexNumber,ParentIndexNumber"
                    + "&api_key=" + apiKey;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url)).GET().build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return Collections.emptyList();

            JSONObject json  = new JSONObject(res.body());
            JSONArray  items = json.getJSONArray("Items");
            List<JellyfinItem> result = new ArrayList<>();

            for (int i = 0; i < items.length(); i++) {
                JSONObject obj = items.getJSONObject(i);
                result.add(new JellyfinItem(
                        obj.optString("Id"),
                        obj.optString("Name"),
                        obj.optString("Type"),
                        obj.optString("Overview", ""),
                        obj.optInt("ProductionYear", 0),
                        obj.optInt("IndexNumber", 0),
                        obj.optInt("ParentIndexNumber", 0),
                        obj.optString("SeriesName", "")
                ));
            }
            return result;

        } catch (Exception e) {
            log.severe("fetchItems error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─── Stream URL ───────────────────────────────────────────────────────

    public String getStreamUrl(String itemId) {
        return baseUrl + "/Videos/" + itemId + "/stream?static=true&api_key=" + apiKey;
    }

    // ─── Playback Session Reporting ───────────────────────────────────────

    /**
     * Report playback started. Call immediately after MPV opens the stream.
     * Jellyfin will show "Now Playing" in the dashboard and trigger plugins.
     */
    public void reportPlaybackStart(String itemId, long positionMs, boolean isPaused) {
        try {
            JSONObject body = new JSONObject();
            body.put("ItemId",        itemId);
            body.put("MediaSourceId", itemId);
            body.put("PositionTicks", positionMs * 10_000L); // ms → 100ns ticks
            body.put("IsPaused",      isPaused);
            body.put("IsMuted",       false);
            body.put("PlayMethod",    "DirectStream");
            body.put("RepeatMode",    "RepeatNone");

            post("/Sessions/Playing", body.toString());
            log.info("Jellyfin: playback started itemId=" + itemId);
        } catch (Exception e) {
            log.warning("Jellyfin reportPlaybackStart failed: " + e.getMessage());
        }
    }

    /**
     * Report playback progress. Call every 10s while playing.
     */
    public void reportPlaybackProgress(String itemId, long positionMs, boolean isPaused) {
        try {
            JSONObject body = new JSONObject();
            body.put("ItemId",        itemId);
            body.put("MediaSourceId", itemId);
            body.put("PositionTicks", positionMs * 10_000L);
            body.put("IsPaused",      isPaused);
            body.put("IsMuted",       false);
            body.put("PlayMethod",    "DirectStream");
            body.put("RepeatMode",    "RepeatNone");

            post("/Sessions/Playing/Progress", body.toString());
            log.finest("Jellyfin: progress " + (positionMs / 1000) + "s paused=" + isPaused);
        } catch (Exception e) {
            log.warning("Jellyfin reportPlaybackProgress failed: " + e.getMessage());
        }
    }

    /**
     * Report playback stopped. Call when MPV closes or before advancing to next episode.
     */
    public void reportPlaybackStopped(String itemId, long positionMs, long durationMs) {
        try {
            JSONObject body = new JSONObject();
            body.put("ItemId",        itemId);
            body.put("MediaSourceId", itemId);
            body.put("PositionTicks", positionMs * 10_000L);

            post("/Sessions/Playing/Stopped", body.toString());
            log.info("Jellyfin: playback stopped itemId=" + itemId
                    + " at " + (positionMs / 1000) + "s");
        } catch (Exception e) {
            log.warning("Jellyfin reportPlaybackStopped failed: " + e.getMessage());
        }
    }

    // ─── HTTP core ────────────────────────────────────────────────────────

    /**
     * POST with correct Authorization header format.
     *
     * Jellyfin requires the Authorization header (not X-Emby-Authorization)
     * for session endpoints, with the Token field included.
     * X-Emby-Authorization is only used for the initial auth request where
     * no token exists yet.
     */
    private void post(String path, String jsonBody) throws Exception {
        // DeviceId must be stable per device+user combination.
        // Including userId makes it unique per user on this device.
        String deviceId = "syncplay-java-" + (userId != null ? userId : "anon");

        String authHeader = "MediaBrowser"
                + " Client=\"SyncPlay\","
                + " Device=\"SyncPlay-Java\","
                + " DeviceId=\"" + deviceId + "\","
                + " Version=\"1.0\","
                + " Token=\"" + apiKey + "\"";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type",  "application/json")
                .header("Authorization", authHeader)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) {
            log.warning("Jellyfin POST " + path
                    + " returned " + res.statusCode()
                    + " body=" + res.body());
        }
    }

    // ─── Misc ─────────────────────────────────────────────────────────────

    public boolean ping() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/System/Info/Public"))
                    .GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public String getApiKey()  { return apiKey; }
    public String getUserId()  { return userId; }
    public String getBaseUrl() { return baseUrl; }
}