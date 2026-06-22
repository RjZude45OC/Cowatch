package com.syncplay.client.ui.controllers;

import com.syncplay.client.jellyfin.*;
import com.syncplay.client.model.SyncMode;
import com.syncplay.client.ui.MainWindow;

import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.Properties;

public class MediaPanel {

    private static final Path PREFS_FILE = resolvePrefsFile();

    // Video extensions recognised for folder scanning
    private static final Set<String> VIDEO_EXTS = Set.of(
            "mp4","mkv","avi","mov","flv","wmv","m4v","ts","webm","ogv"
    );

    private final MainWindow owner;
    private final VBox       node        = new VBox(12);
    private final VBox       card        = new VBox(10);
    private final StackPane  contentArea = new StackPane();

    // ── Jellyfin widgets ──────────────────────────────────────────────────
    private final TextField     jellyfinUrlField  = field("https://jellyfin.example.com");
    private final TextField     jellyfinUserField = field("username");
    private final PasswordField jellyfinPassField = new PasswordField();
    private final TextField     jellyfinApiField  = field("API key (optional — skips login)");
    private final Button        jellyfinConnBtn   = new Button("Connect to Jellyfin");
    private final Button        jellyfinSaveBtn   = new Button("Save");
    private final Label         jellyfinStatus    = new Label("Not connected");

    private final ListView<JellyfinItem> showsList    = new ListView<>();
    private final ListView<JellyfinItem> seasonsList  = new ListView<>();
    private final ListView<JellyfinItem> episodesList = new ListView<>();
    private final Button                 launchJfBtn  = new Button("▶  Launch in MPV");
    private final Button                 nextJfBtn    = new Button("▶▶  Next Episode");

    // ── Local file widgets ────────────────────────────────────────────────
    private final TextField localFileField  = field("No file selected");
    private final Button    browseBtn       = new Button("Browse…");
    private final Button    launchLocalBtn  = new Button("▶  Launch in MPV");
    private final Button    nextLocalBtn    = new Button("▶▶  Next Episode");
    private final Label     playlistLabel   = new Label();

    // ── State ─────────────────────────────────────────────────────────────
    private JellyfinClient jellyfinClient;
    private SyncMode       currentMode        = SyncMode.JELLYFIN;
    private String         savedLocalFilePath = "";

    // Playlist state — shared logic for local and Jellyfin
    private List<String> playlistEntries = new ArrayList<>();
    private int          currentIndex    = 0;

