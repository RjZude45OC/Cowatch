package com.syncplay.client.model;

/**
 * Immutable snapshot of everything needed to start a sync session.
 * Built by the UI and handed to the SyncEngine.
 */
public class SessionConfig {

    // ── Sync relay server ──────────────────────────────────────────────────
    public final String  serverHost;
    public final int     serverPort;
    public final String  roomId;
    public final String  username;

    // ── Mode ───────────────────────────────────────────────────────────────
    public final SyncMode mode;

    // ── Local file (used in LOCAL_TO_LOCAL and LOCAL_TO_JELLYFIN) ─────────
    public final String localFilePath;   // may be null

    // ── Jellyfin (used in JELLYFIN_TO_JELLYFIN and LOCAL_TO_JELLYFIN) ─────
    public final String jellyfinBaseUrl; // e.g. https://jellyfin.example.com
    public final String jellyfinApiKey;
    public final String jellyfinItemId;  // the media item ID
    public final String jellyfinUserId;

    private SessionConfig(Builder b) {
        this.serverHost      = b.serverHost;
        this.serverPort      = b.serverPort;
        this.roomId          = b.roomId;
        this.username        = b.username;
        this.mode            = b.mode;
        this.localFilePath   = b.localFilePath;
        this.jellyfinBaseUrl = b.jellyfinBaseUrl;
        this.jellyfinApiKey  = b.jellyfinApiKey;
        this.jellyfinItemId  = b.jellyfinItemId;
        this.jellyfinUserId  = b.jellyfinUserId;
    }

    public String buildJellyfinStreamUrl() {
        if (jellyfinBaseUrl == null || jellyfinItemId == null || jellyfinApiKey == null)
            return null;
        // Direct stream URL — MPV handles this natively
        return jellyfinBaseUrl.stripTrailing()
            + "/Videos/" + jellyfinItemId
            + "/stream?static=true&api_key=" + jellyfinApiKey;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String   serverHost      = "localhost";
        private int      serverPort      = 5555;
        private String   roomId          = "room1";
        private String   username        = "User";
        private SyncMode mode            = SyncMode.JELLYFIN;
        private String   localFilePath;
        private String   jellyfinBaseUrl;
        private String   jellyfinApiKey;
        private String   jellyfinItemId;
        private String   jellyfinUserId;

        public Builder serverHost(String v)      { serverHost = v;      return this; }
        public Builder serverPort(int v)         { serverPort = v;      return this; }
        public Builder roomId(String v)          { roomId = v;          return this; }
        public Builder username(String v)        { username = v;        return this; }
        public Builder mode(SyncMode v)          { mode = v;            return this; }
        public Builder localFilePath(String v)   { localFilePath = v;   return this; }
        public Builder jellyfinBaseUrl(String v) { jellyfinBaseUrl = v; return this; }
        public Builder jellyfinApiKey(String v)  { jellyfinApiKey = v;  return this; }
        public Builder jellyfinItemId(String v)  { jellyfinItemId = v;  return this; }
        public Builder jellyfinUserId(String v)  { jellyfinUserId = v;  return this; }

        public SessionConfig build() { return new SessionConfig(this); }
    }
}
