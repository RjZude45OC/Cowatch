package com.cowatch.client.seanime;

import org.json.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

/**
 * Talks to a Seanime server's {@code /api/v1} surface.
 *
 * <p>Seanime is the other half of a mixed room: one viewer streams an episode
 * from Jellyfin while the other plays a file Seanime's auto-downloader fetched.
 * cowatch uses this client for three things:
 *
 * <ol>
 *   <li>finding the mpv IPC socket Seanime launches its player on, so cowatch
 *       can attach to that player instead of starting a second one;</li>
 *   <li>turning the file path mpv reports back into an AniList media id and
 *       episode number, which is what the room relays;</li>
 *   <li>handing episode progress to Seanime rather than writing to AniList
 *       directly, so the two do not race on the same list entry.</li>
 * </ol>
 *
 * <p>Seanime's own watch-party feature (Nakama) is not usable here: its peer
 * protocol assumes every participant is a Seanime instance, and Seanime cannot
 * read a Jellyfin library at all.
 */
public class SeanimeClient {

    private static final Logger log = Logger.getLogger(SeanimeClient.class.getName());

    /** Seanime's default port. Its server binds 127.0.0.1 unless reconfigured. */
    public static final int DEFAULT_PORT = 43211;

    private final String baseUrl;
    private final String token;
    private final HttpClient http;

    /** Filled in by {@link #connect()}. */
    private String mpvSocket   = "";
    private String defaultPlayer = "";
    private String userName    = "";
    private String lastError;

