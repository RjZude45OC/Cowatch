package com.cowatch.client.tracking;

import org.json.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.logging.*;

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

    // ─── Progress update (full logic from Seanime section 4) ──────────────

    /**
     * Update AniList progress for a media entry.
     *
     * Implements Seanime's update rules:
     *   - Auto-completes if newProgress >= totalEpisodeCount (and count is known)
     *   - Preserves REPEATING status if user is rewatching
     *   - Clamps progress to totalEpisodeCount if it would exceed it
     *   - Sets status to CURRENT if not yet started
     *
     * @param mediaId           AniList media ID
     * @param episodeNumber     relative episode number (1-based, AniList-relative)
     * @param totalEpisodeCount total episodes in this media entry (0 if unknown)
     */
    public void updateProgress(int mediaId, int episodeNumber, int totalEpisodeCount) {
        if (token == null || token.isBlank()) return;
        if (mediaId <= 0) { log.warning("AniList: invalid mediaId=" + mediaId); return; }
        if (episodeNumber < 0) { log.warning("AniList: invalid ep=" + episodeNumber); return; }

        // Clamp to total episode count
        int clampedEp = (totalEpisodeCount > 0 && episodeNumber > totalEpisodeCount)
                ? totalEpisodeCount
                : episodeNumber;

        // Auto-complete if watched last episode
        String status;
        if (totalEpisodeCount > 0 && clampedEp >= totalEpisodeCount) {
            status = "COMPLETED";
        } else {
            // Check current status — preserve REPEATING if rewatching
            status = getCurrentStatusOrDefault(mediaId);
        }

        String mutation =
                "mutation ($mediaId: Int, $progress: Int, $status: MediaListStatus) {" +
                        "  SaveMediaListEntry(mediaId: $mediaId, progress: $progress, status: $status) {" +
                        "    id progress status" +
                        "  }" +
                        "}";

        JSONObject variables = new JSONObject();
        variables.put("mediaId",  mediaId);
        variables.put("progress", clampedEp);
        variables.put("status",   status);

        try {
            JSONObject result = graphql(mutation, variables);
            if (result != null) {
                log.info("AniList: updated mediaId=" + mediaId
                        + " progress=" + clampedEp + " status=" + status);
            }
        } catch (Exception e) {
            log.warning("AniList updateProgress failed: " + e.getMessage());
        }
    }

    /** Convenience overload — no total episode count known. */
    public void updateProgress(int mediaId, int episodeNumber) {
        updateProgress(mediaId, episodeNumber, 0);
    }

    // ─── Current status check (for REPEATING preservation) ────────────────

    private String getCurrentStatusOrDefault(int mediaId) {
        try {
            String query =
                    "query ($mediaId: Int) {" +
                            "  MediaList(mediaId: $mediaId, userName: null) {" +
                            "    status" +
                            "  }" +
                            "}";
            JSONObject variables = new JSONObject();
            variables.put("mediaId", mediaId);

            JSONObject result = graphql(query, variables);
            if (result == null) return "CURRENT";

            JSONObject data = result.optJSONObject("data");
            if (data == null) return "CURRENT";

            JSONObject list = data.optJSONObject("MediaList");
            if (list == null) return "CURRENT";

            String status = list.optString("status", "CURRENT");
            // Preserve REPEATING — don't flip back to CURRENT on rewatch
            return "REPEATING".equals(status) ? "REPEATING" : "CURRENT";
        } catch (Exception e) {
            return "CURRENT";
        }
    }

    // ─── Search by title + optional year ──────────────────────────────────

    public int searchMediaId(String title) {
        return searchMediaId(title, 0);
    }

    /**
     * Search AniList for media by title and optional year.
     * Using seasonYear avoids returning the wrong season for multi-season series.
     * Falls back to no-year search if year-filtered search returns nothing.
     */
    public int searchMediaId(String title, int year) {
        // No token needed: media search is public, and announcing what is
        // playing should not depend on having set up AniList tracking.
        if (title == null || title.isBlank()) return -1;

        JSONObject variables = new JSONObject();
        variables.put("search", title);

        String query;
        if (year > 0) {
            query =
                    "query ($search: String, $year: Int) {" +
                            "  Media(search: $search, type: ANIME, seasonYear: $year, sort: POPULARITY_DESC) {" +
                            "    id title { romaji english native }" +
                            "  }" +
                            "}";
            variables.put("year", year);
            log.info("AniList: searching '" + title + "' year=" + year);
        } else {
            query =
                    "query ($search: String) {" +
                            "  Media(search: $search, type: ANIME, sort: POPULARITY_DESC) {" +
                            "    id title { romaji english native }" +
                            "  }" +
                            "}";
            log.info("AniList: searching '" + title + "' (no year)");
        }

        try {
            JSONObject result = graphql(query, variables);
            if (result == null) return -1;

            JSONObject data = result.optJSONObject("data");
            if (data == null || data.isNull("Media")) {
                if (year > 0) {
                    log.warning("AniList: no result for '" + title + "' year=" + year
                            + " — retrying without year");
                    return searchMediaId(title, 0);
                }
                log.warning("AniList: no match for '" + title + "'");
                return -1;
            }

            int id = data.getJSONObject("Media").getInt("id");
            log.info("AniList: found mediaId=" + id + " for '" + title + "'");
            return id;
        } catch (Exception e) {
            log.warning("AniList search failed '" + title + "': " + e.getMessage());
            return -1;
        }
    }

    /**
     * The titles and format of one entry: {@code title.romaji}, {@code
     * title.english}, {@code title.native}, {@code synonyms} and {@code format}
     * ("TV", "MOVIE", ...). Public data, so no token is needed.
     *
     * <p>Used to find someone else's episode in this viewer's library when
     * AniZip has no ids to go on, which is common for films.
     *
     * @return the Media object, or null on failure
     */
    public JSONObject fetchMedia(int mediaId) {
        try {
            JSONObject result = graphql(
                    "query ($id: Int) { Media(id: $id, type: ANIME) {"
                  + " id format episodes title { romaji english native } synonyms } }",
                    new JSONObject().put("id", mediaId));
            if (result == null) return null;
            JSONObject data = result.optJSONObject("data");
            return data == null ? null : data.optJSONObject("Media");
        } catch (Exception e) {
            log.warning("AniList fetchMedia failed for " + mediaId + ": " + e.getMessage());
            return null;
        }
    }

    // ─── Token verify ─────────────────────────────────────────────────────

    public String verifyToken() {
        if (token == null || token.isBlank()) return null;
        try {
            JSONObject result = graphql("query { Viewer { id name } }", new JSONObject());
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

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .header("Content-Type",  "application/json")
                .header("Accept",        "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(15));
        // Media search is public. A "Bearer " with no token behind it is
        // rejected, so only send one when there is one; that way a viewer
        // without a token can still look up the AniList id to announce.
        if (hasToken()) b.header("Authorization", "Bearer " + token);
        HttpRequest req = b.build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() == 401) { log.warning("AniList: token invalid/expired"); return null; }
        if (res.statusCode() == 429) { log.warning("AniList: rate limited"); return null; }
        if (res.statusCode() != 200) {
            log.warning("AniList GraphQL " + res.statusCode() + ": " + res.body());
            return null;
        }

        JSONObject json = new JSONObject(res.body());
        if (json.has("errors")) {
            log.warning("AniList errors: " + json.getJSONArray("errors"));
            return null;
        }
        return json;
    }

    public boolean hasToken() { return token != null && !token.isBlank(); }
}