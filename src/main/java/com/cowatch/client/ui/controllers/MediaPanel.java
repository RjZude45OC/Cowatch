package com.cowatch.client.ui.controllers;

import com.cowatch.client.jellyfin.*;
import com.cowatch.client.seanime.SeanimeClient;
import com.cowatch.client.seanime.SeanimeEvents;
import com.cowatch.client.model.SyncMode;
import com.cowatch.client.sync.SyncEngine;
import com.cowatch.client.tracking.AniListClient;
import com.cowatch.client.tracking.AniZipClient;
import com.cowatch.client.ui.MainWindow;
import com.cowatch.client.ui.Motion;
import com.cowatch.client.util.AppPrefs;
import com.cowatch.client.util.Async;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.stream.*;
import java.util.Properties;

import org.json.JSONObject;

import com.dgtlrepublic.anitomyj.AnitomyJ;
import com.dgtlrepublic.anitomyj.Element;
import com.dgtlrepublic.anitomyj.Element.ElementCategory;

public class MediaPanel {

    private static final Logger log = Logger.getLogger(MediaPanel.class.getName());
    private static final String PREFS_FILE = "cowatch-media.properties";

    private static final Set<String> VIDEO_EXTS = Set.of(
            "mp4","mkv","avi","mov","flv","wmv","m4v","ts","webm","ogv"
    );

    /** Result of parsing a local filename via AnitomyJ. */
    private record ParsedInfo(String title, int episode) {}

    /**
     * An episode someone else in the room started, for this client to open too.
     *
     * @param position     their position, in the room's timeline, at receivedAtMs
     * @param paused       whether they were paused
     * @param receivedAtMs when the announcement arrived, by this machine's clock
     */
    public record PeerMedia(int anilistId, int episode, String title, String from,
                            double position, boolean paused, long receivedAtMs) {
        String key() { return anilistId + ":" + episode; }
    }

    /** How often to look for a Seanime player to attach to, while there is none. */
    private static final long SEANIME_WATCH_MS = 500;

    /** How many folders next to the last local file to search when following. */
    private static final int MAX_LOCAL_DIRS = 200;

    private final MainWindow  owner;
    private final AniZipClient aniZip = new AniZipClient();

    private final VBox      node        = new VBox(12);
    private final VBox      card        = new VBox(10);
    private final StackPane contentArea = new StackPane();

    // ── Jellyfin widgets ──────────────────────────────────────────────────
    private final TextField     jellyfinUrlField  = field("https://jellyfin.example.com");
    private final TextField     jellyfinUserField = field("username");
    private final PasswordField jellyfinPassField = new PasswordField();
    private final TextField     jellyfinApiField  = field("API key (optional — skips login)");
    private final Button        jellyfinConnBtn   = new Button("Connect to Jellyfin");
    private final Button        jellyfinSaveBtn   = new Button("Save");
    private final Label         jellyfinStatus    = new Label("Not connected");

    // Wraps the login form so it can be collapsed once connected, freeing up
    // vertical space for the shows/seasons/episodes lists below. Rebuilt
    // each time buildJellyfinPanel() runs, but the reference is kept as a
    // field so handleJellyfinConnect() can collapse it after a successful
    // connection regardless of which build pass created it.
    private TitledPane jellyfinLoginPane;

    // Search box that filters showsList by title as the user types.
    private final TextField showSearchField = field("Search shows & movies…");

    // Backing list for showsList — populated on connect, filtered live by
    // showSearchField. showsList itself is bound to filteredShowItems once,
    // in the constructor, so this works regardless of how many times
    // buildJellyfinPanel() re-creates the surrounding layout.
    private final ObservableList<JellyfinItem> allShowItems      = FXCollections.observableArrayList();
    private final FilteredList<JellyfinItem>   filteredShowItems = new FilteredList<>(allShowItems, i -> true);

    private final ListView<JellyfinItem> showsList    = new ListView<>();
    private final ListView<JellyfinItem> seasonsList  = new ListView<>();
    private final ListView<JellyfinItem> episodesList = new ListView<>();
    private final Button                 launchJfBtn  = new Button("▶  Launch in MPV");
    private final Button                 nextJfBtn    = new Button("▶▶  Next Episode");

    // ── Seanime widgets ─────────────────────────────────────────
    private final TextField     seanimeUrlField   = field("127.0.0.1:43211");
    private final PasswordField seanimeTokenField = new PasswordField();
    private final Button        seanimeConnBtn    = new Button("Connect to Seanime");
    private final Button        seanimeSaveBtn    = new Button("Save");
    private final Button        attachBtn         = new Button("Attach to Seanime playback");
    private final Label         seanimeStatus     = new Label("Not connected");
    private final Label         nowPlayingLabel   = new Label("");
    private TitledPane          seanimeConnPane;

    private SeanimeClient seanimeClient;
    private SeanimeEvents seanimeEvents;

    /**
     * SHA-256 of the Seanime server password, which is what Seanime checks.
     * Kept instead of the password so the settings file never holds it.
     */
    private String seanimeTokenHash = "";

    /** Normalised file path to library entry, for resolving what mpv reports. */
    private Map<String, SeanimeClient.LocalFile> seanimeLibrary = Map.of();

    /** Stops a run of library misses queuing one full re-index each. */
    private final AtomicBoolean seanimeReindexing = new AtomicBoolean(false);

    private boolean seanimeAttached = false;

    /** "anilistId:episode" last sent to the room, so each episode is announced once. */
    private String lastAnnouncedKey = "";

    /**
     * "anilistId:episode" of what this client is playing, when known. An
     * announcement for the episode already on screen is not followed, which is
     * also what stops two clients bouncing the same episode back and forth.
     */
    private String playingKey = "";

    /**
     * True while the player was opened to follow someone else. That launch is
     * not announced: this side's library may resolve the episode to a slightly
     * different AniList entry, and announcing that would send the room after it.
     */
    private boolean followLaunch = false;

    /** What this client last announced, or would have if connected, for re-sending on join. */
    private record Announced(int anilistId, int episode, String title, String source) {}
    private Announced current;

    /** For AniList id lookups when no tracking token is set; search is public. */
    private final AniListClient publicAniList = new AniListClient("");

    // Automatic Seanime attach
    private boolean seanimeAttaching = false;
    private List<String> lastTriedSockets   = List.of();
    private List<String> lastFailedSockets  = List.of();
    private final ScheduledExecutorService seanimeWatcher =
            Executors.newSingleThreadScheduledExecutor(Async.namedDaemonFactory("seanime-watch"));
    private ScheduledFuture<?> seanimeWatchTask;

    // ── Local file widgets ────────────────────────────────────────────────
    private final TextField localFileField    = field("No file selected");
    private final Button    browseBtn         = new Button("Browse…");
    private final Button    launchLocalBtn    = new Button("▶  Launch in MPV");
    private final Button    nextLocalBtn      = new Button("▶▶  Next Episode");
    private final Label     playlistLabel     = new Label();

    // ── State ─────────────────────────────────────────────────────────────
    private JellyfinClient jellyfinClient;
    private SyncEngine      syncEngine; // set via setSyncEngine() once constructed by the owner
    private SyncMode       currentMode        = SyncMode.JELLYFIN;
    private String         savedLocalFilePath = "";

    // Playlist state
    private List<String>       playlistEntries    = new ArrayList<>();
    private List<JellyfinItem> jfEpisodeList      = new ArrayList<>(); // mirrors playlist for Jellyfin
    private int                currentIndex       = 0;
    private String             currentLocalFile   = ""; // updated as playlist advances

    // Guards against the 80%-watched threshold firing more than once for the
    // same episode (SyncEngine's own internal latch should already prevent
    // this, but this is a second, panel-local safety net).
    // Reset every time a new episode starts playing.
    private final AtomicBoolean reportedThisEpisode = new AtomicBoolean(false);

    // Jellyfin progress reporting
    private final ScheduledExecutorService progressScheduler =
            Executors.newSingleThreadScheduledExecutor(
                    Async.namedDaemonFactory("jellyfin-progress"));
    private ScheduledFuture<?> progressTask;