    public MediaPanel(MainWindow owner) {
        this.owner = owner;
        loadPrefs();
        build();
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

    // ── Playlist core logic ───────────────────────────────────────────────

    /**
     * Build a playlist from a local file — scan sibling video files in
     * the same folder, sort alphabetically, find the selected file's index.
     */
    private List<String> buildLocalPlaylist(File selectedFile) {
        File dir = selectedFile.getParentFile();
        if (dir == null || !dir.isDirectory()) return List.of(selectedFile.getAbsolutePath());

        File[] siblings = dir.listFiles(f ->
                f.isFile() && isVideoFile(f.getName()));
        if (siblings == null || siblings.length == 0) return List.of(selectedFile.getAbsolutePath());

        return Arrays.stream(siblings)
                .sorted(Comparator.comparing(f -> f.getName().toLowerCase()))
                .map(File::getAbsolutePath)
                .collect(Collectors.toList());
    }

    private int findIndex(List<String> entries, String target) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).equals(target)) return i;
        }
        return 0;
    }

    private boolean isVideoFile(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return VIDEO_EXTS.contains(name.substring(dot + 1).toLowerCase());
    }

    /**
     * Launch with a playlist. Shared by local and Jellyfin paths.
     * Stores playlist state so "Next Episode" can advance it.
     * This is the FIRST launch only — spawns one MPV process with the
     * whole playlist loaded (--playlist + --playlist-start).
     */
    private void launchPlaylist(List<String> entries, int startIndex, boolean isJellyfin) {
        if (entries.isEmpty()) return;
        playlistEntries = new ArrayList<>(entries);
        currentIndex    = Math.max(0, Math.min(startIndex, entries.size() - 1));

        updatePlaylistLabels(isJellyfin);

        if (isJellyfin) {
            launchJfBtn.setDisable(true);
            launchJfBtn.setText("Launching…");
        } else {
            launchLocalBtn.setDisable(true);
            launchLocalBtn.setText("Launching…");
        }

        // Use list + start index for multi-entry playlists, single entry otherwise
        if (entries.size() == 1) {
            owner.onLaunch(entries.get(0));
        } else {
            owner.onLaunchPlaylist(entries, startIndex);
        }
    }

    private void updatePlaylistLabels(boolean isJellyfin) {
        int total = playlistEntries.size();
        String info = total > 1
                ? "Episode " + (currentIndex + 1) + " of " + total
                : "";
        playlistLabel.setText(info);

        boolean hasNext = currentIndex < total - 1;
        nextLocalBtn.setDisable(!hasNext);
        nextJfBtn.setDisable(!hasNext);
    }

    /**
     * Advance to the next entry in the already-loaded playlist.
     * Does NOT relaunch MPV — sends "playlist-next" over IPC to the
     * running instance, which already has the full playlist loaded
     * from the initial launch. This avoids killing/restarting the
     * player (window flicker, re-handshake, and the macOS teardown
     * race where the old IPC socket isn't released before relaunch).
     */
    private void handleNextEpisode(boolean isJellyfin) {
        if (currentIndex >= playlistEntries.size() - 1) return;
        currentIndex++;
        updatePlaylistLabels(isJellyfin);

        if (isJellyfin) {
            launchJfBtn.setDisable(true);
            launchJfBtn.setText("Launching…");
        } else {
            launchLocalBtn.setDisable(true);
            launchLocalBtn.setText("Launching…");
        }

        owner.onNextEpisode();
    }

    // ── Jellyfin panel ────────────────────────────────────────────────────

    private VBox buildJellyfinPanel() {
        jellyfinPassField.setPromptText("password");
        jellyfinPassField.setMaxWidth(Double.MAX_VALUE);

        jellyfinStatus.getStyleClass().addAll("subhead", "jellyfin-status-pending");
        jellyfinStatus.setText("Not connected");

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

        showsList.setPlaceholder(new Label("Connect to Jellyfin to browse"));
        showsList.setPrefHeight(120);
        showsList.setMinHeight(80);
        VBox.setVgrow(showsList, Priority.ALWAYS);
        showsList.setOnMouseClicked(e -> {
            JellyfinItem sel = showsList.getSelectionModel().getSelectedItem();
            if (sel != null) loadSeasons(sel.id());
        });

        seasonsList.setPlaceholder(new Label("Select a show above"));
        seasonsList.setPrefHeight(90);
        seasonsList.setMinHeight(60);
        seasonsList.setOnMouseClicked(e -> {
            JellyfinItem sel = seasonsList.getSelectionModel().getSelectedItem();
            if (sel != null) loadEpisodes(sel.id());
        });

        episodesList.setPlaceholder(new Label("Select a season above"));
        episodesList.setPrefHeight(120);
        episodesList.setMinHeight(80);
        VBox.setVgrow(episodesList, Priority.ALWAYS);

        if (!launchJfBtn.getStyleClass().contains("green"))
            launchJfBtn.getStyleClass().add("green");
        launchJfBtn.setMaxWidth(Double.MAX_VALUE);
        launchJfBtn.setDisable(true);
        launchJfBtn.setOnAction(e -> handleJellyfinLaunch());

        nextJfBtn.setMaxWidth(Double.MAX_VALUE);
        nextJfBtn.setDisable(true);
        nextJfBtn.setOnAction(e -> handleNextEpisode(true));

        episodesList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, now) -> launchJfBtn.setDisable(now == null));

        VBox browserBox = new VBox(6,
                sectionLabel("Shows / Movies"), showsList,
                sectionLabel("Seasons"),        seasonsList,
                sectionLabel("Episodes"),       episodesList,
                launchJfBtn,
                nextJfBtn);
        browserBox.setFillWidth(true);
        VBox.setVgrow(showsList,    Priority.ALWAYS);
        VBox.setVgrow(episodesList, Priority.ALWAYS);

        VBox panel = new VBox(10, loginBox, browserBox);
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
            File file = fc.showOpenDialog(
                    node.getScene() != null ? node.getScene().getWindow() : null);
            if (file != null) {
                savedLocalFilePath = file.getAbsolutePath();
                localFileField.setText(savedLocalFilePath);
                launchLocalBtn.setDisable(false);

                // Pre-scan folder to show playlist info
                List<String> playlist = buildLocalPlaylist(file);
                int idx = findIndex(playlist, file.getAbsolutePath());
                int total = playlist.size();
                playlistLabel.setText(total > 1
                        ? "Found " + total + " episodes in folder — starting at #" + (idx + 1)
                        : "Single file selected");
            }
        });

        localFileField.setEditable(false);
        localFileField.setMaxWidth(Double.MAX_VALUE);
        localFileField.setPromptText("Click Browse to pick a file…");

        if (!launchLocalBtn.getStyleClass().contains("green"))
            launchLocalBtn.getStyleClass().add("green");
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

        Label hint = new Label(
                "Each user loads their own copy of the file.\n" +
                        "All video files in the same folder are added as a playlist automatically.");
        hint.setWrapText(true);
        hint.getStyleClass().add("subhead");

        VBox panel = new VBox(12,
                sectionLabel("Select local media file"),
                fileRow,
                playlistLabel,
                hint,
                launchLocalBtn,
                nextLocalBtn);
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
        launchPlaylist(playlist, startIndex, false);
    }

    public void onMpvLaunched(boolean success) {
        if (success) {
            launchLocalBtn.setText("MPV Running ✓");
            launchJfBtn.setText("MPV Running ✓");
            // Enable next button if playlist has more entries
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
        launchLocalBtn.setText("▶  Launch in MPV");
        launchLocalBtn.setDisable(false);
        launchJfBtn.setText("▶  Launch in MPV");
        launchJfBtn.setDisable(episodesList.getSelectionModel().getSelectedItem() == null);
        nextLocalBtn.setDisable(true);
        nextJfBtn.setDisable(true);
    }

    private void handleJellyfinConnect() {
        String url  = jellyfinUrlField.getText().trim();
        String user = jellyfinUserField.getText().trim();
        String pass = jellyfinPassField.getText();
        String key  = jellyfinApiField.getText().trim();

        jellyfinConnBtn.setDisable(true);
        setJellyfinStatus("⏳ Connecting…", "pending");

        new Thread(() -> {
            jellyfinClient = new JellyfinClient(url);
            boolean ok;
            if (!key.isBlank()) {
                jellyfinClient.setApiKey(key, "");
                ok = jellyfinClient.ping();
            } else {
                ok = jellyfinClient.authenticate(user, pass);
            }

            if (ok) {
                List<JellyfinItem> shows  = jellyfinClient.getTvShows();
                List<JellyfinItem> movies = jellyfinClient.getMovies();
                Platform.runLater(() -> {
                    showsList.getItems().setAll(shows);
                    showsList.getItems().addAll(movies);
                    setJellyfinStatus("✓ Connected — "
                            + (shows.size() + movies.size()) + " items", "ok");
                    jellyfinConnBtn.setText("Connected ✓");
                });
            } else {
                Platform.runLater(() -> {
                    setJellyfinStatus("✗ Connection failed — check URL / credentials", "error");
                    jellyfinConnBtn.setDisable(false);
                });
            }
        }, "jellyfin-auth").start();
    }

    private void setJellyfinStatus(String text, String state) {
        jellyfinStatus.setText(text);
        jellyfinStatus.getStyleClass()
                .removeAll("jellyfin-status-pending", "jellyfin-status-ok", "jellyfin-status-error");
        jellyfinStatus.getStyleClass().add("jellyfin-status-" + state);
    }

    private void loadSeasons(String seriesId) {
        seasonsList.getItems().clear();
        episodesList.getItems().clear();
        new Thread(() -> {
            List<JellyfinItem> seasons = jellyfinClient.getSeasons(seriesId);
            Platform.runLater(() -> seasonsList.getItems().setAll(seasons));
        }, "jellyfin-seasons").start();
    }

    private void loadEpisodes(String seasonId) {
        episodesList.getItems().clear();
        new Thread(() -> {
            List<JellyfinItem> eps = jellyfinClient.getEpisodes(seasonId);
            Platform.runLater(() -> episodesList.getItems().setAll(eps));
        }, "jellyfin-episodes").start();
    }

    private void handleJellyfinLaunch() {
        JellyfinItem selected = episodesList.getSelectionModel().getSelectedItem();
        if (selected == null || jellyfinClient == null) return;

        // Build playlist from all episodes in the list
        List<JellyfinItem> allEpisodes = new ArrayList<>(episodesList.getItems());
        List<String> urls = allEpisodes.stream()
                .map(ep -> jellyfinClient.getStreamUrl(ep.id()))
                .collect(Collectors.toList());
        int startIndex = allEpisodes.indexOf(selected);

        launchPlaylist(urls, startIndex, true);
    }

    // ── Preferences ───────────────────────────────────────────────────────

    private void savePrefs() {
        try {
            Properties p = new Properties();
            p.setProperty("jellyfin.url",      jellyfinUrlField.getText().trim());
            p.setProperty("jellyfin.username",  jellyfinUserField.getText().trim());
            p.setProperty("jellyfin.apikey",    jellyfinApiField.getText().trim());

            Files.createDirectories(PREFS_FILE.getParent());
            try (OutputStream out = Files.newOutputStream(PREFS_FILE)) {
                p.store(out, "SyncPlay media settings");
            }
            jellyfinSaveBtn.setText("Saved ✓");
            new Thread(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                Platform.runLater(() -> jellyfinSaveBtn.setText("Save"));
            }, "save-reset").start();

        } catch (IOException ex) {
            owner.showError("Save Failed", "Could not save settings:\n" + ex.getMessage());
        }
    }

    private void loadPrefs() {
        if (!Files.exists(PREFS_FILE)) return;
        try (InputStream in = Files.newInputStream(PREFS_FILE)) {
            Properties p = new Properties();
            p.load(in);
            jellyfinUrlField.setText( p.getProperty("jellyfin.url",     jellyfinUrlField.getPromptText()));
            jellyfinUserField.setText(p.getProperty("jellyfin.username", jellyfinUserField.getPromptText()));
            jellyfinApiField.setText( p.getProperty("jellyfin.apikey",   ""));
        } catch (IOException ignored) {}
    }

    private static Path resolvePrefsFile() {
        String os = System.getProperty("os.name", "").toLowerCase();
        Path dir;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            dir = (appData != null ? Paths.get(appData) : Path.of(System.getProperty("user.home")))
                    .resolve("SyncPlay");
        } else if (os.contains("mac")) {
            dir = Paths.get(System.getProperty("user.home"),
                    "Library", "Application Support", "SyncPlay");
        } else {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            dir = (xdg != null ? Paths.get(xdg) : Paths.get(System.getProperty("user.home"), ".config"))
                    .resolve("syncplay");
        }
        return dir.resolve("syncplay-media.properties");
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