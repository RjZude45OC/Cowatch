package com.cowatch.client.seanime;

import org.json.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Talks to a Seanime server's {@code /api/v1} surface.
 *
 * <p>Seanime is the other half of a mixed room: one viewer streams an episode
 * from Jellyfin while the other plays something Seanime opened - a file its
 * auto-downloader fetched, or a torrent or debrid stream. cowatch uses this
 * client for three things:
 *
 * <ol>
 *   <li>finding the mpv IPC socket Seanime launched its player on, so cowatch
 *       can attach to that player instead of starting a second one;</li>
 *   <li>learning which AniList media id and episode is playing, which is what
 *       the room relays - from Seanime's live playback events where possible
 *       (see {@link SeanimeEvents}), and from the scanned library as a
 *       fallback;</li>
 *   <li>handing episode progress to Seanime rather than writing to AniList
 *       directly, so the two do not race on the same list entry.</li>
 * </ol>
 *
 * <p>Checked against Seanime 3.10.3. Seanime's own watch-party feature (Nakama)
 * is not usable here: its peer protocol assumes every participant is a Seanime
 * instance, and Seanime cannot read a Jellyfin library at all.
 */
public class SeanimeClient {

    private static final Logger log = Logger.getLogger(SeanimeClient.class.getName());

    /** Seanime's default port. Its server binds 127.0.0.1 unless reconfigured. */
    public static final int DEFAULT_PORT = 43211;

    /**
     * The socket names older Seanime versions shipped as the default.
     *
     * <p>Seanime treats these exactly like a blank setting: it launches every
     * player on a fresh {@code mpv_ipc_<pid>_<nanos>_<n>} name instead. Plenty of
     * installs still carry one in their settings, so cowatch must not take it at
     * face value. See {@code internal/mediaplayers/mpv/mpv.go} upstream.
     */
    private static final String LEGACY_SOCKET_WINDOWS = "\\\\.\\pipe\\mpv_ipc";
    private static final String LEGACY_SOCKET_UNIX    = "/tmp/mpv_socket";

    /** Generated names: prefix, Seanime's pid, a nanosecond timestamp, a counter. */
    private static final Pattern AUTO_SOCKET =
            Pattern.compile("^(?:mpv_ipc|mpv_socket)_(\\d+)_(\\d+)_(\\d+)$");

    private final String baseUrl;
    private final String tokenHash;
    private final HttpClient http;

    /** Filled in by {@link #connect()}. */
    private String mpvSocket     = "";
    private String defaultPlayer = "";
    private String userName      = "";
    private String version       = "";
    private boolean hasPassword  = false;
    private String lastError;

