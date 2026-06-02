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
 * Switches content dynamically based on SyncMode:
 *   LOCAL_TO_LOCAL        → local file picker
 *   JELLYFIN_TO_JELLYFIN  → Jellyfin login + library browser
 *   LOCAL_TO_JELLYFIN     → local file picker (this user's side)
 */
public class MediaPanel {

    private final MainWindow owner;
    private final VBox node  = new VBox(12);
    private final VBox card  = new VBox(10);        // inner card that swaps content
    private final StackPane contentArea = new StackPane();

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
    private final TextField localFileField  = field("No file selected");
    private final Button    browseBtn       = new Button("Browse…");
    private final Button    launchLocalBtn  = new Button("▶  Launch in MPV");

    // ── State ─────────────────────────────────────────────────────────────
    private JellyfinClient jellyfinClient;
    private SyncMode       currentMode = SyncMode.JELLYFIN_TO_JELLYFIN;

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

        // Default to Jellyfin panel
        showJellyfinPanel();
    }

    // ─── Mode switch (called from MainWindow after Connect) ───────────────

    public void setMode(SyncMode mode) {
        currentMode = mode;
        if (mode == SyncMode.LOCAL_TO_LOCAL || mode == SyncMode.LOCAL_TO_JELLYFIN) {
            showLocalPanel();
        } else {
            showJellyfinPanel();
        }
    }

    /** Also allow switching before connect — wired to ConfigPanel toggle */
    public void previewMode(SyncMode mode) {
        setMode(mode);
    }

    private void showJellyfinPanel() {
        contentArea.getChildren().setAll(buildJellyfinPanel());
    }

    private void showLocalPanel() {
        contentArea.getChildren().setAll(buildLocalPanel());
    }

    // ─── Jellyfin Panel ───────────────────────────────────────────────────

    private VBox buildJellyfinPanel() {
        jellyfinPassField.setPromptText("password");
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
        loginBox.setStyle(
            "-fx-background-color: #252836;" +
            "-fx-border-color: #2e3148;" +
            "-fx-border-radius: 8px;" +
            "-fx-background-radius: 8px;" +
            "-fx-padding: 10;");

        // Library browser
        showsList.setPlaceholder(new Label("Connect to Jellyfin to browse"));
        showsList.setMinHeight(100);
        VBox.setVgrow(showsList, Priority.ALWAYS);
        showsList.setOnMouseClicked(e -> {
            JellyfinItem sel = showsList.getSelectionModel().getSelectedItem();
            if (sel != null) loadSeasons(sel.id());
        });

        seasonsList.setPlaceholder(new Label("Select a show above"));
        seasonsList.setMinHeight(80);
        seasonsList.setOnMouseClicked(e -> {
            JellyfinItem sel = seasonsList.getSelectionModel().getSelectedItem();
            if (sel != null) loadEpisodes(sel.id());
        });

        episodesList.setPlaceholder(new Label("Select a season above"));
        episodesList.setMinHeight(100);
        VBox.setVgrow(episodesList, Priority.ALWAYS);

        launchJfBtn.getStyleClass().add("green");
        launchJfBtn.setMaxWidth(Double.MAX_VALUE);
        launchJfBtn.setDisable(true);
        launchJfBtn.setOnAction(e -> handleJellyfinLaunch());
        episodesList.getSelectionModel().selectedItemProperty().addListener(
            (obs, old, now) -> launchJfBtn.setDisable(now == null));

        VBox browserBox = new VBox(6,
            sectionLabel("Shows / Movies"), showsList,
            sectionLabel("Seasons"),        seasonsList,
            sectionLabel("Episodes"),       episodesList,
            launchJfBtn
        );
        browserBox.setFillWidth(true);
        VBox.setVgrow(showsList,    Priority.ALWAYS);
        VBox.setVgrow(episodesList, Priority.ALWAYS);

        VBox panel = new VBox(10, loginBox, browserBox);
        panel.setFillWidth(true);
        VBox.setVgrow(browserBox, Priority.ALWAYS);
        return panel;
    }

    // ─── Local File Panel ─────────────────────────────────────────────────

    private VBox buildLocalPanel() {
        // Re-wire browse every time panel is rebuilt (mode can switch back)
        browseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select Media File");
            fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Video Files",
                    "*.mp4","*.mkv","*.avi","*.mov","*.flv","*.wmv","*.m4v","*.ts"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
            );
            // Use owner stage so dialog is on top
            File file = fc.showOpenDialog(
                node.getScene() != null ? node.getScene().getWindow() : null);
            if (file != null) {
                localFileField.setText(file.getAbsolutePath());
                launchLocalBtn.setDisable(false);
            }
        });

        localFileField.setEditable(false);
        localFileField.setMaxWidth(Double.MAX_VALUE);
        localFileField.setPromptText("Click Browse to pick a file…");

        launchLocalBtn.getStyleClass().add("green");
        launchLocalBtn.setMaxWidth(Double.MAX_VALUE);
        launchLocalBtn.setDisable(true); // disabled until file is picked
        launchLocalBtn.setOnAction(e -> handleLocalLaunch());

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
            fileRow,
            hint,
            launchLocalBtn
        );
        panel.setFillWidth(true);
        panel.setAlignment(Pos.TOP_LEFT);
        return panel;
    }

    // ─── Handlers ─────────────────────────────────────────────────────────

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

    /** Called back by MainWindow once MPV confirms it launched */
    public void onMpvLaunched(boolean success) {
        if (success) {
            launchLocalBtn.setText("MPV Running ✓");
        } else {
            launchLocalBtn.setDisable(false);
            launchLocalBtn.setText("▶  Launch in MPV");
        }
    }

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
                    jellyfinStatus.setText("✓ Connected — "
                        + (shows.size() + movies.size()) + " items");
                    jellyfinStatus.setStyle("-fx-text-fill: #2eca6a;");
                    jellyfinConnBtn.setText("Connected ✓");
                });
            } else {
                Platform.runLater(() -> {
                    jellyfinStatus.setText("✗ Connection failed — check URL / credentials");
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
        launchJfBtn.setDisable(true);
        launchJfBtn.setText("Launching…");
        owner.onLaunch(streamUrl);
    }

    // ─── UI helpers ───────────────────────────────────────────────────────

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
