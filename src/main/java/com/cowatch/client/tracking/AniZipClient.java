package com.cowatch.client.tracking;

import org.json.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.logging.*;

/**
 * Client for the AniZip API — https://api.ani.zip
 *
 * AniZip provides episode-level mappings between:
 *   - AniList media IDs
 *   - TheTVDB season+episode numbers (what Jellyfin uses)
 *   - AniDB absolute episode numbers (what torrent releases use)
 *
 * This solves two problems:
 *   1. "Wrong season" — find the correct AniList media ID from a TVDB ID
 *   2. "Wrong episode" — convert Jellyfin's TVDB episode number to AniList relative ep
 *
 * No API key required. Public endpoint.
 */
public class AniZipClient {

    private static final Logger log = Logger.getLogger(AniZipClient.class.getName());
    private static final String BASE = "https://api.ani.zip/v1";

    private final HttpClient http;

    public AniZipClient() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ─── Episode resolution ───────────────────────────────────────────────

    /**
     * Given an AniList media ID and a Jellyfin-reported season+episode number
     * (TheTVDB-style), return the correct AniList-relative episode number to
     * send as progress.
     *
     * Jellyfin mirrors TheTVDB numbering, which AniZip stores per episode as
     * seasonNumber + episodeNumber. The map key in AniZip's response is the
     * AniList-relative episode number (what you send to SaveMediaListEntry).
     *
     * @param anilistId   AniList media ID
     * @param tvdbSeason  Jellyfin/TVDB season number (e.g. 2)
     * @param tvdbEpisode Jellyfin/TVDB episode number (e.g. 5)
     * @return AniList-relative episode number, or -1 if not found
     */
    public int resolveJellyfinEpisode(int anilistId, int tvdbSeason, int tvdbEpisode) {
        JSONObject episodes = fetchEpisodes(anilistId);
        if (episodes == null) return -1;

        for (String key : episodes.keySet()) {
            // Skip special keys like "S1", "S2"
            if (!key.matches("\\d+")) continue;

            JSONObject ep = episodes.optJSONObject(key);
            if (ep == null) continue;

            int sn = ep.optInt("seasonNumber",  -1);
            int en = ep.optInt("episodeNumber", -1);

            if (sn == tvdbSeason && en == tvdbEpisode) {
                try {
                    int relativeEp = Integer.parseInt(key);
                    log.info("AniZip: TVDB S" + tvdbSeason + "E" + tvdbEpisode
                            + " → AniList ep " + relativeEp
                            + " for anilistId=" + anilistId);
                    return relativeEp;
                } catch (NumberFormatException e) {
                    log.warning("AniZip: non-numeric key '" + key + "'");
                }
            }
        }

        log.warning("AniZip: no match for anilistId=" + anilistId
                + " TVDB S" + tvdbSeason + "E" + tvdbEpisode);
        return -1;
    }

    /**
     * Resolve a local-file absolute episode number to an AniList-relative episode.
     * Used when the file's episode number exceeds the media's own episode count
     * (split-cour, sequel seasons with continuous numbering).
     *
     * Implements Seanime's part-vs-absolute discrepancy detection (section 3.3/3.4):
     *   - Fetch episode "1" from AniZip
     *   - If episodeNumber > 1 AND absoluteEpisodeNumber - episodeNumber > 1,
     *     the media uses part-relative numbering
     *   - Apply the appropriate offset formula
     *
     * @param anilistId       AniList media ID
     * @param absoluteEpisode absolute episode number from filename (e.g. 14)
     * @param mediaEpCount    AniList's reported episode count for this media
     * @return AniList-relative episode number, or absoluteEpisode as fallback
     */
    public int resolveAbsoluteEpisode(int anilistId, int absoluteEpisode, int mediaEpCount) {
        // Fast path: episode within media's own count → use directly
        if (mediaEpCount > 0 && absoluteEpisode <= mediaEpCount) {
            return absoluteEpisode;
        }

        JSONObject episodes = fetchEpisodes(anilistId);
        if (episodes == null) return absoluteEpisode;

        // Get episode "1" to detect part-relative numbering
        JSONObject firstEp = episodes.optJSONObject("1");
        if (firstEp == null) return absoluteEpisode;

        int firstEpNumber         = firstEp.optInt("episodeNumber",         1);
        int firstAbsoluteEpNumber = firstEp.optInt("absoluteEpisodeNumber", 1);

        // Seanime's discrepancy detection:
        // usePartEpisodeNumber := firstEp.EpisodeNumber > 1
        //     && firstEp.AbsoluteEpisodeNumber - firstEp.EpisodeNumber > 1
        boolean usePartRelative = firstEpNumber > 1
                && (firstAbsoluteEpNumber - firstEpNumber) > 1;

        int relativeEp;
        if (usePartRelative) {
            // Part-relative: offset from the start of this part
            int minPartAbsolute = firstEpNumber;
            relativeEp = absoluteEpisode - (minPartAbsolute - 1);
            log.info("AniZip: part-relative ep " + absoluteEpisode
                    + " → " + relativeEp + " for anilistId=" + anilistId);
        } else {
            // True-absolute: offset from episode 1's absolute number
            int minAbsolute = firstAbsoluteEpNumber;
            relativeEp = absoluteEpisode - (minAbsolute - 1);
            log.info("AniZip: absolute ep " + absoluteEpisode
                    + " → relative " + relativeEp + " for anilistId=" + anilistId);
        }

        if (relativeEp < 1) {
            log.warning("AniZip: relative ep < 1 (" + relativeEp
                    + ") for absolute=" + absoluteEpisode + " — using raw");
            return absoluteEpisode;
        }

        return relativeEp;
    }