    /**
     * @param baseUrl   root of the Seanime server, e.g. {@code http://127.0.0.1:43211}
     * @param tokenHash {@link #hashPassword(String) the hashed server password},
     *                  or blank when the server has none
     */
    public SeanimeClient(String baseUrl, String tokenHash) {
        this.baseUrl   = normalizeBaseUrl(baseUrl);
        this.tokenHash = tokenHash == null ? "" : tokenHash.trim();
        this.http      = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Turns the server password into the value Seanime expects on the wire.
     *
     * <p>Seanime never compares the password itself: {@code X-Seanime-Token}
     * and the websocket's {@code token} parameter must both carry the lowercase
     * hex SHA-256 of it. Sending the plain password is rejected exactly like a
     * wrong one. Storing the hash rather than the password also means cowatch's
     * settings file never holds the password in clear text.
     *
     * @return the hash, or blank for a blank password
     */
    public static String hashPassword(String password) {
        if (password == null || password.isEmpty()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
            if (res.statusCode() == 403) {
                return lastError = "Seanime refused the connection from this address. "
                        + "Without a server password it only answers local and LAN clients; "
                        + "set a password in Seanime's config.toml to connect from elsewhere.";
            }
            if (res.statusCode() != 200) {
                return lastError = "Seanime returned HTTP " + res.statusCode() + " for /status"
                        + errorSuffix(res.body()) + ".";
            }

            JSONObject data = unwrap(res.body());
            hasPassword = data.optBoolean("serverHasPassword", false);
            version     = data.optString("version", "");

            JSONObject settings = data.optJSONObject("settings");
            JSONObject player   = settings == null ? null : settings.optJSONObject("mediaPlayer");

            // A missing or wrong token does not fail /status. Seanime answers 200
            // with a stripped-down status instead - no settings, a placeholder
            // user - so the only tell is a password-protected server that
            // withheld its settings.
            if (hasPassword && player == null) {
                return lastError = tokenHash.isEmpty()
                        ? "This Seanime server has a password. Enter it under "
                          + "\"Server has a password\" and connect again."
                        : "Seanime rejected the password.";
            }

            if (player != null) {
                mpvSocket     = player.optString("mpvSocket", "");
                defaultPlayer = player.optString("defaultPlayer", "");
            }
            JSONObject user = data.optJSONObject("user");
            if (user != null) {
                JSONObject viewer = user.optJSONObject("viewer");
                userName = viewer != null ? viewer.optString("name", "") : user.optString("name", "");
            }
            log.info("Seanime " + version + " connected: player=" + defaultPlayer
                    + " socket=" + mpvSocket + " password=" + hasPassword);
            return null;

        } catch (IOException e) {
            return lastError = "Could not reach Seanime at " + baseUrl + " (" + describe(e) + "). "
                    + "Check Seanime is running. It listens on 127.0.0.1 by default; set "
                    + "host = 0.0.0.0 and a password in its config.toml to reach it from "
                    + "another machine.";
        } catch (Exception e) {
            return lastError = "Unexpected reply from Seanime at " + baseUrl + ": " + describe(e);
        }
    }

    /** The mpv socket from Seanime's settings, verbatim. May be blank or a legacy name. */
    public String getMpvSocket()      { return mpvSocket; }
    public String getDefaultPlayer()  { return defaultPlayer; }
    public String getUserName()       { return userName; }
    public String getVersion()        { return version; }
    public String getLastError()      { return lastError; }
    public String getBaseUrl()        { return baseUrl; }
    public String getTokenHash()      { return tokenHash; }

    /**
     * True when Seanime is set to launch an external mpv, which is the only
     * player cowatch can attach to.
     *
     * <p>VLC, MPC-HC and IINA have no mpv IPC socket, and neither do the players
     * built into Seanime itself (the web player, and Seanime Denshi's embedded
     * libmpv), which are driven over Seanime's own channels.
     */
    public boolean usesExternalMpv() {
        return defaultPlayer.isBlank() || "mpv".equalsIgnoreCase(defaultPlayer);
    }

    /**
     * True when Seanime picks a fresh socket name per launch instead of using the
     * configured one. That is the case for a blank setting and for the legacy
     * default name, which is what most installs have.
     */
    public boolean usesAutoSocket() {
        String s = mpvSocket.trim();
        return s.isEmpty() || s.equals(LEGACY_SOCKET_WINDOWS) || s.equals(LEGACY_SOCKET_UNIX);
    }

    /**
     * The sockets worth trying, best first.
     *
     * <p>For a fixed socket name that is just the name. Otherwise it is every
     * socket Seanime generated that still exists, newest first, so the player it
     * opened most recently wins. Only works when cowatch runs on the same machine
     * as Seanime, which attaching needs anyway: the IPC socket is local.
     */
    public List<String> mpvSocketCandidates() {
        if (!usesAutoSocket()) return List.of(mpvSocket.trim());
        return discoverAutoSockets();
    }

    /** Finds sockets named the way Seanime names them, newest first. */
    static List<String> discoverAutoSockets() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<Path> dirs = new ArrayList<>();
        if (windows) {
            dirs.add(Path.of("\\\\.\\pipe\\"));
        } else {
            // Seanime uses Go's os.TempDir(): $TMPDIR, else /tmp. The JVM's own
            // temp dir usually matches but is not guaranteed to.
            String tmpdir = System.getenv("TMPDIR");
            if (tmpdir != null && !tmpdir.isBlank()) dirs.add(Path.of(tmpdir));
            dirs.add(Path.of(System.getProperty("java.io.tmpdir", "/tmp")));
            dirs.add(Path.of("/tmp"));
        }

        // Keyed on the full path so the same socket seen via two dirs counts once.
        Map<String, Long> found = new LinkedHashMap<>();
        for (Path dir : dirs) {
            try (Stream<Path> entries = Files.list(dir)) {
                entries.forEach(p -> {
                    Path name = p.getFileName();
                    if (name == null) return;
                    Matcher m = AUTO_SOCKET.matcher(name.toString());
                    if (!m.matches()) return;
                    if (windows != name.toString().startsWith("mpv_ipc_")) return;
                    String full = windows ? "\\\\.\\pipe\\" + name : p.toAbsolutePath().toString();
                    found.putIfAbsent(full, parseLongOr(m.group(2), 0L));
                });
            } catch (IOException | RuntimeException e) {
                log.fine("Could not list " + dir + ": " + e.getMessage());
            }
        }
        List<String> sockets = new ArrayList<>(found.keySet());
        sockets.sort(Comparator.comparingLong((String s) -> found.get(s)).reversed());
        // Polled every couple of seconds while waiting for a player, so fine.
        log.fine("Seanime mpv sockets found: " + sockets);
        return sockets;
    }

