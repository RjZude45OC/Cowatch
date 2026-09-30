package com.cowatch.client.model;

/**
 * The three supported sync modes.
 *
 *  LOCAL_TO_LOCAL   — both users play a local file over LAN/internet relay
 *  LOCAL_TO_JELLYFIN— one user has local file, other streams from Jellyfin (rare edge case)
 *  JELLYFIN_TO_JELLYFIN — both users stream from the same Jellyfin server (recommended)
 */
public enum SyncMode {

    LOCAL_TO_LOCAL(
        "Local ↔ Local",
        "Both users play a local copy of the same file.\nSync signals are relayed via the cowatch server.",
        "🖥"
    ),

    LOCAL_TO_JELLYFIN(
        "Local ↔ Jellyfin",
        "One user plays a local file, the other streams from Jellyfin.\nBoth sides sync via the cowatch server.",
        "🔀"
    ),

    JELLYFIN_TO_JELLYFIN(
        "Jellyfin ↔ Jellyfin",
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