    public MediaPanel(MainWindow owner) {
        this.owner = owner;
        loadPrefs();

        // One-time wiring: bind the list to the filtered view and hook up
        // the search field's listener. Doing this here (rather than inside
        // buildJellyfinPanel(), which can run multiple times as the user
        // switches modes) avoids stacking duplicate listeners.
        showsList.setItems(filteredShowItems);
        showSearchField.textProperty().addListener((obs, oldV, newV) -> applyShowFilter(newV));

        // Same reason: a selection listener added inside a builder would stack
        // one copy per rebuild.
        episodesList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, now) -> launchJfBtn.setDisable(now == null));

        // The now-playing strip takes no space until there is something in it.
        nowPlayingLabel.visibleProperty().bind(nowPlayingLabel.textProperty().isNotEmpty());
        nowPlayingLabel.managedProperty().bind(nowPlayingLabel.visibleProperty());

        nextJfBtn.setDisable(true);
        nextLocalBtn.setDisable(true);
        for (Button b : new Button[] { nextJfBtn, nextLocalBtn }) {
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
        }

        build();
    }

    /**
     * Wires this panel to the SyncEngine driving mpv, so the 80%-watched
     * threshold can trigger AniList updates without waiting for mpv to close,
     * and so currentIndex stays correct when mpv auto-advances its own
     * internal playlist (e.g. an entire season loaded as one --playlist).
     * Call once, after both this panel and the SyncEngine have been constructed.
     */
    public void setSyncEngine(SyncEngine engine) {
        this.syncEngine = engine;
        if (this.syncEngine != null) {
            this.syncEngine.setWatchedThresholdListener(this::onWatchedThresholdReached);
            this.syncEngine.setPlaylistPositionListener(this::onPlaylistPositionChanged);
        }
    }

    private void build() {
        node.setFillWidth(true);
        VBox.setVgrow(node, Priority.ALWAYS);

        // No "Media Source" heading: this panel is the whole main area, so a
        // label naming it only repeats what the window already shows.
        card.getStyleClass().add("card");
        card.setFillWidth(true);
        VBox.setVgrow(card,        Priority.ALWAYS);
        VBox.setVgrow(contentArea, Priority.ALWAYS);

        card.getChildren().add(contentArea);
        node.getChildren().add(card);
        showJellyfinPanel();
    }

    // ── Mode switching ────────────────────────────────────────────────────

    public void setMode(SyncMode mode) {
        if (mode == currentMode) return;
        currentMode = mode;
        switch (mode) {
            case LOCAL    -> showLocalPanel();
            case SEANIME  -> showSeanimePanel();
            case JELLYFIN -> showJellyfinPanel();
        }
    }

    public void previewMode(SyncMode mode) { setMode(mode); }

    private void showJellyfinPanel() { contentArea.getChildren().setAll(buildJellyfinPanel()); }
    private void showSeanimePanel()  { contentArea.getChildren().setAll(buildSeanimePanel()); }
    private void showLocalPanel()    { contentArea.getChildren().setAll(buildLocalPanel()); }

    // ── Show search filter ──────────────────────────────────────────────

    /**
     * Filters filteredShowItems (and therefore showsList) by title, matched
     * case-insensitively against JellyfinItem.name(). Empty/blank query
     * shows everything.
     */
    private void applyShowFilter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        filteredShowItems.setPredicate(item -> {
            if (q.isEmpty()) return true;
            String name = item.name();
            return name != null && name.toLowerCase().contains(q);
        });
    }

    // ── Filename parsing (AnitomyJ) ────────────────────────────────────────

    /**
     * Parses a local media filename into series title + episode number using
     * AnitomyJ. Replaces the old hand-rolled regex parser, which only handled
     * single-bracket anime release names and broke on both multi-bracket
     * names (e.g. "[Group][Title][07][1080p][TAG].mp4" — title itself is a
     * bracket group) and scene-style dot names (e.g. "Show.S01E08.Tag.mkv").
     *
     * Falls back to (currentIndex + 1) for episode if AnitomyJ can't find one,
     * matching old behavior. Title returned empty if not found — caller must
     * check before using it as a search query.
     */
    private ParsedInfo parseFilename(String filePath) {
        String name = new File(filePath).getName();
        List<Element> elements = AnitomyJ.parse(name);

        String title = elements.stream()
                .filter(e -> e.getCategory() == ElementCategory.kElementAnimeTitle)
                .map(Element::getValue)
                .findFirst()
                .orElse("");

        int ep = elements.stream()
                .filter(e -> e.getCategory() == ElementCategory.kElementEpisodeNumber)
                .map(Element::getValue)
                .findFirst()
                .map(v -> {
                    try { return Integer.parseInt(v); }
                    catch (NumberFormatException ex) { return -1; }
                })
                .orElse(-1);

        if (ep < 0) {
            log.warning("Anitomy: no episode parsed from " + name + " — using index " + (currentIndex + 1));
            ep = currentIndex + 1;
        }
        if (title.isBlank()) {
            log.warning("Anitomy: no title parsed from " + name);
        }

        return new ParsedInfo(title.trim(), ep);
    }

    // ── Playlist core logic ───────────────────────────────────────────────

    private List<String> buildLocalPlaylist(File selectedFile) {
        File dir = selectedFile.getParentFile();
        if (dir == null || !dir.isDirectory()) return List.of(selectedFile.getAbsolutePath());
        File[] siblings = dir.listFiles(f -> f.isFile() && isVideoFile(f.getName()));
        if (siblings == null || siblings.length == 0) return List.of(selectedFile.getAbsolutePath());
        return Arrays.stream(siblings)
                .sorted(Comparator.comparing(f -> f.getName().toLowerCase()))
                .map(File::getAbsolutePath)
                .collect(Collectors.toList());
    }

    private int findIndex(List<String> entries, String target) {
        for (int i = 0; i < entries.size(); i++)
            if (entries.get(i).equals(target)) return i;
        return 0;
    }

    private boolean isVideoFile(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && VIDEO_EXTS.contains(name.substring(dot + 1).toLowerCase());
    }

    private void launchPlaylist(List<String> entries, int startIndex, boolean isJellyfin) {
        if (entries.isEmpty()) return;
        playlistEntries = new ArrayList<>(entries);
        currentIndex    = Math.max(0, Math.min(startIndex, entries.size() - 1));
        if (!isJellyfin && !entries.isEmpty())
            currentLocalFile = entries.get(currentIndex);

        // New episode is about to play — reset both the 80%-watched latch
        // (SyncEngine) and our own "already reported" guard.
        resetWatchedTrackingForNewEpisode();

        updatePlaylistLabels(isJellyfin);

        if (isJellyfin) { launchJfBtn.setDisable(true); launchJfBtn.setText("Launching…"); }
        else             { launchLocalBtn.setDisable(true); launchLocalBtn.setText("Launching…"); }

        if (entries.size() == 1) owner.onLaunch(entries.get(0));
        else                     owner.onLaunchPlaylist(entries, startIndex);
    }

    private void updatePlaylistLabels(boolean isJellyfin) {
        int total = playlistEntries.size();
        playlistLabel.setText(total > 1 ? "Episode " + (currentIndex + 1) + " of " + total : "");
        boolean hasNext = currentIndex < total - 1;
        nextLocalBtn.setDisable(!hasNext);
        nextJfBtn.setDisable(!hasNext);
        publishNowPlaying(isJellyfin);
    }

    /**
     * Names the current playlist entry on the transport bar. Jellyfin knows the
     * series and episode outright; local files go through the same filename
     * parser the AniList reporting uses.
     */
    private void publishNowPlaying(boolean isJellyfin) {
        if (isJellyfin) {
            if (currentIndex >= jfEpisodeList.size()) return;
            JellyfinItem item = jfEpisodeList.get(currentIndex);
            String show = item.seriesName().isBlank() ? item.name() : item.seriesName();
            String ep = item.episodeNumber() <= 0 ? item.name()
                    : (item.seasonNumber() > 1 ? "Season " + item.seasonNumber() + " · " : "")
                      + "Episode " + item.episodeNumber();
            owner.setNowPlaying(show, ep);
        } else if (currentIndex < playlistEntries.size()) {
            String path = playlistEntries.get(currentIndex);
            ParsedInfo info = parseFilename(path);
            String show = info.title().isBlank() ? new File(path).getName() : info.title();
            owner.setNowPlaying(show, "Episode " + info.episode());
        }
    }

    private void handleNextEpisode(boolean isJellyfin) {
        if (currentIndex >= playlistEntries.size() - 1) return;

        // Report stop for current Jellyfin episode before advancing
        if (isJellyfin && !jfEpisodeList.isEmpty()
                && currentIndex < jfEpisodeList.size()
                && jellyfinClient != null) {
            final String itemId = jfEpisodeList.get(currentIndex).id();
            Async.run("jellyfin-next-stop", () -> jellyfinClient.reportPlaybackStopped(itemId, 0, 0));
        }

        currentIndex++;

        if (!isJellyfin && currentIndex < playlistEntries.size())
            currentLocalFile = playlistEntries.get(currentIndex);

        // New episode — reset watched tracking again.
        resetWatchedTrackingForNewEpisode();

        updatePlaylistLabels(isJellyfin);
        announceCurrent(isJellyfin);

        if (isJellyfin) { launchJfBtn.setDisable(true); launchJfBtn.setText("Launching…"); }
        else             { launchLocalBtn.setDisable(true); launchLocalBtn.setText("Launching…"); }

        owner.onNextEpisode();
    }

    /**
     * Called by SyncEngine when mpv's own playlist-pos changes — covers mpv
     * auto-advancing through an entire season loaded as one --playlist, which
     * never goes through handleNextEpisode()/owner.onNextEpisode() at all.
     * Without this, currentIndex (and therefore which episode gets reported
     * to AniList/Jellyfin) silently goes stale after the first auto-advance.
     *
     * NOTE: SyncEngine already calls resetWatchedTracking() itself right after
     * notifying this listener, so this method only needs to handle the
     * panel-local state (currentIndex, currentLocalFile, the reportedThisEpisode
     * guard, Jellyfin stop-reporting, UI labels) — not call syncEngine.resetWatchedTracking() again.
     */
    private void onPlaylistPositionChanged(int newIndex) {
        if (newIndex < 0 || newIndex >= playlistEntries.size()) return;
        if (newIndex == currentIndex) return; // already in sync, nothing to do

        boolean isJellyfin = currentMode == SyncMode.JELLYFIN;

        // Report stop for the episode mpv just left, same as handleNextEpisode() does.
        if (isJellyfin && !jfEpisodeList.isEmpty()
                && currentIndex < jfEpisodeList.size()
                && jellyfinClient != null) {
            final String itemId = jfEpisodeList.get(currentIndex).id();
            Async.run("jellyfin-auto-advance-stop", () -> jellyfinClient.reportPlaybackStopped(itemId, 0, 0));
        }

        log.info("mpv auto-advanced playlist: index " + currentIndex + " → " + newIndex);
        currentIndex = newIndex;

        if (!isJellyfin && currentIndex < playlistEntries.size())
            currentLocalFile = playlistEntries.get(currentIndex);

        reportedThisEpisode.set(false); // syncEngine already reset its own latch for this transition

        Platform.runLater(() -> {
            updatePlaylistLabels(isJellyfin);
            announceCurrent(isJellyfin);
        });
    }

    private void resetWatchedTrackingForNewEpisode() {
        reportedThisEpisode.set(false);
        if (syncEngine != null) syncEngine.resetWatchedTracking();
    }

    // ── Jellyfin panel ────────────────────────────────────────────────────

    /**
     * Jellyfin mode: a collapsible connection form over a three-pane browser.
     *
     * <p>The browser is side-by-side rather than the three stacked lists it used
     * to be. Stacked, the panel needed about 600px of height before any list
     * showed more than four rows; in columns it is usable at half that, which is
     * most of why the window can open smaller.
     */
    private VBox buildJellyfinPanel() {
        VBox browser = buildJellyfinBrowser();
        VBox panel = new VBox(8, buildJellyfinLoginPane(), browser);
        panel.setFillWidth(true);
        VBox.setVgrow(browser, Priority.ALWAYS);
        return panel;
    }

    /**
     * The connection form, collapsed by default once credentials are on file.
     *
     * <p>Username/password and API key are two ways to do the same thing, so
     * only one is on screen: the key field hides behind a disclosure link unless
     * a key is already stored.
     */
    private TitledPane buildJellyfinLoginPane() {
        jellyfinPassField.setPromptText("Password");
        jellyfinPassField.setMaxWidth(Double.MAX_VALUE);

        addOnce(jellyfinStatus, "subhead");
        addOnce(jellyfinStatus, "jellyfin-status-pending");
        jellyfinStatus.setWrapText(true);

        addOnce(jellyfinConnBtn, "accent");
        jellyfinConnBtn.setMaxWidth(Double.MAX_VALUE);
        jellyfinConnBtn.setOnAction(e -> handleJellyfinConnect());
        HBox.setHgrow(jellyfinConnBtn, Priority.ALWAYS);

        addOnce(jellyfinSaveBtn, "secondary");
        jellyfinSaveBtn.setOnAction(e -> savePrefs(jellyfinSaveBtn));

        VBox apiKeyRow = rowLabel("API key", jellyfinApiField);
        boolean hasKey = !jellyfinApiField.getText().trim().isEmpty();
        setShown(apiKeyRow, hasKey);

        Hyperlink apiKeyToggle = new Hyperlink(hasKey ? "Hide API key" : "Use an API key instead");
        apiKeyToggle.getStyleClass().add("subhead");
        apiKeyToggle.setOnAction(e -> {
            boolean show = !apiKeyRow.isManaged();
            setShown(apiKeyRow, show);
            apiKeyToggle.setText(show ? "Hide API key" : "Use an API key instead");
        });

        VBox loginBox = new VBox(8,
                rowLabel("Server URL", jellyfinUrlField),
                rowLabel("Username",   jellyfinUserField),
                rowLabel("Password",   jellyfinPassField),
                apiKeyToggle,
                apiKeyRow,
                new HBox(8, jellyfinConnBtn, jellyfinSaveBtn),
                jellyfinStatus);
        loginBox.getStyleClass().add("jellyfin-login");

        jellyfinLoginPane = new TitledPane("Jellyfin server", loginBox);
        jellyfinLoginPane.setAnimated(true);
        jellyfinLoginPane.getStyleClass().add("jellyfin-login-pane");
        // Expanded only when there is nothing to connect with yet. A stored
        // token reconnects on startup, so the form would just be in the way.
        jellyfinLoginPane.setExpanded(jellyfinClient == null && !hasKey);
        return jellyfinLoginPane;
    }

    private VBox buildJellyfinBrowser() {
        addOnce(showSearchField, "search-field");
        showSearchField.setPromptText("Search shows and movies");

        showsList.setPlaceholder(new Label("Connect to browse"));
        seasonsList.setPlaceholder(new Label("Pick a show"));
        episodesList.setPlaceholder(new Label("Pick a season"));

        showsList.setOnMouseClicked(e -> {
            JellyfinItem sel = showsList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            // Movies have no Season/Episode hierarchy in Jellyfin - asking for
            // seasons on a movie's Id returns nothing, which is why movies
            // could never reach the episode list before.
            if ("Movie".equals(sel.type())) selectMovie(sel);
            else                            loadSeasons(sel.id());
        });

        seasonsList.setOnMouseClicked(e -> {
            JellyfinItem sel = seasonsList.getSelectionModel().getSelectedItem();
            if (sel != null) loadEpisodes(sel.id());
        });

        addOnce(launchJfBtn, "green");
        launchJfBtn.setMaxWidth(Double.MAX_VALUE);
        launchJfBtn.setDisable(episodesList.getSelectionModel().getSelectedItem() == null);
        launchJfBtn.setOnAction(e -> handleJellyfinLaunch());
        HBox.setHgrow(launchJfBtn, Priority.ALWAYS);

        nextJfBtn.setOnAction(e -> handleNextEpisode(true));

        HBox panes = new HBox(8,
                listColumn("Shows & movies", showsList),
                listColumn("Seasons",        seasonsList),
                listColumn("Episodes",       episodesList));
        panes.setFillHeight(true);
        VBox.setVgrow(panes, Priority.ALWAYS);

        VBox box = new VBox(8, showSearchField, panes, new HBox(8, launchJfBtn, nextJfBtn));
        box.setFillWidth(true);
        return box;
    }

    /** One pane of the three-pane browser: a small caption over its list. */
    private VBox listColumn(String title, ListView<JellyfinItem> list) {
        list.setMinHeight(110);
        list.setPrefHeight(240);
        VBox col = new VBox(4, sectionLabel(title), list);
        col.setFillWidth(true);
        col.setMinWidth(104);
        VBox.setVgrow(list, Priority.ALWAYS);
        HBox.setHgrow(col, Priority.ALWAYS);
        return col;
    }

    // ── Local file panel ──────────────────────────────────────────────────

    private VBox buildLocalPanel() {
        browseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select Media File");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Video Files",
                            "*.mp4","*.mkv","*.avi","*.mov","*.flv","*.wmv","*.m4v","*.ts","*.webm"),
                    new FileChooser.ExtensionFilter("All Files", "*.*"));
            File file = fc.showOpenDialog(node.getScene() != null ? node.getScene().getWindow() : null);
            if (file != null) {
                savedLocalFilePath = file.getAbsolutePath();
                localFileField.setText(savedLocalFilePath);
                launchLocalBtn.setDisable(false);
                List<String> pl = buildLocalPlaylist(file);
                int idx = findIndex(pl, file.getAbsolutePath());
                ParsedInfo info = parseFilename(file.getAbsolutePath());
                int ep = info.episode();
                playlistLabel.setText(pl.size() > 1
                        ? "Found " + pl.size() + " files — ep " + ep + " selected"
                        : "Single file — ep " + ep);
            }
        });

        localFileField.setEditable(false);
        localFileField.setMaxWidth(Double.MAX_VALUE);
        localFileField.setPromptText("Click Browse to pick a file…");

        addOnce(launchLocalBtn, "green");
        launchLocalBtn.setMaxWidth(Double.MAX_VALUE);
        launchLocalBtn.setOnAction(e -> handleLocalLaunch());
        HBox.setHgrow(launchLocalBtn, Priority.ALWAYS);

        nextLocalBtn.setOnAction(e -> handleNextEpisode(false));

        playlistLabel.getStyleClass().add("subhead");
        playlistLabel.setWrapText(true);

        if (!savedLocalFilePath.isEmpty()) {
            localFileField.setText(savedLocalFilePath);
            launchLocalBtn.setDisable(false);
        } else {
            launchLocalBtn.setDisable(true);
        }

        HBox fileRow = new HBox(8, localFileField, browseBtn);
        HBox.setHgrow(localFileField, Priority.ALWAYS);
        fileRow.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("All video files in the folder load as a playlist.\n"
                + "Title and episode number are parsed from the filename automatically.");
        hint.setWrapText(true);
        hint.getStyleClass().add("subhead");

        VBox panel = new VBox(10,
                sectionLabel("Local media file"),
                fileRow, playlistLabel, hint,
                new HBox(8, launchLocalBtn, nextLocalBtn));
        panel.setFillWidth(true);
        panel.setAlignment(Pos.TOP_LEFT);
        return panel;
    }

    // ── Handlers ─────────────────────────────────────────────────────────

    private void handleLocalLaunch() {
        String path = localFileField.getText().trim();
        if (path.isEmpty() || path.equals("No file selected")) {
            owner.showError("No File Selected", "Please select a file first.");
            return;
        }
        File file = new File(path);
        if (!file.exists()) {
            owner.showError("File Not Found", "File not found:\n" + path);
            return;
        }
        List<String> playlist = buildLocalPlaylist(file);
        int startIndex        = findIndex(playlist, file.getAbsolutePath());
        currentLocalFile      = file.getAbsolutePath();
        followLaunch          = false;
        launchPlaylist(playlist, startIndex, false);
        announceLocalFile(currentLocalFile);
    }

    public void onMpvLaunched(boolean success) {
        if (success) {
            launchLocalBtn.setText("MPV Running ✓");
            launchJfBtn.setText("MPV Running ✓");
            boolean hasNext = currentIndex < playlistEntries.size() - 1;
            nextLocalBtn.setDisable(!hasNext);
            nextJfBtn.setDisable(!hasNext);
        } else {
            launchLocalBtn.setDisable(false);
            launchLocalBtn.setText("▶  Launch in MPV");
            launchJfBtn.setDisable(episodesList.getSelectionModel().getSelectedItem() == null);
            launchJfBtn.setText("▶  Launch in MPV");
        }
    }

    public void onMpvClosed(boolean success) {
        if (progressTask != null) { progressTask.cancel(false); progressTask = null; }
        playingKey       = "";
        lastAnnouncedKey = "";
        followLaunch     = false;
        current          = null;

        launchLocalBtn.setText("▶  Launch in MPV"); launchLocalBtn.setDisable(false);
        launchJfBtn.setText("▶  Launch in MPV");
        launchJfBtn.setDisable(episodesList.getSelectionModel().getSelectedItem() == null);
        nextLocalBtn.setDisable(true);
        nextJfBtn.setDisable(true);

        // Report Jellyfin playback-stopped — this is just Jellyfin's own
        // "resume from here" bookkeeping, unrelated to the AniList update.
        if (currentMode == SyncMode.JELLYFIN
                && !jfEpisodeList.isEmpty()
                && currentIndex < jfEpisodeList.size()
                && jellyfinClient != null) {
            final String itemId = jfEpisodeList.get(currentIndex).id();
            Async.run("jellyfin-session-stop", () -> jellyfinClient.reportPlaybackStopped(itemId, 0, 0));
        }

        // NOTE: AniList progress is reported solely by the 80%-watched threshold
        // (see onWatchedThresholdReached()). Closing mpv before reaching 80% will
        // NOT report progress to AniList — this is intentional: stopping early
        // means the episode wasn't actually watched, so no update should be sent.
    }


    /**
     * Called by SyncEngine once playback crosses 80% of the current file's
     * duration. This is the sole trigger for AniList progress updates — closing
     * mpv no longer reports anything (see onMpvClosed()). Guarded against firing
     * twice for the same episode via reportedThisEpisode.
     */
    private void onWatchedThresholdReached() {
        if (!reportedThisEpisode.compareAndSet(false, true)) return; // already reported

        Platform.runLater(() -> {
            switch (currentMode) {
                case JELLYFIN -> reportJellyfinWatched();
                case LOCAL    -> reportLocalWatched();
                // Seanime started the episode and is already tracking it. A second
                // writer on the same AniList entry would race the first, and
                // Seanime's episode mapping is better than the AniZip fallback
                // here, so cowatch deliberately reports nothing in this mode.
                case SEANIME  -> log.info("Seanime mode: leaving the AniList update to Seanime");
            }
        });
    }

    // ── AniList reporting (Jellyfin + local) ───────────────────────────────

    /**
     * Resolves the current Jellyfin episode to an AniList media ID + relative
     * episode number via AniZip, then sends the progress update.
     * Strategy:
     *   1. Search AniList by series name + year → candidate media ID
     *   2. Use AniZip to convert Jellyfin's TVDB season+ep → AniList relative ep
     *   3. If AniZip lookup fails, fall back to Jellyfin's own episode number
     */
    private void reportJellyfinWatched() {
        if (jfEpisodeList.isEmpty() || currentIndex >= jfEpisodeList.size()) return;
        final JellyfinItem item = jfEpisodeList.get(currentIndex);

        Async.run("jellyfin-anilist-report", () -> {
            AniListClient aniList = owner.getTrackingPanel().getAniListClient();
            if (aniList == null || !aniList.hasToken()) return;

            String searchTitle = item.seriesName().isBlank() ? item.name() : item.seriesName();
            int candidateId = aniList.searchMediaId(searchTitle, item.year());

            if (candidateId <= 0) {
                log.warning("AniList Jellyfin: no media found for '" + searchTitle + "' year=" + item.year());
                return;
            }

            int tvdbSeason  = item.seasonNumber();  // ParentIndexNumber from Jellyfin
            int tvdbEpisode = item.episodeNumber(); // IndexNumber from Jellyfin
            int relativeEp  = aniZip.resolveJellyfinEpisode(candidateId, tvdbSeason, tvdbEpisode);

            if (relativeEp <= 0) {
                // Do NOT fall back to the raw TVDB episode number here — TVDB
                // and AniList often use different numbering schemes (TVDB may
                // run absolute-within-season across multiple AniList entries),
                // so a raw passthrough is frequently wrong, not just imprecise.
                // Sending a wrong progress value is worse than sending none —
                // skip this update and let the user fix it manually on AniList
                // if it matters, rather than silently corrupting their progress.
                log.warning("AniZip: could not resolve TVDB S" + tvdbSeason + "E" + tvdbEpisode
                        + " for anilistId=" + candidateId
                        + " — skipping AniList update (no safe fallback)");
                return;
            }

            aniList.updateProgress(candidateId, relativeEp);
            log.info("AniList Jellyfin: mediaId=" + candidateId
                    + " tvdbS" + tvdbSeason + "E" + tvdbEpisode
                    + " → AniList ep " + relativeEp);

        });
    }

    /**
     * Parses the currently-playing local file's title + episode number via
     * AnitomyJ, resolves it against AniList via search, normalizes the
     * episode number through AniZip (handles split-cour absolute numbering),
     * then sends the progress update.
     */
    private void reportLocalWatched() {
        if (currentLocalFile.isEmpty()) return;
        final String fileForThisRun = currentLocalFile; // snapshot — avoid races with playlist advancing

        AniListClient aniList = owner.getTrackingPanel().getAniListClient();
        if (aniList == null || !aniList.hasToken()) return;

        Async.run("local-anilist-report", () -> {
            ParsedInfo info = parseFilename(fileForThisRun);
            String parsedTitle = info.title();
            int parsedEp = info.episode();

            if (parsedTitle.isEmpty()) {
                log.warning("AniList local: could not parse a title from " + fileForThisRun);
                return;
            }

            int mediaId = aniList.searchMediaId(parsedTitle);
            if (mediaId <= 0) {
                log.warning("AniList local: no AniList match for '" + parsedTitle + "'");
                return;
            }

            int finalEp = aniZip.resolveAbsoluteEpisode(mediaId, parsedEp, 0);

            log.info("AniList local: '" + parsedTitle + "' mediaId=" + mediaId
                    + " parsedEp=" + parsedEp + " finalEp=" + finalEp
                    + " file=" + fileForThisRun);

            aniList.updateProgress(mediaId, finalEp);
        });
    }

    // ── Jellyfin connect / launch ─────────────────────────────────────────

    private void handleJellyfinConnect() {
        String url  = jellyfinUrlField.getText().trim();
        String user = jellyfinUserField.getText().trim();
        String pass = jellyfinPassField.getText();
        String key  = jellyfinApiField.getText().trim();

        jellyfinConnBtn.setDisable(true);
        setJellyfinStatus("Connecting...", "pending");

        Async.run("jellyfin-auth", () -> {
            jellyfinClient = new JellyfinClient(url);
            boolean ok;
            if (!key.isBlank()) {
                jellyfinClient.setApiKey(key, "");
                // The library endpoints are user-scoped, so a token alone is
                // not enough — resolve which account it belongs to. This also
                // rejects a stale token, which ping() would happily accept.
                ok = jellyfinClient.resolveCurrentUser();
            } else {
                ok = jellyfinClient.authenticate(user, pass);
            }
            if (ok) jellyfinClient.registerCapabilities();

            if (ok) {
                List<JellyfinItem> shows  = jellyfinClient.getTvShows();
                List<JellyfinItem> movies = jellyfinClient.getMovies();
                Platform.runLater(() -> {
                    rememberAccessToken();
                    allShowItems.setAll(shows);
                    allShowItems.addAll(movies);
                    applyShowFilter(showSearchField.getText());
                    setJellyfinStatus("Connected — " + (shows.size() + movies.size()) + " items", "ok");
                    jellyfinConnBtn.setText("Connected");
                    // Free up vertical space now that we're connected —
                    // collapse the login form so the browser gets more room.
                    if (jellyfinLoginPane != null) jellyfinLoginPane.setExpanded(false);
                });
            } else {
                // Report what the server actually said. Jellyfin 12 answers 400
                // to a login it cannot attribute to a device, which is
                // indistinguishable from a bad password in a generic message.
                final String reason = jellyfinClient.getLastError();
                Platform.runLater(() -> {
                    setJellyfinStatus(reason == null ? "Connection failed" : reason, "error");
                    jellyfinConnBtn.setDisable(false);
                    jellyfinConnBtn.setText("Connect to Jellyfin");
                    if (jellyfinLoginPane != null) jellyfinLoginPane.setExpanded(true);
                });
            }
        });
    }

    private void setJellyfinStatus(String text, String state) {
        jellyfinStatus.setText(text);
        jellyfinStatus.getStyleClass()
                .removeAll("jellyfin-status-pending", "jellyfin-status-ok", "jellyfin-status-error");
        jellyfinStatus.getStyleClass().add("jellyfin-status-" + state);
    }

    /**
     * Movies skip the Season/Episode browsing step entirely — there's
     * nothing to drill into. Instead the movie is placed directly into
     * episodesList as its only entry and auto-selected, so it flows through
     * handleJellyfinLaunch()/jfEpisodeList exactly like a real episode would.
     */
    private void selectMovie(JellyfinItem movie) {
        seasonsList.getItems().clear();
        episodesList.getItems().setAll(movie);
        episodesList.getSelectionModel().select(0);
    }

    private void loadSeasons(String seriesId) {
        seasonsList.getItems().clear();
        episodesList.getItems().clear();
        Async.run("jellyfin-seasons", () -> {
            List<JellyfinItem> seasons = jellyfinClient.getSeasons(seriesId);
            Platform.runLater(() -> seasonsList.getItems().setAll(seasons));
        });
    }

    private void loadEpisodes(String seasonId) {
        episodesList.getItems().clear();
        Async.run("jellyfin-episodes", () -> {
            List<JellyfinItem> eps = jellyfinClient.getEpisodes(seasonId);
            Platform.runLater(() -> episodesList.getItems().setAll(eps));
        });
    }

    private void handleJellyfinLaunch() {
        JellyfinItem selected = episodesList.getSelectionModel().getSelectedItem();
        if (selected == null || jellyfinClient == null) return;
        followLaunch = false;
        startJellyfin(new ArrayList<>(episodesList.getItems()), selected);
    }

    /**
     * Plays {@code selected} with the rest of {@code episodes} queued behind it,
     * and reports the session to Jellyfin.
     */
    private void startJellyfin(List<JellyfinItem> episodes, JellyfinItem selected) {
        // Store full episode list — currentIndex tracks which ep is playing
        // as MPV advances through the playlist via playlist-next
        jfEpisodeList = new ArrayList<>(episodes);
        List<String> urls = jfEpisodeList.stream()
                .map(ep -> jellyfinClient.getStreamUrl(ep.id()))
                .collect(Collectors.toList());
        int startIndex = jfEpisodeList.indexOf(selected);

        launchPlaylist(urls, startIndex, true);
        announceJellyfinEpisode(selected);

        // Report playback start + start periodic progress ticks
        final JellyfinItem startItem = selected;
        Async.run("jellyfin-session-start", () -> {
            jellyfinClient.reportPlaybackStart(startItem.id(), 0, true);
            jellyfinClient.registerCapabilities();

            // A relaunch while the last session's ticker still runs would
            // otherwise leave two of them reporting.
            if (progressTask != null) progressTask.cancel(false);
            progressTask = progressScheduler.scheduleAtFixedRate(() -> {
                if (jellyfinClient != null
                        && !jfEpisodeList.isEmpty()
                        && currentIndex < jfEpisodeList.size()) {
                    jellyfinClient.reportPlaybackProgress(
                            jfEpisodeList.get(currentIndex).id(), 0, false);
                }
            }, 10, 10, TimeUnit.SECONDS);

        });
    }

    /**
     * Tells the room which episode this client just started from Jellyfin.
     *
     * <p>Resolving the AniList id costs a search, so this runs in the background
     * and stays silent on a miss: failing to announce only costs the other side
     * the convenience of being told what to open, while blocking playback on a
     * lookup would be much worse.
     */
    private void announceJellyfinEpisode(JellyfinItem item) {
        if (followLaunch) return;
        AniListClient aniList = aniListForLookup();

        Async.run("jellyfin-announce", () -> {
            String searchTitle = item.seriesName().isBlank() ? item.name() : item.seriesName();
            int anilistId = aniList.searchMediaId(searchTitle, item.year());
            if (anilistId <= 0) {
                log.info("Not announcing: no AniList match for '" + searchTitle + "'");
                return;
            }
            // A film is AniList episode 1. Jellyfin numbers it 0, which the room
            // would discard as "no episode" - so following a film did nothing.
            boolean movie = "Movie".equals(item.type());
            int relativeEp = movie ? 1 : aniZip.resolveJellyfinEpisode(
                    anilistId, item.seasonNumber(), item.episodeNumber());
            final int episode = relativeEp > 0 ? relativeEp : Math.max(1, item.episodeNumber());
            Platform.runLater(() -> announce(anilistId, episode, searchTitle, "jellyfin"));
        });
    }

    /**
     * Tells the room which local file this client just started.
     *
     * <p>Same idea as {@link #announceJellyfinEpisode}: the title and episode
     * come from the filename, the AniList id from a search, and AniZip folds
     * absolute numbering back into the season's own.
     */
    private void announceLocalFile(String path) {
        if (followLaunch || path == null || path.isBlank()) return;
        AniListClient aniList = aniListForLookup();
        Async.run("local-announce", () -> {
            ParsedInfo info = parseFilename(path);
            if (info.title().isBlank()) return;
            int anilistId = aniList.searchMediaId(info.title());
            if (anilistId <= 0) {
                log.info("Not announcing: no AniList match for '" + info.title() + "'");
                return;
            }
            int episode = aniZip.resolveAbsoluteEpisode(anilistId, info.episode(), 0);
            Platform.runLater(() -> announce(anilistId, episode, info.title(), "local"));
        });
    }

    /** Announces whatever the playlist has moved on to. */
    private void announceCurrent(boolean isJellyfin) {
        // An advance is this viewer's own doing, even in a followed playlist.
        followLaunch = false;
        if (isJellyfin) {
            if (currentIndex < jfEpisodeList.size()) announceJellyfinEpisode(jfEpisodeList.get(currentIndex));
        } else {
            announceLocalFile(currentLocalFile);
        }
    }

    /** Sends an announcement once per episode, and remembers it as what is playing. */
    private void announce(int anilistId, int episode, String title, String source) {
        String key = anilistId + ":" + episode;
        playingKey = key;
        current    = new Announced(anilistId, episode, title, source);
        if (key.equals(lastAnnouncedKey)) return;
        // Only counts as said once it reached a room; otherwise joining one
        // later re-sends it (see reannounce()).
        if (owner.announceMedia(anilistId, episode, title, source)) lastAnnouncedKey = key;
    }

    /**
     * Re-sends what is playing, for a room that has nothing on. Called by
     * MainWindow shortly after joining, with the current position.
     */
    public void reannounce() {
        Announced a = current;
        if (a == null || !(a.anilistId() + ":" + a.episode()).equals(playingKey)) {
            log.info("Nothing to announce: current=" + a + " playing=" + playingKey);
            return;
        }
        log.info("Announcing " + a + " to the room");
        if (owner.announceMedia(a.anilistId(), a.episode(), a.title(), a.source())) {
            lastAnnouncedKey = playingKey;
        }
    }

    /** The tracking client when there is one, else a token-less one for search. */
    private AniListClient aniListForLookup() {
        AniListClient tracking = owner.getTrackingPanel().getAniListClient();
        return tracking != null ? tracking : publicAniList;
    }

    // ── Preferences ───────────────────────────────────────────────────────

    /**
     * Writes the media settings.
     *
     * @param feedback the Save button that was pressed, to flash a confirmation
     *                 on; null for a save the user did not ask for
     */
    private void savePrefs(Button feedback) {
        Properties p = new Properties();
        p.setProperty("jellyfin.url",      jellyfinUrlField.getText().trim());
        p.setProperty("jellyfin.username", jellyfinUserField.getText().trim());
        p.setProperty("jellyfin.apikey",   jellyfinApiField.getText().trim());
        p.setProperty("seanime.url",       seanimeUrlField.getText().trim());
        p.setProperty("seanime.tokenhash", effectiveSeanimeTokenHash());
        // Passwords are deliberately absent: this file is plain text. Jellyfin
        // gets the access token it hands back after a login instead, and
        // Seanime the hash it compares against. See AppPrefs.isSecret().

        try {
            AppPrefs.save(PREFS_FILE, p, "cowatch media settings");
            if (feedback != null) Motion.confirm(feedback, "Saved ✓");
        } catch (IOException ex) {
            owner.showError("Save Failed", "Could not save settings:\n" + ex.getMessage());
        }
    }

    private void loadPrefs() {
        Properties p = AppPrefs.load(PREFS_FILE);
        jellyfinUrlField.setText( p.getProperty("jellyfin.url",      ""));
        jellyfinUserField.setText(p.getProperty("jellyfin.username", ""));
        jellyfinApiField.setText( p.getProperty("jellyfin.apikey",   ""));
        seanimeUrlField.setText(  p.getProperty("seanime.url",       seanimeUrlField.getText()));
        seanimeTokenHash = p.getProperty("seanime.tokenhash", "");

        // Older builds stored the Seanime password itself. Swap it for its hash
        // and drop it from disk.
        if (p.containsKey("seanime.token")) {
            String legacyPassword = p.getProperty("seanime.token", "");
            if (!legacyPassword.isEmpty()) seanimeTokenHash = SeanimeClient.hashPassword(legacyPassword);
            p.remove("seanime.token");
            p.setProperty("seanime.tokenhash", seanimeTokenHash);
            try {
                AppPrefs.save(PREFS_FILE, p, "cowatch media settings");
            } catch (IOException ex) {
                log.warning("Could not migrate the stored Seanime password: " + ex.getMessage());
            }
        }

        // Reconnect unattended only when a stored token can do it without a
        // password prompt. Anything else waits for the user to press Connect.
        if (!jellyfinUrlField.getText().trim().isEmpty()
                && !jellyfinApiField.getText().trim().isEmpty()) {
            handleJellyfinConnect();
        }
    }

    /**
     * Records the access token from a successful username/password login so
     * the next start can reconnect without the password being kept on disk.
     */
    private void rememberAccessToken() {
        if (jellyfinClient == null) return;
        String token = jellyfinClient.getApiKey();
        if (token == null || token.isBlank()) return;

        jellyfinApiField.setText(token);
        jellyfinPassField.clear();

        Properties p = AppPrefs.load(PREFS_FILE);
        p.setProperty("jellyfin.url",      jellyfinUrlField.getText().trim());
        p.setProperty("jellyfin.username", jellyfinUserField.getText().trim());
        p.setProperty("jellyfin.apikey",   token);
        try {
            AppPrefs.save(PREFS_FILE, p, "cowatch media settings");
        } catch (IOException ex) {
            log.warning("Could not store Jellyfin access token: " + ex.getMessage());
        }
    }

    // --- Seanime panel ----------------------------------------------------

    /**
     * Seanime mode: connect to the server, then attach to the player it opens.
     *
     * <p>Deliberately not a library browser. The point of this mode is that the
     * viewer picks and plays the episode in Seanime - that is where their
     * auto-downloader, their release-group rules and their episode matching
     * already live - and cowatch joins the mpv window it opens. Duplicating the
     * browser here would mean reimplementing all of that badly.
     */
    private VBox buildSeanimePanel() {
        addOnce(seanimeStatus, "subhead");
        addOnce(seanimeStatus, "jellyfin-status-pending");
        seanimeStatus.setWrapText(true);

        addOnce(seanimeConnBtn, "accent");
        seanimeConnBtn.setMaxWidth(Double.MAX_VALUE);
        seanimeConnBtn.setOnAction(e -> handleSeanimeConnect());
        HBox.setHgrow(seanimeConnBtn, Priority.ALWAYS);

        addOnce(seanimeSaveBtn, "secondary");
        seanimeSaveBtn.setOnAction(e -> savePrefs(seanimeSaveBtn));

        seanimeTokenField.setPromptText(seanimeTokenHash.isEmpty()
                ? "Password"
                : "Saved - leave blank to keep it");
        seanimeUrlField.setOnAction(e -> handleSeanimeConnect());
        seanimeTokenField.setOnAction(e -> handleSeanimeConnect());

        VBox tokenRow = rowLabel("Server password", seanimeTokenField);
        boolean hasToken = !seanimeTokenHash.isEmpty() || !seanimeTokenField.getText().isEmpty();
        setShown(tokenRow, hasToken);

        Hyperlink tokenToggle = new Hyperlink(hasToken ? "Hide password" : "Server has a password");
        tokenToggle.getStyleClass().add("subhead");
        tokenToggle.setOnAction(e -> {
            boolean show = !tokenRow.isManaged();
            setShown(tokenRow, show);
            tokenToggle.setText(show ? "Hide password" : "Server has a password");
        });

        VBox connBox = new VBox(8,
                rowLabel("Seanime address", seanimeUrlField),
                tokenToggle,
                tokenRow,
                new HBox(8, seanimeConnBtn, seanimeSaveBtn),
                seanimeStatus);
        connBox.getStyleClass().add("jellyfin-login");

        seanimeConnPane = new TitledPane("Seanime server", connBox);
        seanimeConnPane.setAnimated(true);
        seanimeConnPane.getStyleClass().add("jellyfin-login-pane");
        seanimeConnPane.setExpanded(seanimeClient == null);

        addOnce(attachBtn, "green");
        attachBtn.setMaxWidth(Double.MAX_VALUE);
        attachBtn.setDisable(seanimeClient == null || seanimeAttached);
        attachBtn.setOnAction(e -> handleSeanimeAttach());
        HBox.setHgrow(attachBtn, Priority.ALWAYS);

        nowPlayingLabel.setWrapText(true);
        addOnce(nowPlayingLabel, "now-playing");

        Label hint = new Label(
                "Play the episode in Seanime with mpv as its media player. cowatch joins "
              + "that mpv window as soon as it opens, so play, pause and seek stay in "
              + "sync whichever side presses them, and tells the room which episode it "
              + "is. The button is there if it ever misses one. Seanime keeps updating "
              + "AniList itself.");
        hint.setWrapText(true);
        hint.getStyleClass().add("subhead");

        VBox panel = new VBox(10,
                seanimeConnPane,
                sectionLabel("Playback"),
                attachBtn,
                nowPlayingLabel,
                hint);
        panel.setFillWidth(true);
        return panel;
    }

    // --- Seanime handlers -------------------------------------------------

    /** The token to connect with: a freshly typed password wins over the saved one. */
    private String effectiveSeanimeTokenHash() {
        String typed = seanimeTokenField.getText();
        return typed.isEmpty() ? seanimeTokenHash : SeanimeClient.hashPassword(typed);
    }

    private void handleSeanimeConnect() {
        if (seanimeConnBtn.isDisabled()) return;
        final String url  = seanimeUrlField.getText().trim();
        final String hash = effectiveSeanimeTokenHash();

        seanimeConnBtn.setDisable(true);
        seanimeConnBtn.setText("Connecting...");
        setSeanimeStatus("Connecting...", "pending");

        Async.run("seanime-connect", () -> {
            SeanimeClient client = new SeanimeClient(url, hash);
            String error = client.connect();
            Map<String, SeanimeClient.LocalFile> index =
                    (error == null) ? client.indexLocalFiles() : Map.of();

            Platform.runLater(() -> {
                seanimeConnBtn.setDisable(false);
                if (error != null) {
                    stopSeanimeEvents();
                    stopSeanimeWatch();
                    seanimeClient = null;
                    seanimeLibrary = Map.of();
                    attachBtn.setDisable(true);
                    seanimeConnBtn.setText("Connect to Seanime");
                    setSeanimeStatus(error, "error");
                    if (seanimeConnPane != null) seanimeConnPane.setExpanded(true);
                    return;
                }

                seanimeClient    = client;
                seanimeLibrary   = index;
                seanimeTokenHash = hash;
                seanimeTokenField.clear();
                seanimeTokenField.setPromptText(hash.isEmpty() ? "Password" : "Saved - leave blank to keep it");
                attachBtn.setDisable(seanimeAttached);
                seanimeConnBtn.setText("Reconnect");
                savePrefs(null);
                startSeanimeEvents(client);
                startSeanimeWatch();

                String who = client.getUserName().isEmpty() ? "" : " as " + client.getUserName();
                String ver = client.getVersion().isEmpty() ? "" : " (Seanime " + client.getVersion() + ")";
                String files = index.size() == 1 ? "1 file" : index.size() + " files";
                if (!client.usesExternalMpv()) {
                    // Worth saying up front rather than at Attach time: the fix is
                    // in Seanime's settings, not here.
                    setSeanimeStatus("Connected" + who + ver + ", but Seanime plays with "
                            + client.getDefaultPlayer() + ". cowatch can only join mpv: in "
                            + "Seanime, set Settings, Media player, Default player to mpv.", "error");
                    if (seanimeConnPane != null) seanimeConnPane.setExpanded(true);
                } else {
                    String socket = client.usesAutoSocket()
                            ? ""
                            : " on mpv socket " + client.getMpvSocket();
                    setSeanimeStatus("Connected" + who + ver + ". " + files
                            + " in the library. cowatch joins Seanime's mpv" + socket
                            + " as soon as it opens.", "ok");
                    if (seanimeConnPane != null) seanimeConnPane.setExpanded(false);
                }
            });
        });
    }

    /**
     * Follows Seanime's live playback events, which name the AniList entry and
     * episode directly. Works for streams as well as library files.
     */
    private void startSeanimeEvents(SeanimeClient client) {
        stopSeanimeEvents();
        seanimeEvents = new SeanimeEvents(client,
                playback -> Platform.runLater(() -> {
                    if (seanimeClient == client) onSeanimePlayback(playback);
                }),
                () -> Platform.runLater(() -> {
                    if (seanimeClient == client && !seanimeAttached) nowPlayingLabel.setText("");
                }));
        seanimeEvents.start();
    }

    private void stopSeanimeEvents() {
        if (seanimeEvents != null) {
            seanimeEvents.close();
            seanimeEvents = null;
        }
    }

    /**
     * Attaches cowatch to the mpv window Seanime opened.
     *
     * <p>No player is launched here. If nothing is playing in Seanime yet there is
     * no socket to connect to, and the error says so rather than starting a second,
     * unrelated mpv.
     */
    private void handleSeanimeAttach() {
        if (seanimeClient == null) return;
        final SeanimeClient client = seanimeClient;

        seanimeAttaching = true;
        lastTriedSockets = List.of();
        attachBtn.setDisable(true);
        attachBtn.setText("Attaching...");
        owner.onAttachToSeanime(client::mpvSocketCandidates);
    }

    /**
     * Attaches to Seanime's player without a click, as soon as one exists.
     *
     * <p>Seanime names each player's socket afresh, so this keeps looking until
     * one appears: on a timer, and straight away when Seanime's event stream
     * says playback started. A set of sockets that already failed is not tried
     * again until it changes, so a stale socket file does not mean a connection
     * attempt every couple of seconds.
     *
     * @param sockets the candidates to try, found off the FX thread
     * @param retryFailed true to try even a set that failed last time, for when
     *                    Seanime has just said a new player started
     */
    private void autoAttachSeanime(List<String> sockets, boolean retryFailed) {
        if (seanimeClient == null || currentMode != SyncMode.SEANIME) return;
        if (seanimeAttached || seanimeAttaching || !seanimeClient.usesExternalMpv()) return;
        if (sockets.isEmpty()) return;
        if (!retryFailed && sockets.equals(lastFailedSockets)) return;

        seanimeAttaching = true;
        lastTriedSockets = sockets;
        attachBtn.setDisable(true);
        attachBtn.setText("Attaching...");
        owner.onAttachToSeanime(() -> sockets, true);
    }

    /** Looks for Seanime's player in the background while connected. */
    private void startSeanimeWatch() {
        stopSeanimeWatch();
        final SeanimeClient client = seanimeClient;
        seanimeWatchTask = seanimeWatcher.scheduleWithFixedDelay(() -> {
            if (seanimeAttached || seanimeAttaching || currentMode != SyncMode.SEANIME) return;
            List<String> sockets = client.mpvSocketCandidates();
            if (sockets.isEmpty()) return;
            Platform.runLater(() -> {
                if (seanimeClient == client) autoAttachSeanime(sockets, false);
            });
        }, 0, SEANIME_WATCH_MS, TimeUnit.MILLISECONDS);
    }

    private void stopSeanimeWatch() {
        if (seanimeWatchTask != null) {
            seanimeWatchTask.cancel(false);
            seanimeWatchTask = null;
        }
    }

    /** Called by MainWindow once an attach attempt has finished, or the player closed. */
    public void onSeanimeAttached(boolean success) {
        boolean wasAttempt = seanimeAttaching;
        seanimeAttaching = false;
        seanimeAttached  = success;
        attachBtn.setText(success ? "Attached to Seanime's mpv" : "Attach to Seanime playback");
        attachBtn.setDisable(success || seanimeClient == null);
        if (success) {
            lastFailedSockets = List.of();
        } else {
            if (wasAttempt) lastFailedSockets = lastTriedSockets;
            nowPlayingLabel.setText("");
            lastAnnouncedKey = "";
            playingKey       = "";
        }
    }

    /**
     * Resolves the file mpv reports against Seanime's library and tells the room.
     *
     * <p>The fallback for when Seanime's live events are not available. Streams
     * report a URL here that no library lookup can resolve; those are left to
     * {@link #onSeanimePlayback}.
     *
     * <p>Called on the FX thread from MainWindow's SyncEngine listener.
     */
    public void onSeanimeMediaPath(String path) {
        if (seanimeClient == null || currentMode != SyncMode.SEANIME) return;

        SeanimeClient.LocalFile file = seanimeLibrary.get(SeanimeClient.normalizePath(path));
        if (file == null) {
            // A stream, a file outside the scanned library, or a library that
            // changed since connect. Re-index once in the background; Seanime's
            // own playback event will name the episode either way.
            if (!path.contains("://")) reindexSeanimeLibrary();
            return;
        }
        announceSeanime(file.anilistId(), file.episode(), "", new File(file.path()).getName());
    }

    /** Seanime said what it is playing. */
    private void onSeanimePlayback(SeanimeEvents.Playback playback) {
        if (currentMode != SyncMode.SEANIME) return;
        announceSeanime(playback.anilistId(), playback.episode(), playback.title(), playback.filename());

        // A player has just opened. Find its socket off the FX thread.
        if (!seanimeAttached && !seanimeAttaching && seanimeClient != null) {
            final SeanimeClient client = seanimeClient;
            Async.run("seanime-find-player", () -> {
                List<String> sockets = client.mpvSocketCandidates();
                Platform.runLater(() -> {
                    if (seanimeClient == client) autoAttachSeanime(sockets, true);
                });
            });
        }
    }

    /**
     * Shows the episode and tells the room, once per episode.
     *
     * <p>The library lookup and the live event usually both fire for the same
     * file; the room only needs to hear about it once.
     */
    private void announceSeanime(int anilistId, int episode, String title, String filename) {
        if (anilistId <= 0 || episode <= 0) return;
        String name = title.isBlank() ? filename : title;
        nowPlayingLabel.setText((name.isBlank() ? "AniList " + anilistId : name)
                + "  ·  Episode " + episode);
        owner.setNowPlaying(name.isBlank() ? "AniList " + anilistId : name, "Episode " + episode);

        if (seanimeAttached) {
            announce(anilistId, episode, name, "seanime");
        } else {
            // Not attached yet, so there is no position to send. Remember it;
            // MainWindow announces once attached (see reannounce()).
            playingKey = anilistId + ":" + episode;
            current    = new Announced(anilistId, episode, name, "seanime");
        }
    }

    /**
     * Refreshes the path index after a miss.
     *
     * <p>Guarded so a run of misses - every file in an unscanned folder, say -
     * cannot queue one full library fetch per event.
     */
    private void reindexSeanimeLibrary() {
        if (seanimeClient == null) return;
        if (!seanimeReindexing.compareAndSet(false, true)) return;

        final SeanimeClient client = seanimeClient;
        Async.run("seanime-reindex", () -> {
            Map<String, SeanimeClient.LocalFile> index = client.indexLocalFiles();
            Platform.runLater(() -> {
                seanimeReindexing.set(false);
                if (seanimeClient == client) seanimeLibrary = index;
            });
        });
    }

    private void setSeanimeStatus(String text, String state) {
        seanimeStatus.setText(text);
        seanimeStatus.getStyleClass()
                .removeAll("jellyfin-status-pending", "jellyfin-status-ok", "jellyfin-status-error");
        seanimeStatus.getStyleClass().add("jellyfin-status-" + state);
    }

    // --- Following the room -----------------------------------------------

    /**
     * Opens the episode someone else in the room started, from this viewer's
     * own source, and lines it up with where they are.
     *
     * <p>Each source resolves the AniList id its own way: Jellyfin by the TVDB
     * id or AniList id on its series, a local folder by the titles in the
     * filenames, Seanime by its own library. The position is applied once the
     * file has loaded (see {@link SyncEngine#joinAt}).
     *
     * <p>Called on the FX thread.
     */
    public void followPeer(PeerMedia peer) {
        String key = peer.key();
        log.info("Room media from " + peer.from() + ": " + peer + " mode=" + currentMode
                + " playing=" + playingKey + " open=" + owner.isPlayerOpen());
        if (key.equals(playingKey) && owner.isPlayerOpen()) {
            // Same episode already open - typically both started before
            // connecting. Line up with them rather than reopening it.
            if (syncEngine != null) syncEngine.joinNow(peer.position(), peer.receivedAtMs(), peer.paused());
            owner.systemMessage("Lined up with " + peer.from() + ", who is on the same episode");
            return;
        }
        String what = (peer.title().isBlank() ? "AniList " + peer.anilistId() : peer.title())
                + " episode " + peer.episode();
        owner.systemMessage("Opening " + what + ", which " + peer.from() + " started");

        playingKey = key;
        // Seanime will report this episode back once it opens it; the room
        // already knows.
        lastAnnouncedKey = key;
        if (syncEngine != null) syncEngine.joinAt(peer.position(), peer.receivedAtMs(), peer.paused());

        switch (currentMode) {
            case JELLYFIN -> followJellyfin(peer, what);
            case LOCAL    -> followLocal(peer, what);
            case SEANIME  -> followSeanime(peer, what);
        }
    }

    /** Gives up on a follow: says why, and drops the pending position. */
    private void followFailed(PeerMedia peer, String reason) {
        Platform.runLater(() -> {
            if (!peer.key().equals(playingKey)) return;   // superseded by a newer one
            if (syncEngine != null) syncEngine.cancelJoin();
            playingKey = "";
            lastAnnouncedKey = "";
            owner.systemMessage(reason);
        });
    }

    private void followJellyfin(PeerMedia peer, String what) {
        final JellyfinClient jf = jellyfinClient;
        if (jf == null) {
            followFailed(peer, "Couldn't open " + what + ": connect to Jellyfin first.");
            return;
        }
        Async.run("follow-jellyfin", () -> {
            ShowInfo info = lookUpShow(peer);

            if (info.movie()) {
                JellyfinItem movie = jf.findItem("Movie", peer.anilistId(),
                        Map.of("Tmdb", info.tmdbId(), "Imdb", info.imdbId()), info.titles());
                if (movie == null) {
                    followFailed(peer, "Couldn't find " + what + " in your Jellyfin movies.");
                    return;
                }
                Platform.runLater(() -> {
                    if (!peer.key().equals(playingKey) || jellyfinClient != jf) return;
                    followLaunch = true;
                    startJellyfin(List.of(movie), movie);
                });
                return;
            }

            JellyfinItem series = jf.findSeries(peer.anilistId(), info.tvdbId(), info.titles());
            if (series == null) {
                followFailed(peer, "Couldn't find " + what + " in your Jellyfin library.");
                return;
            }
            int season = info.tvdbSeason(), number = info.tvdbEpisode();
            List<JellyfinItem> all = jf.getSeriesEpisodes(series.id());
            JellyfinItem target = pickJellyfinEpisode(all, season, number, peer.episode());
            if (target == null) {
                followFailed(peer, "Found " + series.name() + " in Jellyfin, but not episode "
                        + peer.episode() + ".");
                return;
            }
            List<JellyfinItem> seasonEps = all.stream()
                    .filter(e -> e.seasonNumber() == target.seasonNumber())
                    .sorted(Comparator.comparingInt(JellyfinItem::episodeNumber))
                    .collect(Collectors.toList());

            Platform.runLater(() -> {
                if (!peer.key().equals(playingKey) || jellyfinClient != jf) return;
                followLaunch = true;
                startJellyfin(seasonEps, target);
            });
        });
    }

    /**
     * What the libraries need to find someone else's episode: titles from
     * AniZip, AniList and the announcement itself, other sites' ids, and how
     * the episode is numbered on TVDB.
     */
    private record ShowInfo(boolean movie, int tvdbId, String tmdbId, String imdbId,
                            int tvdbSeason, int tvdbEpisode, int absoluteEpisode,
                            Set<String> titles) {}

    /** Blocking; call off the FX thread. */
    private ShowInfo lookUpShow(PeerMedia peer) {
        Set<String> titles = new LinkedHashSet<>();
        titles.add(peer.title());
        boolean movie = false;
        int tvdbId = 0, season = -1, number = -1, absolute = -1;
        String tmdb = "", imdb = "";

        JSONObject show = aniZip.fetchShow(peer.anilistId());
        if (show != null) {
            JSONObject mappings = show.optJSONObject("mappings");
            if (mappings != null) {
                tvdbId = mappings.optInt("thetvdb_id", 0);
                tmdb   = mappings.isNull("themoviedb_id") ? "" : mappings.optString("themoviedb_id", "");
                imdb   = mappings.isNull("imdb_id")       ? "" : mappings.optString("imdb_id", "");
                movie  = "MOVIE".equalsIgnoreCase(mappings.optString("type", ""));
            }
            JSONObject eps = show.optJSONObject("episodes");
            JSONObject ep  = eps == null ? null : eps.optJSONObject(String.valueOf(peer.episode()));
            if (ep != null) {
                season   = ep.optInt("seasonNumber", -1);
                number   = ep.optInt("episodeNumber", -1);
                absolute = ep.optInt("absoluteEpisodeNumber", -1);
            }
            JSONObject t = show.optJSONObject("titles");
            if (t != null) for (String lang : t.keySet()) titles.add(t.optString(lang, ""));
        }

        // AniZip often has no titles at all for films; AniList always does.
        JSONObject media = aniListForLookup().fetchMedia(peer.anilistId());
        if (media != null) {
            movie |= "MOVIE".equalsIgnoreCase(media.optString("format", ""));
            JSONObject t = media.optJSONObject("title");
            if (t != null) for (String k : t.keySet()) if (!t.isNull(k)) titles.add(t.optString(k, ""));
            org.json.JSONArray syn = media.optJSONArray("synonyms");
            if (syn != null) for (int i = 0; i < syn.length(); i++) titles.add(syn.optString(i, ""));
        }
        titles.removeIf(s -> s == null || s.isBlank());
        return new ShowInfo(movie, tvdbId, tmdb, imdb, season, number, absolute, titles);
    }

    /**
     * The library episode matching an AniList episode.
     *
     * <p>TVDB season and episode first, since that is how Jellyfin numbers by
     * default. Failing that, a series with a single regular season - which is
     * how the AniList plugin lays shows out - is matched on the AniList
     * episode number itself.
     */
    static JellyfinItem pickJellyfinEpisode(List<JellyfinItem> all, int tvdbSeason,
                                            int tvdbEpisode, int anilistEpisode) {
        if (tvdbSeason >= 0 && tvdbEpisode > 0) {
            for (JellyfinItem e : all) {
                if (e.seasonNumber() == tvdbSeason && e.episodeNumber() == tvdbEpisode) return e;
            }
        }
        Set<Integer> seasons = all.stream().map(JellyfinItem::seasonNumber)
                .filter(s -> s > 0).collect(Collectors.toSet());
        if (seasons.size() == 1) {
            for (JellyfinItem e : all) {
                if (e.seasonNumber() > 0 && e.episodeNumber() == anilistEpisode) return e;
            }
        }
        return null;
    }

    private void followLocal(PeerMedia peer, String what) {
        String anchor = !currentLocalFile.isBlank() ? currentLocalFile : savedLocalFilePath;
        File anchorDir = anchor.isBlank() ? null : new File(anchor).getParentFile();
        if (anchorDir == null || !anchorDir.isDirectory()) {
            followFailed(peer, "Couldn't open " + what + ": pick a file in Local mode once, "
                    + "so cowatch knows which folder your shows are in.");
            return;
        }
        Async.run("follow-local", () -> {
            ShowInfo info = lookUpShow(peer);
            Set<String> titles = new HashSet<>();
            for (String t : info.titles()) titles.add(JellyfinClient.titleKey(t));
            titles.remove("");

            File match = findLocalEpisode(localSearchDirs(anchorDir), titles, peer.episode(),
                    info.absoluteEpisode(), info.movie());
            if (match == null) {
                followFailed(peer, "Couldn't find " + what + " in " + anchorDir.getParent()
                        + " or the folders in it.");
                return;
            }
            Platform.runLater(() -> {
                if (!peer.key().equals(playingKey)) return;
                savedLocalFilePath = match.getAbsolutePath();
                localFileField.setText(savedLocalFilePath);
                currentLocalFile = savedLocalFilePath;
                List<String> playlist = buildLocalPlaylist(match);
                followLaunch = true;
                launchPlaylist(playlist, findIndex(playlist, savedLocalFilePath), false);
            });
        });
    }

    /** The last file's folder first, then the other folders beside it. */
    private static List<File> localSearchDirs(File anchorDir) {
        List<File> dirs = new ArrayList<>();
        dirs.add(anchorDir);
        File parent = anchorDir.getParentFile();
        File[] siblings = parent == null ? null : parent.listFiles(File::isDirectory);
        if (siblings != null) {
            Arrays.sort(siblings, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
            for (File d : siblings) {
                if (dirs.size() >= MAX_LOCAL_DIRS) break;
                if (!d.equals(anchorDir)) dirs.add(d);
            }
        }
        return dirs;
    }

    /**
     * A video file whose name parses to one of {@code titleKeys} and to the
     * wanted episode, either as numbered within the season or as an absolute
     * number (long shows and many fansub releases number that way).
     */
    private File findLocalEpisode(List<File> dirs, Set<String> titleKeys, int episode, int absolute,
                                  boolean movie) {
        for (File dir : dirs) {
            File[] files = dir.listFiles(f -> f.isFile() && isVideoFile(f.getName()));
            if (files == null) continue;
            Arrays.sort(files, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
            for (File f : files) {
                List<Element> elements = AnitomyJ.parse(f.getName());
                String title = elements.stream()
                        .filter(e -> e.getCategory() == ElementCategory.kElementAnimeTitle)
                        .map(Element::getValue).findFirst().orElse("");
                int ep = elements.stream()
                        .filter(e -> e.getCategory() == ElementCategory.kElementEpisodeNumber)
                        .map(Element::getValue).findFirst()
                        .map(v -> { try { return Integer.parseInt(v); } catch (NumberFormatException x) { return -1; } })
                        .orElse(-1);
                // A film's file usually carries no episode number at all.
                boolean epOk = ep == episode || (absolute > 0 && ep == absolute) || (movie && ep < 0);
                if (!epOk) continue;
                if (titleMatches(JellyfinClient.titleKey(title), titleKeys)) return f;
            }
        }
        return null;
    }

    /**
     * Release names shorten and decorate titles ("Frieren" for "Sousou no
     * Frieren"), so containment either way counts, past a minimum length that
     * keeps two-letter titles from matching everything.
     */
    static boolean titleMatches(String parsedKey, Set<String> titleKeys) {
        return JellyfinClient.titleMatches(parsedKey, titleKeys);
    }

    private void followSeanime(PeerMedia peer, String what) {
        final SeanimeClient client = seanimeClient;
        if (client == null) {
            followFailed(peer, "Couldn't open " + what + ": connect to Seanime first.");
            return;
        }
        final Map<String, SeanimeClient.LocalFile> library = seanimeLibrary;
        Async.run("follow-seanime", () -> {
            SeanimeClient.LocalFile file = findSeanimeFile(library, peer);
            if (file == null) {
                // The library may have grown since connect.
                Map<String, SeanimeClient.LocalFile> fresh = client.indexLocalFiles();
                Platform.runLater(() -> { if (seanimeClient == client) seanimeLibrary = fresh; });
                file = findSeanimeFile(fresh, peer);
            }
            if (file == null) {
                followFailed(peer, "Couldn't find " + what + " in your Seanime library.");
                return;
            }
            String error = client.playFile(file.path());
            if (error != null) {
                followFailed(peer, "Seanime couldn't open " + what + ": " + error);
            }
            // Otherwise Seanime launches mpv, and the attach watcher joins it.
        });
    }

    private static SeanimeClient.LocalFile findSeanimeFile(Map<String, SeanimeClient.LocalFile> library,
                                                           PeerMedia peer) {
        for (SeanimeClient.LocalFile f : library.values()) {
            if (f.anilistId() == peer.anilistId() && f.episode() == peer.episode()) return f;
        }
        return null;
    }

    /** Stops background work. Called when the window closes. */
    public void shutdown() {
        stopSeanimeEvents();
        stopSeanimeWatch();
        seanimeWatcher.shutdownNow();
        progressScheduler.shutdownNow();
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private VBox rowLabel(String labelText, Control control) {
        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("muted");
        control.setMaxWidth(Double.MAX_VALUE);
        return new VBox(3, lbl, control);
    }

    /**
     * Adds a style class only if it is missing.
     *
     * <p>The widgets here are long-lived fields while the panels around them are
     * rebuilt on every mode switch, so an unguarded add would append the same
     * class again on each pass.
     */
    private static void addOnce(javafx.scene.Node node, String styleClass) {
        if (!node.getStyleClass().contains(styleClass)) node.getStyleClass().add(styleClass);
    }

    /** Shows or hides a node, and stops a hidden one reserving layout space. */
    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("subhead");
        return l;
    }

    private TextField field(String prompt) {
        TextField tf = new TextField();
        tf.setPromptText(prompt);
        tf.setMaxWidth(Double.MAX_VALUE);
        return tf;
    }

    public VBox getNode() { return node; }
}