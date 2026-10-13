package com.cowatch.client.jellyfin;

import org.json.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.logging.*;

/**
 * Talks to the Jellyfin REST API.
 * Used to authenticate, browse the library, resolve stream URLs,
 * and report playback session state so Jellyfin knows what is playing.
 *
 * <h2>Jellyfin 12.0 compatibility</h2>
 * Jellyfin 12.0 ships with {@code EnableLegacyAuthorization} off by default,
 * which rejects every legacy credential channel this client used to rely on:
 * the {@code X-Emby-Authorization} / {@code X-Emby-Token} headers and the
 * lowercase {@code api_key} query parameter. Everything here therefore goes
 * through the standard {@code Authorization: MediaBrowser} header, and the one
 * place a header is impossible - the stream URL handed to mpv - uses the
 * modern {@code ApiKey} query parameter instead. Both forms are also accepted
 * by 10.x servers, so this stays backwards compatible.
 */
public class JellyfinClient {

    private static final Logger log = Logger.getLogger(JellyfinClient.class.getName());

    private static final String CLIENT_NAME    = "cowatch";
    private static final String CLIENT_VERSION = "1.0.0";

    /**
     * Stable per-machine, per-account device id.
     *
     * <p>Jellyfin keys a session off DeviceId, so this must not change between
     * the unauthenticated login call and the authenticated calls that follow -
     * otherwise every run registers a second device in the server dashboard.
     */
    private static final String DEVICE_ID   = buildDeviceId();
    private static final String DEVICE_NAME = buildDeviceName();

    private final String baseUrl;
    private final HttpClient http;
    private String apiKey;
    private String userId;

    /** Human-readable reason the last auth attempt failed, or null on success. */
    private String lastError;

    public JellyfinClient(String baseUrl) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.http    = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Trims whitespace and any trailing slashes off the configured server URL.
     *
     * <p>Every path here is concatenated with a leading slash, so a pasted
     * "https://host/" would otherwise produce "//Users/AuthenticateByName" - a
     * 404 on Jellyfin 12, which no longer carries the lenient legacy route
     * prefixes to fall back on.
     */
    private static String normalizeBaseUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    // --- Auth ------------------------------------------------------------

