package com.syncplay.client.tracking;

import org.json.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.logging.*;

/**
 * AniList GraphQL API client.
 *
 * Requires a user OAuth token — get one from:
 *   https://anilist.co/settings/developer
 * Create an API client there, then use the implicit OAuth flow to get a token.
 * Paste the token into SyncPlay settings — no full OAuth flow needed in-app.
 *
 * Key mutation: SaveMediaListEntry — marks an episode as watched and
 * updates progress on the user's list.
 */
public class AniListClient {

    private static final Logger log = Logger.getLogger(AniListClient.class.getName());
    private static final String ENDPOINT = "https://graphql.anilist.co";

    private final String token;
    private final HttpClient http;

    public AniListClient(String token) {
        this.token = token;
        this.http  = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ─── Progress update ──────────────────────────────────────────────────

    /**
     * Update watch progress for a media entry.
     * Sets status to CURRENT if not already watching, increments progress
     * to the given episode number.
     *
     * @param mediaId       AniList media ID (not episode ID)
     * @param episodeNumber episode number just watched (1-based)
     */
    public void updateProgress(int mediaId, int episodeNumber) {
        if (token == null || token.isBlank()) {
            log.warning("AniList: no token configured, skipping progress update");
            return;
        }
        // Guard: a bad/unset mediaId (e.g. -1 from a failed searchMediaId call)
        // is the #1 cause of a silent 400 here. Catch it before it hits the wire.
        if (mediaId <= 0) {
            log.warning("AniList: refusing to update progress, invalid mediaId=" + mediaId);
            return;
        }
        if (episodeNumber < 0) {
            log.warning("AniList: refusing to update progress, invalid episodeNumber=" + episodeNumber);
            return;
        }

        String mutation =
                "mutation ($mediaId: Int, $progress: Int) {" +
                        "  SaveMediaListEntry(mediaId: $mediaId, progress: $progress, status: CURRENT) {" +
                        "    id progress status" +
                        "  }" +
                        "}";

        JSONObject variables = new JSONObject();
        variables.put("mediaId",  mediaId);
        variables.put("progress", episodeNumber);

        try {
            JSONObject result = graphql(mutation, variables);
            if (result != null) {
                log.info("AniList: updated mediaId=" + mediaId
                        + " progress=" + episodeNumber);
            }
        } catch (Exception e) {
            log.warning("AniList updateProgress failed: " + e.getMessage());
        }
    }

    /**
     * Search AniList for an anime by title and return its media ID.
     * Used when playing local files where you don't have an AniList ID directly.
     *
     * @param title anime title (romaji, english, or native)
     * @return AniList media ID, or -1 if not found
     */
    public int searchMediaId(String title) {
        if (token == null || token.isBlank()) return -1;
        if (title == null || title.isBlank()) {
            log.warning("AniList: searchMediaId called with empty title");
            return -1;
        }

        String query =
                "query ($search: String) {" +
                        "  Media(search: $search, type: ANIME) {" +
                        "    id title { romaji english native }" +
                        "  }" +
                        "}";

        JSONObject variables = new JSONObject();
        variables.put("search", title);

        try {
            JSONObject result = graphql(query, variables);
            if (result == null) return -1;
            JSONObject data = result.optJSONObject("data");
            if (data == null || data.isNull("Media")) {
                log.warning("AniList: no media match for title='" + title + "'");
                return -1;
            }
            return data.getJSONObject("Media").getInt("id");
        } catch (Exception e) {
            log.warning("AniList search failed for title='" + title + "': " + e.getMessage());
            return -1;
        }
    }

    /**
     * Verify the token works by fetching the viewer's username.
     * @return username on success, null on failure.
     */
    public String verifyToken() {
        if (token == null || token.isBlank()) return null;
        String query = "query { Viewer { id name } }";
        try {
            JSONObject result = graphql(query, new JSONObject());
            if (result == null) return null;
            return result.getJSONObject("data").getJSONObject("Viewer").getString("name");
        } catch (Exception e) {
            log.warning("AniList token verify failed: " + e.getMessage());
            return null;
        }
    }

    // ─── GraphQL core ─────────────────────────────────────────────────────

    private JSONObject graphql(String query, JSONObject variables) throws Exception {
        JSONObject body = new JSONObject();
        body.put("query",     query);
        body.put("variables", variables);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .header("Content-Type",  "application/json")
                .header("Accept",        "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() == 401) {
            log.warning("AniList: token is invalid or expired");
            return null;
        }
        if (res.statusCode() == 429) {
            log.warning("AniList: rate limited");
            return null;
        }
        if (res.statusCode() != 200) {
            // Previously this swallowed the body, so 400s were undiagnosable.
            // The body usually contains the exact GraphQL validation error.
            log.warning("AniList GraphQL returned " + res.statusCode() + ": " + res.body());
            return null;
        }

        JSONObject json = new JSONObject(res.body());
        if (json.has("errors")) {
            log.warning("AniList GraphQL errors: " + json.getJSONArray("errors"));
            return null;
        }
        return json;
    }

    public boolean hasToken() { return token != null && !token.isBlank(); }
}