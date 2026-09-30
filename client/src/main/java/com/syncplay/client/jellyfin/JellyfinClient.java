package com.cowatch.client.jellyfin;

import org.json.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.logging.*;

/**
 * Talks to the Jellyfin REST API.
 * Used to authenticate, browse the library, and resolve stream URLs.
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

    /**
     * Authenticate with username + password, returns true on success.
     * Stores the access token internally.
     */
    public boolean authenticate(String username, String password) {
        try {
            String body = String.format(
                "{\"Username\":\"%s\",\"Pw\":\"%s\"}", username, password);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/Users/AuthenticateByName"))
                .header("Content-Type",  "application/json")
                .header("X-Emby-Authorization",
                    "MediaBrowser Client=\"cowatch\", Device=\"PC\", " +
                    "DeviceId=\"cowatch-java\", Version=\"1.0\"")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warning("Auth failed: " + res.statusCode());
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

    /** Use a pre-existing API key instead of username/password */
    public void setApiKey(String apiKey, String userId) {
        this.apiKey  = apiKey;
        this.userId  = userId;
    }

    // ─── Library ──────────────────────────────────────────────────────────

    /** Fetch all TV shows from the library */
    public List<JellyfinItem> getTvShows() {
        return fetchItems("Series", "");
    }

    /** Fetch seasons for a given series */
    public List<JellyfinItem> getSeasons(String seriesId) {
        return fetchItems("Season", seriesId);
    }

    /** Fetch episodes for a given season */
    public List<JellyfinItem> getEpisodes(String seasonId) {
        return fetchItems("Episode", seasonId);
    }

    /** Fetch movies */
    public List<JellyfinItem> getMovies() {
        return fetchItems("Movie", "");
    }

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
                .uri(URI.create(url))
                .GET()
                .build();

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
                    obj.optInt("IndexNumber", 0),       // episode number
                    obj.optInt("ParentIndexNumber", 0)  // season number
                ));
            }
            return result;

        } catch (Exception e) {
            log.severe("fetchItems error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─── Stream URL ───────────────────────────────────────────────────────

    /**
     * Build the direct stream URL for MPV.
     * MPV can play this directly — no transcoding needed for most formats.
     */
    public String getStreamUrl(String itemId) {
        return baseUrl + "/Videos/" + itemId
            + "/stream?static=true&api_key=" + apiKey;
    }

    /** Check if the server is reachable */
    public boolean ping() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/System/Info/Public"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
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