    /**
     * Find the AniList media ID for a given TheTVDB series ID.
     * Used to resolve the correct AniList entry from Jellyfin metadata
     * without relying on title search (which gets the wrong season).
     *
     * @param tvdbId TheTVDB series ID (integer)
     * @return AniList media ID, or -1 if not found
     */
    public int findAniListIdByTvdbId(int tvdbId) {
        try {
            String url = BASE + "/mappings?thetvdb_id=" + tvdbId;
            String body = get(url);
            if (body == null) return -1;

            JSONObject json = new JSONObject(body);
            // AniZip mappings response: { "mappings": [ { "anilist_id": 123, ... } ] }
            JSONArray mappings = json.optJSONArray("mappings");
            if (mappings == null || mappings.isEmpty()) {
                // Try direct field
                int id = json.optInt("anilist_id", -1);
                if (id > 0) {
                    log.info("AniZip: TVDB " + tvdbId + " → AniList " + id);
                    return id;
                }
                log.warning("AniZip: no AniList mapping for TVDB " + tvdbId);
                return -1;
            }

            int id = mappings.getJSONObject(0).optInt("anilist_id", -1);
            log.info("AniZip: TVDB " + tvdbId + " → AniList " + id);
            return id;

        } catch (Exception e) {
            log.warning("AniZip findByTvdbId failed: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Everything AniZip knows about one AniList entry: {@code titles},
     * {@code episodes} (keyed by AniList episode number, each with a TVDB
     * season and episode and an absolute number) and {@code mappings} (other
     * sites' ids, including {@code thetvdb_id}).
     *
     * <p>This is what turns an episode someone else announced into a file in
     * this viewer's own library.
     *
     * @return the whole response, or null on failure
     */
    public JSONObject fetchShow(int anilistId) {
        try {
            String body = get(BASE + "/episodes?anilist_id=" + anilistId);
            return body == null ? null : new JSONObject(body);
        } catch (Exception e) {
            log.warning("AniZip fetchShow failed for " + anilistId + ": " + e.getMessage());
            return null;
        }
    }

    // ─── Raw fetch ────────────────────────────────────────────────────────

    /**
     * Fetch the episodes map for an AniList media ID.
     * Returns the "episodes" JSONObject from AniZip, or null on failure
     * (including a failure that persists after a retry).
     * Keys are AniList-relative episode numbers ("1", "2", ..., "S1" for specials).
     */
    public JSONObject fetchEpisodes(int anilistId) {
        try {
            String url  = BASE + "/episodes?anilist_id=" + anilistId;
            String body = get(url);
            if (body == null) return null;

            JSONObject json = new JSONObject(body);
            JSONObject eps  = json.optJSONObject("episodes");
            if (eps == null) {
                log.warning("AniZip: no episodes in response for anilistId=" + anilistId);
                return null;
            }
            return eps;
        } catch (Exception e) {
            log.warning("AniZip fetchEpisodes failed for " + anilistId + ": " + e.getMessage());
            return null;
        }
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() == 404) {
            log.warning("AniZip: 404 for " + url);
            return retryOnce(url, "404");
        }
        if (res.statusCode() >= 500) {
            log.warning("AniZip: " + res.statusCode() + " for " + url);
            return retryOnce(url, String.valueOf(res.statusCode()));
        }
        if (res.statusCode() != 200) {
            log.warning("AniZip: " + res.statusCode() + " for " + url);
            return null;
        }
        return res.body();
    }

    /**
     * Performs exactly one retry for status-code failures (404/5xx), with a
     * short backoff. Tracked via a thread-local-free simple recursion guard:
     * since get() always calls this directly (never itself), recursion depth
     * is bounded to 1 retry by construction — see retryOnce's single call site.
     */
    private String retryOnce(String url, String reason) throws Exception {
        log.info("AniZip: retrying after " + reason + " for " + url);
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            log.warning("AniZip: retry also failed (" + res.statusCode() + ") for " + url);
            return null;
        }
        log.info("AniZip: retry succeeded for " + url);
        return res.body();
    }
}