    // --- Local library ----------------------------------------------------

    /**
     * One scanned file in Seanime's library, reduced to what the room needs.
     *
     * @param path       absolute path Seanime scanned, as Seanime reports it
     * @param anilistId  AniList media id the file was matched to, 0 if unmatched
     * @param episode    AniList progress number Seanime tracks the file as
     */
    public record LocalFile(String path, int anilistId, int episode) {}

    /**
     * Fetches every trackable scanned file, keyed by a normalised path for lookup.
     *
     * <p>Keyed rather than returned as a list because the caller's question is
     * always "mpv says it is playing this path, what is it?" - and the library
     * can run to thousands of files, so a scan per lookup would be wasteful.
     *
     * <p>Only main episodes are kept. Specials and openings/endings carry their
     * own numbering, and announcing one would point the room at the wrong
     * episode of the main series.
     */
    public Map<String, LocalFile> indexLocalFiles() {
        Map<String, LocalFile> index = new HashMap<>();
        try {
            HttpResponse<String> res = get("/library/local-files");
            if (res.statusCode() != 200) {
                log.warning("Seanime local-files returned HTTP " + res.statusCode() + errorSuffix(res.body()));
                return index;
            }
            JSONArray arr = unwrapArray(res.body());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String path = obj.optString("path", "");
                if (path.isEmpty()) continue;

                int anilistId = obj.optInt("mediaId", 0);
                JSONObject meta = obj.optJSONObject("metadata");
                if (meta == null || anilistId <= 0) continue;

                String type = meta.optString("type", "main");
                if (!type.isEmpty() && !"main".equals(type)) continue;

                // metadata.episode is the number Seanime itself sends to AniList.
                // aniDBEpisode is AniDB's numbering ("S1", or absolute for a
                // second cour) and would be wrong for exactly the shows where the
                // two differ.
                int episode = meta.optInt("episode", 0);
                if (episode <= 0) continue;

                index.put(normalizePath(path), new LocalFile(path, anilistId, episode));
            }
            log.info("Seanime library indexed: " + index.size() + " files");
        } catch (Exception e) {
            log.warning("Seanime indexLocalFiles failed: " + describe(e));
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
        if (!p.contains("://")) {
            try {
                p = Path.of(p).toAbsolutePath().normalize().toString();
            } catch (Exception ignored) {
                // Not a valid local path - fall through and compare raw.
            }
        }
        p = p.replace('\\', '/');
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
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

    /**
     * Asks Seanime to open a library file in its media player, as if the viewer
     * had pressed play on it in Seanime.
     *
     * <p>Seanime then launches mpv and tracks progress itself, exactly as for
     * playback started from its own UI, and cowatch attaches to that player.
     *
     * @param path the file's path as Seanime's library lists it
     * @return null on success, or the reason it failed
     */
    public String playFile(String path) {
        return post("/playback-manager/play", new JSONObject().put("path", path).toString());
    }

    /** Flushes tracked progress to AniList through Seanime. */
    public String syncCurrentProgress() {
        return post("/playback-manager/sync-current-progress", "{}");
    }

    /** Abandons a manual tracking session without recording progress. */
    public String cancelManualTracking() {
        return post("/playback-manager/manual-tracking/cancel", "{}");
    }

    // --- Events -----------------------------------------------------------

    /** The websocket address for Seanime's live event stream. */
    URI eventsUri() {
        String ws = baseUrl.replaceFirst("^http", "ws") + "/events";
        if (!tokenHash.isEmpty()) ws += "?token=" + tokenHash;
        return URI.create(ws);
    }

    HttpClient httpClient() { return http; }

    // --- HTTP core --------------------------------------------------------

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
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
                return "Seanime returned HTTP " + res.statusCode() + " for " + path
                        + errorSuffix(res.body());
            }
            return null;
        } catch (Exception e) {
            log.warning("Seanime POST " + path + " failed: " + describe(e));
            return describe(e);
        }
    }

    /**
     * Adds the credential header, and only when there is one.
     *
     * <p>Seanime's API group runs an optional-auth middleware, so a server with
     * no password set answers unauthenticated requests normally.
     */
    private void applyToken(HttpRequest.Builder b) {
        if (!tokenHash.isEmpty()) b.header("X-Seanime-Token", tokenHash);
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

    /** Seanime's own error text, as ": text", or blank when there is none. */
    private static String errorSuffix(String body) {
        try {
            String err = new JSONObject(body).optString("error", "");
            return err.isBlank() ? "" : ": " + err;
        } catch (Exception e) {
            return "";
        }
    }

    /** An exception's message, or its type when it has none (timeouts often don't). */
    private static String describe(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }

    private static long parseLongOr(String s, long fallback) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return fallback; }
    }
}
