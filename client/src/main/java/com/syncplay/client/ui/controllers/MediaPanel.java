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

/**
 * Right-side media panel.
 *
 * In LOCAL_TO_LOCAL mode → shows a file picker.
 * In JELLYFIN modes       → shows Jellyfin login + library browser.
 * Switches dynamically based on the mode selected in ConfigPanel.
 */
public class MediaPanel {

    private final MainWindow owner;
    private final VBox node = new VBox(12);

    // ── Jellyfin widgets ──────────────────────────────────────────────────
    private final TextField  jellyfinUrlField  = field("https://jellyfin.example.com");
    private final TextField  jellyfinUserField = field("your-username");
    private final PasswordField jellyfinPassField = new PasswordField();
    private final TextField  jellyfinApiField  = field("paste API key (optional)");
    private final Button     jellyfinConnBtn   = new Button("Connect to Jellyfin");
    private final Label      jellyfinStatus    = new Label("Not connected");

    // Jellyfin library lists
    private final ListView<JellyfinItem> showsList    = new ListView<>();
    private final ListView<JellyfinItem> seasonsList  = new ListView<>();
    private final ListView<JellyfinItem> episodesList = new ListView<>();
    private final Button                 launchJfBtn  = new Button("▶  Launch in MPV");

    // ── Local file widgets ────────────────────────────────────────────────
    private final TextField  localFileField = field("No file selected");
    private final Button     browseBtn      = new Button("Browse…");
    private final Button     launchLocalBtn = new Button("▶  Launch in MPV");

    // ── Shared state ──────────────────────────────────────────────────────
    private JellyfinClient jellyfinClient;
    private SyncMode       currentMode = SyncMode.JELLYFIN_TO_JELLYFIN;
    private final StackPane contentArea = new StackPane(); // swaps panels

    public MediaPanel(MainWindow owner) {
        this.owner = owner;
        build();
    }

    private void build() {
        node.setPadding(new Insets(0));
        VBox.setVgrow(node, Priority.ALWAYS);

        Label heading = new Label("Media Source");
        heading.getStyleClass().add("heading");

        VBox card = new VBox(10, heading, contentArea);
        card.getStyleClass().add("card");
        VBox.setVgrow(card, Priority.ALWAYS);
        VBox.setVgrow(contentArea, Priority.ALWAYS);

        node.getChildren().add(card);
        showJellyfinPanel(); // default
    }

    // ─── Panel switcher ───────────────────────────────────────────────────

    public void setMode(SyncMode mode) {
        currentMode = mode;
        if (mode == SyncMode.LOCAL_TO_LOCAL) showLocalPanel();
        else showJellyfinPanel();
    }

    private void showJellyfinPanel() {
        contentArea.getChildren().setAll(buildJellyfinPanel());
    }

    private void showLocalPanel() {
        contentArea.getChildren().setAll(buildLocalPanel());
    }

    // ─── Jellyfin Panel ───────────────────────────────────────────────────

