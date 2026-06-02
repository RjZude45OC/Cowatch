package com.syncplay.client.jellyfin;

/** A single item returned from the Jellyfin library. */
public record JellyfinItem(
    String id,
    String name,
    String type,
    String overview,
    int    year,
    int    episodeNumber,
    int    seasonNumber
) {
    /** Display label for lists — e.g. "S01E03 - Episode Name" */
    public String displayLabel() {
        return switch (type) {
            case "Episode" -> String.format("S%02dE%02d - %s",
                seasonNumber, episodeNumber, name);
            case "Season"  -> name;
            default        -> year > 0 ? name + " (" + year + ")" : name;
        };
    }

    @Override public String toString() { return displayLabel(); }
}