    public boolean authenticate(String username, String password) {
        lastError = null;
        try {
            // Built with JSONObject, not string concatenation - a quote or
            // backslash in the password would otherwise produce an invalid
            // body and a login failure that looks like wrong credentials.
            String body = new JSONObject()
                    .put("Username", username)
                    .put("Pw",       password)
                    .toString();

            // Jellyfin 12 ignores X-Emby-Authorization, so the client and
            // device details have to travel in the standard Authorization
            // header. Without them the server has no device to bind a session
            // to and answers 400 - which looks exactly like a wrong password.
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/Users/AuthenticateByName"))
                    .header("Content-Type",  "application/json")
                    .header("Accept",        "application/json")
                    .header("Authorization", authHeader(null))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                lastError = describeAuthFailure(res.statusCode(), res.body());
                log.warning("Auth failed: " + res.statusCode() + " body=" + res.body());
                return false;
            }

            JSONObject json = new JSONObject(res.body());
            this.apiKey = json.getString("AccessToken");
            this.userId = json.getJSONObject("User").getString("Id");
            log.info("Authenticated as userId=" + userId);
            return true;

        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.severe("Auth error: " + e.getMessage());
            return false;
        }
    }

    /** Turns an auth HTTP status into something worth putting in front of a user. */
    private static String describeAuthFailure(int status, String body) {
        return switch (status) {
            case 400 -> "Server rejected the login request (HTTP 400). Check that the "
                      + "server URL points at Jellyfin itself, not a reverse-proxy path.";
            case 401 -> "Wrong username or password.";
            case 403 -> "This account is disabled, or login from this device is not permitted.";
            case 404 -> "No Jellyfin API at that URL (HTTP 404). Check the server address.";
            default  -> "Server returned HTTP " + status
                      + (body == null || body.isBlank() ? "" : " - " + body.strip());
        };
    }

    public void setApiKey(String apiKey, String userId) {
        this.apiKey = apiKey;
        this.userId = userId;
    }

    /**
     * Looks up the account the current token belongs to and records its id.
     *
     * <p>Every library call is user-scoped, so a client holding only an API key
     * cannot browse anything until this has run. It doubles as the real check
     * that the token works, which {@link #ping()} cannot provide.
     *
     * @return true if the token was accepted and a user id is now available
     */
    public boolean resolveCurrentUser() {
        lastError = null;
        try {
            HttpResponse<String> res = get("/Users/Me");
            if (res.statusCode() != 200) {
                lastError = res.statusCode() == 401
                        ? "Stored access token was rejected - sign in again with your username and password."
                        : "Server returned HTTP " + res.statusCode() + " for /Users/Me.";
                log.warning("Jellyfin token rejected: HTTP " + res.statusCode());
                return false;
            }
            this.userId = new JSONObject(res.body()).getString("Id");
            log.info("Jellyfin token belongs to userId=" + userId);
            return true;

        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warning("Jellyfin resolveCurrentUser failed: " + e.getMessage());
            return false;
        }
    }

    /** Reason the last auth attempt failed, or null if it succeeded. */
    public String getLastError() { return lastError; }

    // --- Capabilities ----------------------------------------------------

    /**
     * Register device capabilities with Jellyfin.
     *
     * MUST be called once after authentication (or after setApiKey) before
     * any session reporting. Without this, Jellyfin does not create a session
     * entry for this device and silently ignores all /Sessions/Playing calls -
     * the "Now Playing" dashboard stays empty and plugins never fire.
     */
    public void registerCapabilities() {
        try {
            JSONObject body = new JSONObject();
            body.put("PlayableMediaTypes",           new JSONArray().put("Video").put("Audio"));
            body.put("SupportedCommands",            new JSONArray());
            body.put("SupportsMediaControl",         true);
            body.put("SupportsPersistentIdentifier", true);
            body.put("SupportsSync",                 false);

            post("/Sessions/Capabilities/Full", body.toString());
            log.info("Jellyfin: capabilities registered");
        } catch (Exception e) {
            log.warning("Jellyfin registerCapabilities failed: " + e.getMessage());
        }
    }

    // --- Library ---------------------------------------------------------

    public List<JellyfinItem> getTvShows() { return fetchItems("Series", ""); }
    public List<JellyfinItem> getSeasons(String seriesId) { return fetchItems("Season", seriesId); }
    public List<JellyfinItem> getEpisodes(String seasonId) { return fetchItems("Episode", seasonId); }
    public List<JellyfinItem> getMovies() { return fetchItems("Movie", ""); }

    /** Every episode of a series, across all its seasons. */
    public List<JellyfinItem> getSeriesEpisodes(String seriesId) { return fetchItems("Episode", seriesId); }

    /**
     * Finds the series or movie in this library that an AniList entry refers to.
     *
     * <p>Tries, in order: an AniList provider id (set by Jellyfin's AniList
     * plugin); the other sites' ids AniZip maps the entry to, such as TVDB for
     * a series or TMDB for a movie; an exact title; then the closest title that
     * contains or is contained in one of {@code titles}. Anime films often have
     * no mapped ids at all, which is what the title steps are for.
     *
     * @param type        "Series" or "Movie"
     * @param anilistId   AniList media id
     * @param providerIds Jellyfin provider name ("Tvdb", "Tmdb", "Imdb") to id;
     *                    blank and "0" ids are ignored
     * @param titles      titles to fall back on, compared ignoring case and punctuation
     * @return the item, or null if nothing matched
     */
    public JellyfinItem findItem(String type, int anilistId, Map<String, String> providerIds,
                                 Collection<String> titles) {
        if (userId == null || userId.isEmpty()) return null;
        try {
            HttpResponse<String> res = get("/Items?userId=" + enc(userId)
                    + "&IncludeItemTypes=" + enc(type) + "&Recursive=true"
                    + "&Fields=ProviderIds,ProductionYear");
            if (res.statusCode() != 200) {
                log.warning("findItem " + type + " returned HTTP " + res.statusCode());
                return null;
            }
            JSONArray items = new JSONObject(res.body()).optJSONArray("Items");
            if (items == null) return null;

            Set<String> wanted = new HashSet<>();
            for (String t : titles) if (t != null && !t.isBlank()) wanted.add(titleKey(t));
            wanted.remove("");

            JSONObject byProvider = null, byTitle = null, byNearTitle = null;
            int nearScore = Integer.MAX_VALUE;
            for (int i = 0; i < items.length(); i++) {
                JSONObject obj = items.getJSONObject(i);
                JSONObject ids = obj.optJSONObject("ProviderIds");
                if (ids != null) {
                    if (String.valueOf(anilistId).equals(providerId(ids, "AniList"))) return toItem(obj);
                    if (byProvider == null) {
                        for (Map.Entry<String, String> want : providerIds.entrySet()) {
                            String v = want.getValue();
                            if (v == null || v.isBlank() || "0".equals(v)) continue;
                            if (v.equals(providerId(ids, want.getKey()))) { byProvider = obj; break; }
                        }
                    }
                }
                String name = titleKey(obj.optString("Name"));
                if (byTitle == null && wanted.contains(name)) byTitle = obj;
                int score = nearTitleScore(name, wanted);
                if (score < nearScore) { nearScore = score; byNearTitle = obj; }
            }
            JSONObject hit = byProvider != null ? byProvider
                           : byTitle    != null ? byTitle
                           : byNearTitle;
            return hit == null ? null : toItem(hit);
        } catch (Exception e) {
            log.warning("findItem failed: " + e.getMessage());
            return null;
        }
    }

    /** {@link #findItem} for a series, by AniList and TVDB id, then title. */
    public JellyfinItem findSeries(int anilistId, int tvdbId, Collection<String> titles) {
        return findItem("Series", anilistId,
                Map.of("Tvdb", tvdbId > 0 ? String.valueOf(tvdbId) : ""), titles);
    }

    /**
     * True when two title keys name the same thing: equal, or one containing
     * the other. Release names and library names shorten and decorate titles
     * ("Frieren" for "Sousou no Frieren"); the minimum length stops a two-letter
     * title matching everything.
     */
    public static boolean titleMatches(String key, Set<String> titleKeys) {
        return nearTitleScore(key, titleKeys) < Integer.MAX_VALUE;
    }

    /**
     * How far {@code key} is from the nearest of {@code titleKeys}: 0 for an
     * exact match, the length difference when one contains the other, and
     * MAX_VALUE for no match. Lower is closer, so "Gundam Hathaway" prefers the
     * Hathaway film over every other Gundam title containing "gundam".
     */
    static int nearTitleScore(String key, Set<String> titleKeys) {
        if (key == null || key.isEmpty()) return Integer.MAX_VALUE;
        int best = Integer.MAX_VALUE;
        for (String t : titleKeys) {
            if (t.equals(key)) return 0;
            if (Math.min(t.length(), key.length()) >= 4 && (t.contains(key) || key.contains(t))) {
                best = Math.min(best, Math.abs(t.length() - key.length()));
            }
        }
        return best;
    }

    /** Provider ids are keyed by name, with casing that differs between plugins. */
    private static String providerId(JSONObject ids, String name) {
        for (String key : ids.keySet()) {
            if (key.equalsIgnoreCase(name)) return ids.optString(key, "").trim();
        }
        return "";
    }

    /** Lowercase letters and digits only, so "Re:Zero" and "Re Zero" compare equal. */
    public static String titleKey(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static JellyfinItem toItem(JSONObject obj) {
        return new JellyfinItem(
                obj.optString("Id"),
                obj.optString("Name"),
                obj.optString("Type"),
                obj.optString("Overview", ""),
                obj.optInt("ProductionYear", 0),
                obj.optInt("IndexNumber", 0),
                obj.optInt("ParentIndexNumber", 0),
                obj.optString("SeriesName", ""));
    }

    /**
     * Fetches one item type, optionally restricted to a parent.
     *
     * <p>Uses {@code /Items?userId=...} rather than {@code /Users/{userId}/Items}:
     * the user-scoped path is no longer listed in Jellyfin 12's OpenAPI document
     * and is a candidate for removal, while the query-parameter form works on
     * both 10.x and 12.x. Credentials go in the Authorization header because the
     * {@code api_key} query parameter is rejected once legacy auth is off.
     */
    private List<JellyfinItem> fetchItems(String type, String parentId) {
        if (userId == null || userId.isEmpty()) {
            log.warning("fetchItems called before a user id was resolved");
            return Collections.emptyList();
        }
        try {
            String path = "/Items?userId=" + enc(userId)
                    + "&IncludeItemTypes=" + enc(type)
                    + "&Recursive=true&SortBy=SortName&SortOrder=Ascending"
                    + (parentId.isEmpty() ? "" : "&ParentId=" + enc(parentId))
                    + "&Fields=Overview,ProductionYear,IndexNumber,ParentIndexNumber";

            HttpResponse<String> res = get(path);
            if (res.statusCode() != 200) {
                log.warning("fetchItems " + type + " returned HTTP " + res.statusCode()
                        + " body=" + res.body());
                return Collections.emptyList();
            }

            JSONArray items = new JSONObject(res.body()).optJSONArray("Items");
            if (items == null) return Collections.emptyList();
            List<JellyfinItem> result = new ArrayList<>();

            for (int i = 0; i < items.length(); i++) {
                result.add(toItem(items.getJSONObject(i)));
            }
            return result;

        } catch (Exception e) {
            log.severe("fetchItems error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // --- Stream URL ------------------------------------------------------

    /**
     * Direct-stream URL for mpv.
     *
     * <p>mpv gets a bare URL with no way to attach an Authorization header, so
     * this is the one place credentials still travel in the query string. The
     * parameter is {@code ApiKey}: the lowercase {@code api_key} spelling is
     * part of the legacy set Jellyfin 12 rejects by default, which surfaced as
     * mpv opening and immediately failing on a 401.
     */
    public String getStreamUrl(String itemId) {
        return baseUrl + "/Videos/" + enc(itemId)
                + "/stream?static=true&ApiKey=" + enc(apiKey == null ? "" : apiKey);
    }

    // --- Playback session reporting --------------------------------------

    /**
     * Report playback started. Call immediately after MPV opens the stream.
     * Jellyfin then shows "Now Playing" in the dashboard and triggers plugins.
     */
    public void reportPlaybackStart(String itemId, long positionMs, boolean isPaused) {
        try {
            post("/Sessions/Playing", playStateBody(itemId, positionMs, isPaused).toString());
            log.info("Jellyfin: playback started itemId=" + itemId);
        } catch (Exception e) {
            log.warning("Jellyfin reportPlaybackStart failed: " + e.getMessage());
        }
    }

    /** Report playback progress. Call every 10s while playing. */
    public void reportPlaybackProgress(String itemId, long positionMs, boolean isPaused) {
        try {
            post("/Sessions/Playing/Progress", playStateBody(itemId, positionMs, isPaused).toString());
            log.finest("Jellyfin: progress " + (positionMs / 1000) + "s paused=" + isPaused);
        } catch (Exception e) {
            log.warning("Jellyfin reportPlaybackProgress failed: " + e.getMessage());
        }
    }

    /** Report playback stopped. Call when MPV closes or before advancing episode. */
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

    private static JSONObject playStateBody(String itemId, long positionMs, boolean isPaused) {
        JSONObject body = new JSONObject();
        body.put("ItemId",        itemId);
        body.put("MediaSourceId", itemId);
        body.put("PositionTicks", positionMs * 10_000L); // ms to 100ns ticks
        body.put("IsPaused",      isPaused);
        body.put("IsMuted",       false);
        body.put("PlayMethod",    "DirectStream");
        body.put("RepeatMode",    "RepeatNone");
        return body;
    }

    // --- HTTP core -------------------------------------------------------

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept",        "application/json")
                .header("Authorization", authHeader(apiKey))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private void post(String path, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type",  "application/json")
                .header("Accept",        "application/json")
                .header("Authorization", authHeader(apiKey))
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

    /**
     * The {@code Authorization: MediaBrowser} header Jellyfin expects.
     *
     * <p>This is the only credential channel Jellyfin 12 accepts by default;
     * the {@code X-Emby-*} headers it replaces belong to the legacy set that is
     * now disabled. Pass a null or blank token for the login call, which still
     * has to identify the client and device but has no token yet.
     */
    private String authHeader(String token) {
        StringBuilder sb = new StringBuilder("MediaBrowser")
                .append(" Client=\"").append(headerSafe(CLIENT_NAME)).append("\",")
                .append(" Device=\"").append(headerSafe(DEVICE_NAME)).append("\",")
                .append(" DeviceId=\"").append(headerSafe(DEVICE_ID)).append("\",")
                .append(" Version=\"").append(headerSafe(CLIENT_VERSION)).append("\"");
        if (token != null && !token.isBlank()) {
            sb.append(", Token=\"").append(headerSafe(token)).append("\"");
        }
        return sb.toString();
    }

    /**
     * Strips characters that would break out of a quoted header parameter.
     *
     * <p>The device name comes from the OS hostname and the token from the
     * server, so neither is expected to contain a quote - but one would produce
     * a malformed header and a 400 that reads as a credential problem.
     */
    private static String headerSafe(String value) {
        return value == null ? "" : value.replaceAll("[\"\\\r\n]", "");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String buildDeviceId() {
        String seed = System.getProperty("user.name", "user") + "@" + rawHostName();
        return "cowatch-java-" + Integer.toHexString(seed.hashCode());
    }

    private static String buildDeviceName() {
        return "cowatch (" + rawHostName() + ")";
    }

    private static String rawHostName() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) return host;
        } catch (Exception ignored) {
            // No resolvable hostname - the fallback below still yields a stable id.
        }
        String env = System.getenv("COMPUTERNAME");
        return (env == null || env.isBlank()) ? "unknown-host" : env;
    }

    // --- Misc ------------------------------------------------------------

    /**
     * True if the server is reachable. The endpoint it calls needs no
     * authentication, so a successful ping says nothing about whether the
     * token is valid - use {@link #resolveCurrentUser()} for that.
     */
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