    private VBox buildJellyfinPanel() {
        // ── Login section
        jellyfinPassField.setPromptText("password (or leave blank if using API key)");
        jellyfinPassField.setMaxWidth(Double.MAX_VALUE);

        jellyfinStatus.getStyleClass().add("subhead");

        jellyfinConnBtn.getStyleClass().add("accent");
        jellyfinConnBtn.setMaxWidth(Double.MAX_VALUE);
        jellyfinConnBtn.setOnAction(e -> handleJellyfinConnect());

        VBox loginBox = new VBox(6,
            rowLabel("Server URL", jellyfinUrlField),
            rowLabel("Username",   jellyfinUserField),
            rowLabel("Password",   jellyfinPassField),
            rowLabel("API Key",    jellyfinApiField),
            jellyfinConnBtn,
            jellyfinStatus
        );
        loginBox.getStyleClass().add("card");
        loginBox.setStyle("-fx-background-color: #252836; -fx-border-color: #2e3148; " +
                          "-fx-border-radius: 8px; -fx-background-radius: 8px; -fx-padding: 10;");

        // ── Library browser: shows → seasons → episodes
        showsList.setPlaceholder(new Label("Connect to Jellyfin to browse"));
        showsList.setPrefHeight(120);
        showsList.setOnMouseClicked(e -> {
            JellyfinItem selected = showsList.getSelectionModel().getSelectedItem();
            if (selected != null) loadSeasons(selected.id());
        });

        seasonsList.setPlaceholder(new Label("Select a show"));
        seasonsList.setPrefHeight(80);
        seasonsList.setOnMouseClicked(e -> {
            JellyfinItem selected = seasonsList.getSelectionModel().getSelectedItem();
            if (selected != null) loadEpisodes(selected.id());
        });

        episodesList.setPlaceholder(new Label("Select a season"));
        episodesList.setPrefHeight(100);

        launchJfBtn.getStyleClass().add("green");
        launchJfBtn.setMaxWidth(Double.MAX_VALUE);
        launchJfBtn.setDisable(true);
        launchJfBtn.setOnAction(e -> handleJellyfinLaunch());

        episodesList.getSelectionModel().selectedItemProperty().addListener(
            (obs, old, now) -> launchJfBtn.setDisable(now == null));

        VBox browserBox = new VBox(8,
            sectionLabel("Shows / Movies"), showsList,
            sectionLabel("Seasons"),        seasonsList,
            sectionLabel("Episodes"),       episodesList,
            launchJfBtn
        );
        VBox.setVgrow(showsList,    Priority.ALWAYS);
        VBox.setVgrow(episodesList, Priority.ALWAYS);

        VBox panel = new VBox(10, loginBox, browserBox);
        VBox.setVgrow(browserBox, Priority.ALWAYS);
        panel.setAlignment(Pos.TOP_LEFT);
        return panel;
    }

    // ─── Local File Panel ─────────────────────────────────────────────────

    private VBox buildLocalPanel() {
        browseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select Media File");
            fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Video Files",
                    "*.mp4", "*.mkv", "*.avi", "*.mov", "*.flv", "*.wmv", "*.m4v"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
            );
            File file = fc.showOpenDialog(null);
            if (file != null) localFileField.setText(file.getAbsolutePath());
        });

        localFileField.setEditable(false);
        localFileField.setMaxWidth(Double.MAX_VALUE);

        launchLocalBtn.getStyleClass().add("green");
        launchLocalBtn.setMaxWidth(Double.MAX_VALUE);
        launchLocalBtn.setOnAction(e -> {
            String path = localFileField.getText().trim();
            if (path.isEmpty() || path.equals("No file selected")) {
                owner.showError("Please select a file first.");
                return;
            }
            owner.onLaunch(path);
            launchLocalBtn.setDisable(true);
            launchLocalBtn.setText("MPV Launched ✓");
        });

        HBox fileRow = new HBox(8, localFileField, browseBtn);
        HBox.setHgrow(localFileField, Priority.ALWAYS);

        VBox panel = new VBox(12,
            sectionLabel("Select local media file"),
            fileRow,
            launchLocalBtn
        );
        panel.setAlignment(Pos.TOP_LEFT);
        return panel;
    }

    // ─── Jellyfin Handlers ────────────────────────────────────────────────

    private void handleJellyfinConnect() {
        String url  = jellyfinUrlField.getText().trim();
        String user = jellyfinUserField.getText().trim();
        String pass = jellyfinPassField.getText();
        String key  = jellyfinApiField.getText().trim();

        jellyfinConnBtn.setDisable(true);
        jellyfinStatus.setText("⏳ Connecting…");
        jellyfinStatus.setStyle("-fx-text-fill: #f0a040;");

        new Thread(() -> {
            jellyfinClient = new JellyfinClient(url);

            boolean ok;
            if (!key.isBlank()) {
                // API key mode — need userId too; try a quick auth call
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
                    jellyfinStatus.setText("✓ Connected — " +
                        (shows.size() + movies.size()) + " items found");
                    jellyfinStatus.setStyle("-fx-text-fill: #2eca6a;");
                    jellyfinConnBtn.setText("Connected ✓");
                });
            } else {
                Platform.runLater(() -> {
                    jellyfinStatus.setText("✗ Connection failed");
                    jellyfinStatus.setStyle("-fx-text-fill: #e05260;");
                    jellyfinConnBtn.setDisable(false);
                });
            }
        }, "jellyfin-auth").start();
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
        owner.onLaunch(streamUrl);
        launchJfBtn.setDisable(true);
        launchJfBtn.setText("MPV Launched ✓");
    }

    // ─── UI Helpers ───────────────────────────────────────────────────────

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
