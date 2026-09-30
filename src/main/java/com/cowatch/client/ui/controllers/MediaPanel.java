package com.cowatch.client.ui.controllers;

import com.cowatch.client.jellyfin.*;
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

        Label heading = new Label("Media Source");
        heading.getStyleClass().add("heading");

        card.getStyleClass().add("card");
        card.setFillWidth(true);
        VBox.setVgrow(card,        Priority.ALWAYS);
        VBox.setVgrow(contentArea, Priority.ALWAYS);

        card.getChildren().addAll(heading, contentArea);
        node.getChildren().add(card);
        showJellyfinPanel();
    }

    // ── Mode switching ────────────────────────────────────────────────────

    public void setMode(SyncMode mode) {
        if (mode == currentMode) return;
        currentMode = mode;
        if (mode == SyncMode.LOCAL) showLocalPanel();
        else showJellyfinPanel();
    }

    public void previewMode(SyncMode mode) { setMode(mode); }

    private void showJellyfinPanel() { contentArea.getChildren().setAll(buildJellyfinPanel()); }
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

        Platform.runLater(() -> updatePlaylistLabels(isJellyfin));
    }

    private void resetWatchedTrackingForNewEpisode() {
        reportedThisEpisode.set(false);
        if (syncEngine != null) syncEngine.resetWatchedTracking();
    }

    // ── Jellyfin panel ────────────────────────────────────────────────────

    private VBox buildJellyfinPanel() {
        jellyfinPassField.setPromptText("password");
        jellyfinPassField.setMaxWidth(Double.MAX_VALUE);

        jellyfinStatus.getStyleClass().addAll("subhead", "jellyfin-status-pending");
        jellyfinConnBtn.getStyleClass().add("accent");
        jellyfinConnBtn.setMaxWidth(Double.MAX_VALUE);
        jellyfinConnBtn.setOnAction(e -> handleJellyfinConnect());
        HBox.setHgrow(jellyfinConnBtn, Priority.ALWAYS);

        jellyfinSaveBtn.getStyleClass().add("secondary");
        jellyfinSaveBtn.setOnAction(e -> savePrefs());

        HBox btnRow = new HBox(8, jellyfinConnBtn, jellyfinSaveBtn);

        VBox loginBox = new VBox(8,
                rowLabel("Server URL", jellyfinUrlField),
                rowLabel("Username",   jellyfinUserField),
                rowLabel("Password",   jellyfinPassField),
                rowLabel("API Key",    jellyfinApiField),
                btnRow,
                jellyfinStatus);
        loginBox.getStyleClass().add("jellyfin-login");

        // Collapsible wrapper — starts expanded unless we're already
        // connected (e.g. panel rebuilt after switching modes and back),
        // in which case it stays out of the way.
        jellyfinLoginPane = new TitledPane("Jellyfin Connection", loginBox);
        jellyfinLoginPane.setAnimated(true);
        jellyfinLoginPane.getStyleClass().add("jellyfin-login-pane");
        jellyfinLoginPane.setExpanded(jellyfinClient == null);

        showSearchField.setPromptText("Search shows & movies…");
        if (!showSearchField.getStyleClass().contains("search-field"))
            showSearchField.getStyleClass().add("search-field");

        showsList.setPlaceholder(new Label("Connect to Jellyfin to browse"));
        showsList.setPrefHeight(100); showsList.setMinHeight(60);
        VBox.setVgrow(showsList, Priority.ALWAYS);
        showsList.setOnMouseClicked(e -> {
            JellyfinItem sel = showsList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            // Movies have no Season/Episode hierarchy in Jellyfin — asking
            // for seasons on a movie's Id returns nothing (or garbage),
            // which is why movies could never reach the episode list before.
            if ("Movie".equals(sel.type())) {
                selectMovie(sel);
            } else {
                loadSeasons(sel.id());
            }
        });

        seasonsList.setPlaceholder(new Label("Select a show above"));
        seasonsList.setPrefHeight(70); seasonsList.setMinHeight(50);
        seasonsList.setOnMouseClicked(e -> {
            JellyfinItem sel = seasonsList.getSelectionModel().getSelectedItem();
            if (sel != null) loadEpisodes(sel.id());
        });

        episodesList.setPlaceholder(new Label("Select a season above"));
        episodesList.setPrefHeight(100); episodesList.setMinHeight(60);
        VBox.setVgrow(episodesList, Priority.ALWAYS);

        if (!launchJfBtn.getStyleClass().contains("green")) launchJfBtn.getStyleClass().add("green");
        launchJfBtn.setMaxWidth(Double.MAX_VALUE);
        launchJfBtn.setDisable(true);
        launchJfBtn.setOnAction(e -> handleJellyfinLaunch());

        nextJfBtn.setMaxWidth(Double.MAX_VALUE);
        nextJfBtn.setDisable(true);
        nextJfBtn.setOnAction(e -> handleNextEpisode(true));

        episodesList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, now) -> launchJfBtn.setDisable(now == null));

        VBox browserBox = new VBox(6,
                sectionLabel("Shows / Movies"), showSearchField, showsList,
                sectionLabel("Seasons"),        seasonsList,
                sectionLabel("Episodes"),       episodesList,
                launchJfBtn, nextJfBtn);
        browserBox.setFillWidth(true);
        VBox.setVgrow(showsList, Priority.ALWAYS);
        VBox.setVgrow(episodesList, Priority.ALWAYS);

        VBox panel = new VBox(10, jellyfinLoginPane, browserBox);
        panel.setFillWidth(true);
        VBox.setVgrow(browserBox, Priority.ALWAYS);
        return panel;
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

        if (!launchLocalBtn.getStyleClass().contains("green")) launchLocalBtn.getStyleClass().add("green");
        launchLocalBtn.setMaxWidth(Double.MAX_VALUE);
        launchLocalBtn.setOnAction(e -> handleLocalLaunch());

        nextLocalBtn.setMaxWidth(Double.MAX_VALUE);
        nextLocalBtn.setDisable(true);
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

        VBox panel = new VBox(12,
                sectionLabel("Select local media file"),
                fileRow, playlistLabel, hint,
                launchLocalBtn, nextLocalBtn);
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
        launchPlaylist(playlist, startIndex, false);
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
            if (currentMode == SyncMode.JELLYFIN) {
                reportJellyfinWatched();
            } else if (currentMode == SyncMode.LOCAL) {
                reportLocalWatched();
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
        setJellyfinStatus("⏳ Connecting…", "pending");

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
                    setJellyfinStatus("✓ Connected — " + (shows.size() + movies.size()) + " items", "ok");
                    jellyfinConnBtn.setText("Connected ✓");
                    // Free up vertical space now that we're connected —
                    // collapse the login form so the browser gets more room.
                    if (jellyfinLoginPane != null) jellyfinLoginPane.setExpanded(false);
                });
            } else {
                Platform.runLater(() -> {
                    setJellyfinStatus("✗ Connection failed — check URL / credentials", "error");
                    jellyfinConnBtn.setDisable(false);
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

        // Store full episode list — currentIndex tracks which ep is playing
        // as MPV advances through the playlist via playlist-next
        jfEpisodeList = new ArrayList<>(episodesList.getItems());
        List<String> urls = jfEpisodeList.stream()
                .map(ep -> jellyfinClient.getStreamUrl(ep.id()))
                .collect(Collectors.toList());
        int startIndex = jfEpisodeList.indexOf(selected);

        launchPlaylist(urls, startIndex, true);

        // Report playback start + start periodic progress ticks
        final JellyfinItem startItem = selected;
        Async.run("jellyfin-session-start", () -> {
            jellyfinClient.reportPlaybackStart(startItem.id(), 0, true);
            jellyfinClient.registerCapabilities();

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

    // ── Preferences ───────────────────────────────────────────────────────

    private void savePrefs() {
        Properties p = new Properties();
        p.setProperty("jellyfin.url",      jellyfinUrlField.getText().trim());
        p.setProperty("jellyfin.username", jellyfinUserField.getText().trim());
        p.setProperty("jellyfin.apikey",   jellyfinApiField.getText().trim());
        // The password is deliberately absent: this file is plain text. A
        // successful login stores the access token Jellyfin hands back
        // instead, which reconnects just as well and can be revoked server
        // side. See AppPrefs.isSecret().

        try {
            AppPrefs.save(PREFS_FILE, p, "cowatch media settings");
            Motion.confirm(jellyfinSaveBtn, "Saved ✓");
        } catch (IOException ex) {
            owner.showError("Save Failed", "Could not save settings:\n" + ex.getMessage());
        }
    }

    private void loadPrefs() {
        Properties p = AppPrefs.load(PREFS_FILE);
        jellyfinUrlField.setText( p.getProperty("jellyfin.url",      ""));
        jellyfinUserField.setText(p.getProperty("jellyfin.username", ""));
        jellyfinApiField.setText( p.getProperty("jellyfin.apikey",   ""));

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

    // ── UI helpers ────────────────────────────────────────────────────────

    private VBox rowLabel(String labelText, Control control) {
        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("muted");
        control.setMaxWidth(Double.MAX_VALUE);
        return new VBox(3, lbl, control);
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