package com.syncplay.client.ui.controllers;

import com.syncplay.client.jellyfin.*;
import com.syncplay.client.model.SyncMode;
import com.syncplay.client.ui.MainWindow;

import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.List;

public class MediaPanel {

    private final MainWindow  owner;
    private final VBox        node        = new VBox(12);
    private final VBox        card        = new VBox(10);
    private final StackPane   contentArea = new StackPane();

    // ── Jellyfin widgets ──────────────────────────────────────────────────
    private final TextField     jellyfinUrlField  = field("https://jellyfin.example.com");
    private final TextField     jellyfinUserField = field("username");
    private final PasswordField jellyfinPassField = new PasswordField();
    private final TextField     jellyfinApiField  = field("API key (optional — skips login)");
    private final Button        jellyfinConnBtn   = new Button("Connect to Jellyfin");
    private final Label         jellyfinStatus    = new Label("Not connected");

    private final ListView<JellyfinItem> showsList    = new ListView<>();
    private final ListView<JellyfinItem> seasonsList  = new ListView<>();
    private final ListView<JellyfinItem> episodesList = new ListView<>();
    private final Button                 launchJfBtn  = new Button("▶  Launch in MPV");

    // ── Local file widgets ────────────────────────────────────────────────
    private final TextField localFileField = field("No file selected");
    private final Button    browseBtn      = new Button("Browse…");
    private final Button    launchLocalBtn = new Button("▶  Launch in MPV");

    // ── State ─────────────────────────────────────────────────────────────
    private JellyfinClient jellyfinClient;
    private SyncMode       currentMode        = SyncMode.JELLYFIN;
    private String         savedLocalFilePath = "";   // persists across mode switches

    public MediaPanel(MainWindow owner) {
        this.owner = owner;
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
        if (mode == currentMode) return;   // no rebuild if unchanged — preserves UI state
        currentMode = mode;
        if (mode == SyncMode.LOCAL) {
            showLocalPanel();
        } else {
            showJellyfinPanel();
        }
    }

    public void previewMode(SyncMode mode) { setMode(mode); }

    private void showJellyfinPanel() { contentArea.getChildren().setAll(buildJellyfinPanel()); }
    private void showLocalPanel()    { contentArea.getChildren().setAll(buildLocalPanel()); }

    // ── Jellyfin panel ────────────────────────────────────────────────────

    private VBox buildJellyfinPanel() {
        jellyfinPassField.setPromptText("password");
        jellyfinPassField.setMaxWidth(Double.MAX_VALUE);

        jellyfinStatus.getStyleClass().addAll("subhead", "jellyfin-status-pending");
        jellyfinStatus.setText("Not connected");

        jellyfinConnBtn.getStyleClass().add("accent");
        jellyfinConnBtn.setMaxWidth(Double.MAX_VALUE);
        jellyfinConnBtn.setOnAction(e -> handleJellyfinConnect());

        VBox loginBox = new VBox(8,
                rowLabel("Server URL", jellyfinUrlField),
                rowLabel("Username",   jellyfinUserField),
                rowLabel("Password",   jellyfinPassField),
                rowLabel("API Key",    jellyfinApiField),
                jellyfinConnBtn,
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
        episodesList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, now) -> launchJfBtn.setDisable(now == null));

        VBox browserBox = new VBox(6,
                sectionLabel("Shows / Movies"), showsList,
                sectionLabel("Seasons"),        seasonsList,
                sectionLabel("Episodes"),       episodesList,
                launchJfBtn);
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
                            "*.mp4","*.mkv","*.avi","*.mov","*.flv","*.wmv","*.m4v","*.ts"),
                    new FileChooser.ExtensionFilter("All Files", "*.*"));
            File file = fc.showOpenDialog(
                    node.getScene() != null ? node.getScene().getWindow() : null);
            if (file != null) {
                savedLocalFilePath = file.getAbsolutePath();
                localFileField.setText(savedLocalFilePath);
                launchLocalBtn.setDisable(false);
            }
        });

        localFileField.setEditable(false);
        localFileField.setMaxWidth(Double.MAX_VALUE);
        localFileField.setPromptText("Click Browse to pick a file…");

        if (!launchLocalBtn.getStyleClass().contains("green"))
            launchLocalBtn.getStyleClass().add("green");
        launchLocalBtn.setMaxWidth(Double.MAX_VALUE);
        launchLocalBtn.setOnAction(e -> handleLocalLaunch());

        // Restore saved state LAST — overrides any setDisable(true) above
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
                        "Make sure everyone has the same episode before launching.");
        hint.setWrapText(true);
        hint.getStyleClass().add("subhead");

        VBox panel = new VBox(12,
                sectionLabel("Select local media file"),
                fileRow, hint, launchLocalBtn);
        panel.setFillWidth(true);
        panel.setAlignment(Pos.TOP_LEFT);
        return panel;
    }

    // ── Handlers ─────────────────────────────────────────────────────────

    private void handleLocalLaunch() {
        String path = localFileField.getText().trim();
        if (path.isEmpty() || path.equals("No file selected")) {
            owner.showError("Please select a file first.");
            return;
        }
        if (!new File(path).exists()) {
            owner.showError("File not found:\n" + path);
            return;
        }
        launchLocalBtn.setDisable(true);
        launchLocalBtn.setText("Launching…");
        owner.onLaunch(path);
    }

    public void onMpvLaunched(boolean success) {
        if (success) {
            launchLocalBtn.setText("MPV Running ✓");
        } else {
            launchLocalBtn.setDisable(false);
            launchLocalBtn.setText("▶  Launch in MPV");
        }
    }

    public void onMpvClosed(boolean success) {
        launchLocalBtn.setText("▶  Launch in MPV");
        launchLocalBtn.setDisable(false);
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
        JellyfinItem item = episodesList.getSelectionModel().getSelectedItem();
        if (item == null || jellyfinClient == null) return;
        String streamUrl = jellyfinClient.getStreamUrl(item.id());
        launchJfBtn.setDisable(true);
        launchJfBtn.setText("Launching…");
        owner.onLaunch(streamUrl);
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