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