    /**
     * @param baseUrl root of the Seanime server, e.g. {@code http://127.0.0.1:43211}
     * @param token   the server password, or blank when the server has none;
     *                sent as {@code X-Seanime-Token}
     */
    public SeanimeClient(String baseUrl, String token) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.token   = token == null ? "" : token.trim();
        this.http    = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Accepts a bare host, a host:port, or a full URL, and returns a usable root.
     *
     * <p>Seanime's own UI shows the address as "127.0.0.1:43211" with no scheme,
     * so that is what people copy. Requiring them to prepend http:// would be a
     * pointless failure mode.
     */
    static String normalizeBaseUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.isEmpty()) return "http://127.0.0.1:" + DEFAULT_PORT;
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
        // Host with no port: assume Seanime's default rather than port 80.
        String afterScheme = url.substring(url.indexOf("://") + 3);
        if (!afterScheme.contains(":")) url = url + ":" + DEFAULT_PORT;
        return url;
    }

    // --- Status -----------------------------------------------------------

    /**
     * Checks the server and caches the settings cowatch needs from it.
     *
     * @return null on success, or a human-readable reason it failed
     */
    public String connect() {
        lastError = null;
        try {
            HttpResponse<String> res = get("/status");
            if (res.statusCode() == 401 || res.statusCode() == 403) {
                return lastError = "Seanime rejected the token. If the server has a password set, "
                        + "enter it here; otherwise leave the field blank.";
            }
            if (res.statusCode() != 200) {
                return lastError = "Seanime returned HTTP " + res.statusCode() + " for /status.";
            }

            JSONObject data = unwrap(res.body());
            JSONObject settings = data.optJSONObject("settings");
            JSONObject player = settings == null ? null : settings.optJSONObject("mediaPlayer");
            if (player != null) {
                mpvSocket     = player.optString("mpvSocket", "");
                defaultPlayer = player.optString("defaultPlayer", "");
            }
            JSONObject user = data.optJSONObject("user");
            if (user != null) {
                JSONObject viewer = user.optJSONObject("viewer");
                userName = viewer != null ? viewer.optString("name", "") : user.optString("name", "");
            }
            log.info("Seanime connected: player=" + defaultPlayer + " socket=" + mpvSocket);
            return null;

        } catch (Exception e) {
            return lastError = "Could not reach Seanime at " + baseUrl + " (" + e.getMessage() + "). "
                    + "Seanime binds to 127.0.0.1 by default; set host = 0.0.0.0 in its config.toml "
                    + "to reach it from another machine.";
        }
    }

    /**
     * The mpv IPC socket Seanime is configured to use, or blank if it is not pinned.
     *
     * <p>When this is empty Seanime generates a fresh socket name per launch
     * ({@code pid_timestamp_counter}), which cowatch cannot guess. The fix is on
     * the Seanime side: set a fixed name under Settings, Media Player, mpv socket.
     */
    public String getMpvSocket()      { return mpvSocket; }
    public String getDefaultPlayer()  { return defaultPlayer; }
    public String getUserName()       { return userName; }
    public String getLastError()      { return lastError; }
    public String getBaseUrl()        { return baseUrl; }

    // --- Local library ----------------------------------------------------

    /**
     * One scanned file in Seanime's library, reduced to what the room needs.
     *
     * @param path       absolute path Seanime scanned, as Seanime reports it
     * @param anilistId  AniList media id the file was matched to, 0 if unmatched
     * @param episode    episode number parsed from the file, 0 if unknown
     */
    public record LocalFile(String path, int anilistId, int episode) {}

    /**
     * Fetches every scanned file, keyed by a normalised path for lookup.
     *
     * <p>Keyed rather than returned as a list because the caller's question is
     * always "mpv says it is playing this path, what is it?" - and the library
     * can run to thousands of files, so a scan per lookup would be wasteful.
     */
    public Map<String, LocalFile> indexLocalFiles() {
        Map<String, LocalFile> index = new HashMap<>();
        try {
            HttpResponse<String> res = get("/library/local-files");
            if (res.statusCode() != 200) {
                log.warning("Seanime local-files returned HTTP " + res.statusCode());
                return index;
            }
            JSONArray arr = unwrapArray(res.body());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String path = obj.optString("path", "");
                if (path.isEmpty()) continue;

                int anilistId = obj.optInt("mediaId", 0);
                int episode   = 0;
                JSONObject meta = obj.optJSONObject("metadata");
                if (meta != null) {
                    episode = meta.optInt("episode", 0);
                    // aniDbEpisode is a string ("1", "S1") and is the one that
                    // matches AniList's numbering for split-cour shows, so
                    // prefer it when it parses as a plain number.
                    String aniDb = meta.optString("aniDbEpisode", "");
                    if (!aniDb.isEmpty()) {
                        try { episode = Integer.parseInt(aniDb.trim()); }
                        catch (NumberFormatException ignored) { /* keep metadata.episode */ }
                    }
                }
                index.put(normalizePath(path), new LocalFile(path, anilistId, episode));
            }
            log.info("Seanime library indexed: " + index.size() + " files");
        } catch (Exception e) {
            log.warning("Seanime indexLocalFiles failed: " + e.getMessage());
        }
        return index;
    }

    /**
     * Normalises a path for comparison against what mpv reports.
     *
     * <p>mpv echoes back whatever it was launched with, and Seanime may have
     * handed it a path with different separators or casing than the value in its
     * own database. Windows paths are compared case-insensitively; separators
     * are unified on every platform.
     */
    public static String normalizePath(String raw) {
        if (raw == null) return "";
        String p = raw.trim();
        if (p.startsWith("file://")) p = p.substring("file://".length());
        try {
            p = Path.of(p).toAbsolutePath().normalize().toString();
        } catch (Exception ignored) {
            // Not a valid local path (a URL, say) - fall through and compare raw.
        }
        p = p.replace('\\', '/');
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows ? p.toLowerCase(Locale.ROOT) : p;
    }

    // --- Progress ---------------------------------------------------------

    /**
     * Asks Seanime to track an episode cowatch is about to play itself.
     *
     * <p>Only for the direction where cowatch owns mpv. When the viewer started
     * playback from Seanime, Seanime is already tracking it and cowatch must
     * stay out of the way - two writers on one AniList entry will fight.
     *
     * @return null on success, or the reason it failed
     */
    public String startManualTracking(int anilistId, int episode) {
        JSONObject body = new JSONObject()
                .put("mediaId",       anilistId)
                .put("episodeNumber", episode)
                .put("clientId",      "cowatch");
        return post("/playback-manager/manual-tracking/start", body.toString());
    }

    /** Flushes tracked progress to AniList through Seanime. */
    public String syncCurrentProgress() {
        return post("/playback-manager/sync-current-progress", "{}");
    }

    /** Abandons a manual tracking session without recording progress. */
    public String cancelManualTracking() {
        return post("/playback-manager/manual-tracking/cancel", "{}");
    }

    // --- HTTP core --------------------------------------------------------

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1" + path))
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(15));
        applyToken(b);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** @return null on success, or a human-readable failure reason */
    private String post(String path, String jsonBody) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1" + path))
                    .header("Content-Type", "application/json")
                    .header("Accept",       "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(10));
            applyToken(b);

            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                log.warning("Seanime POST " + path + " returned " + res.statusCode()
                        + " body=" + res.body());
                return "Seanime returned HTTP " + res.statusCode() + " for " + path;
            }
            return null;
        } catch (Exception e) {
            log.warning("Seanime POST " + path + " failed: " + e.getMessage());
            return e.getMessage();
        }
    }

    /**
     * Adds the credential header, and only when there is one.
     *
     * <p>Seanime's API group runs an optional-auth middleware, so a local server
     * with no password set answers unauthenticated requests normally. Sending an
     * empty token would be worse than sending none.
     */
    private void applyToken(HttpRequest.Builder b) {
        if (!token.isEmpty()) b.header("X-Seanime-Token", token);
    }

    /**
     * Unwraps Seanime's {@code {"data": ...}} envelope.
     *
     * <p>Tolerates a bare object too, so a change in how the server wraps
     * responses degrades to a missing field rather than an exception.
     */
    private static JSONObject unwrap(String body) {
        JSONObject root = new JSONObject(body);
        JSONObject data = root.optJSONObject("data");
        return data != null ? data : root;
    }

    private static JSONArray unwrapArray(String body) {
        JSONObject root = new JSONObject(body);
        JSONArray data = root.optJSONArray("data");
        return data != null ? data : new JSONArray();
    }

    @SuppressWarnings("unused")
    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
