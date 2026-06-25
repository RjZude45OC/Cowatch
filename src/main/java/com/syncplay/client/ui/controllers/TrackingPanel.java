package com.syncplay.client.ui.controllers;

import com.syncplay.client.tracking.AniListClient;
import com.syncplay.client.ui.MainWindow;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Settings panel for AniList token.
 * Sits in the left column below the offset panel.
 */
public class TrackingPanel {

    private static final Path PREFS_FILE = resolvePrefsFile();

    private final MainWindow owner;
    private final VBox       node = new VBox(8);

    private final PasswordField aniListTokenField = new PasswordField();
    private final Label         aniListStatus     = new Label("Not verified");
    private final Button        aniListVerifyBtn  = new Button("Verify");
    private final Button        saveBtn           = new Button("Save");

    private AniListClient aniListClient;

    public TrackingPanel(MainWindow owner) {
        this.owner = owner;
        loadPrefs();
        build();
    }

    private void build() {
        node.setPadding(new Insets(0));

        aniListTokenField.setPromptText("Paste AniList OAuth token…");
        aniListTokenField.setMaxWidth(Double.MAX_VALUE);

        aniListStatus.getStyleClass().add("muted");

        aniListVerifyBtn.setOnAction(e -> verifyAniList());
        aniListVerifyBtn.getStyleClass().add("secondary");

        saveBtn.getStyleClass().add("accent");
        saveBtn.setMaxWidth(Double.MAX_VALUE);
        saveBtn.setOnAction(e -> savePrefs());

        VBox aniListBox = new VBox(6,
                label("AniList OAuth Token"),
                aniListTokenField,
                aniListVerifyBtn,
                aniListStatus,
                hint("Get token: anilist.co/settings/developer\n"
                        + "Create a client → use Implicit grant → paste token here."));
        aniListBox.getStyleClass().add("card");

        Label heading = new Label("Tracking");
        heading.getStyleClass().add("heading");

        node.getChildren().addAll(heading, aniListBox, saveBtn);
    }

    // ── Verify ───────────────────────────────────────────────────────────

    private void verifyAniList() {
        String token = aniListTokenField.getText().trim();
        if (token.isEmpty()) {
            setStatus("No token entered", "error");
            return;
        }
        aniListVerifyBtn.setDisable(true);
        setStatus("Verifying…", "pending");

        new Thread(() -> {
            AniListClient client = new AniListClient(token);
            String username = client.verifyToken();
            Platform.runLater(() -> {
                aniListVerifyBtn.setDisable(false);
                if (username != null) {
                    aniListClient = client;
                    setStatus("✓ Logged in as " + username, "ok");
                } else {
                    setStatus("✗ Invalid token", "error");
                }
            });
        }, "anilist-verify").start();
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public AniListClient getAniListClient() { return aniListClient; }

    // ── Prefs ─────────────────────────────────────────────────────────────

    private void savePrefs() {
        try {
            Properties p = new Properties();
            p.setProperty("anilist.token", aniListTokenField.getText().trim());

            Files.createDirectories(PREFS_FILE.getParent());
            try (OutputStream out = Files.newOutputStream(PREFS_FILE)) {
                p.store(out, "SyncPlay tracking settings");
            }
            saveBtn.setText("Saved ✓");
            new Thread(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                Platform.runLater(() -> saveBtn.setText("Save"));
            }, "save-reset").start();

        } catch (IOException ex) {
            owner.showError("Save Failed", "Could not save tracking settings:\n" + ex.getMessage());
        }
    }

    private void loadPrefs() {
        if (!Files.exists(PREFS_FILE)) return;
        try (InputStream in = Files.newInputStream(PREFS_FILE)) {
            Properties p = new Properties();
            p.load(in);
            String token = p.getProperty("anilist.token", "");
            aniListTokenField.setText(token);
            if (!token.isEmpty()) {
                aniListClient = new AniListClient(token);
            }
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
        return dir.resolve("syncplay-tracking.properties");
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        return l;
    }

    private Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("subhead");
        l.setWrapText(true);
        return l;
    }

    private void setStatus(String text, String state) {
        aniListStatus.setText(text);
        aniListStatus.getStyleClass().removeAll("status-ok", "status-error", "muted");
        aniListStatus.getStyleClass().add(switch (state) {
            case "ok"    -> "status-ok";
            case "error" -> "status-error";
            default      -> "muted";
        });
    }

    public VBox getNode() { return node; }
}