package com.cowatch.client.model;

/**
 * Where each viewer's copy of the media comes from.
 *
 * <p>The mode changes only what the media panel offers as a source. Sync
 * signals travel over the cowatch server either way, so viewers in different
 * modes can still watch together as long as their files line up — the offset
 * panel exists to absorb the cases where they do not.
 */
public enum SyncMode {

    LOCAL(
        "Local",
        "Both users play a local copy of the same file.\nSync signals are relayed via the cowatch server.",
        "🖥"
    ),
    JELLYFIN(
        "Jellyfin",
        "Both users stream from the same Jellyfin server.\nThe cleanest option — no file sharing needed.",
        "☁"
    ),

    /**
     * Playback lives in a Seanime library, and may have been started from
     * Seanime rather than from cowatch.
     *
     * <p>cowatch attaches to the mpv instance Seanime launched instead of
     * opening its own, so a room stays in sync whichever side pressed play.
     * Seanime keeps ownership of the AniList update, because it is already
     * tracking the episode it started.
     */
    SEANIME(
        "Seanime",
        "Play from a Seanime library, including episodes its auto-downloader fetched."
            + "\ncowatch attaches to the mpv window Seanime opens.",
        "\u2693"
    );

    public final String label;
    public final String description;
    public final String icon;

    SyncMode(String label, String description, String icon) {
        this.label       = label;
        this.description = description;
        this.icon        = icon;
    }
}
