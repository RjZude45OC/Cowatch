package com.cowatch.client.ui.controllers;

import com.cowatch.client.tracking.AniListClient;
import com.cowatch.client.ui.MainWindow;
import com.cowatch.client.ui.Motion;
import com.cowatch.client.util.AppPrefs;
import com.cowatch.client.util.Async;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.IOException;
import java.util.Properties;

/**
 * Settings panel for the AniList token.
 * Sits in the left column below the offset panel.
 */
public class TrackingPanel {

    private static final String PREFS_FILE = "cowatch-tracking.properties";

    private final MainWindow owner;
    private final VBox       node = new VBox(8);

    private final PasswordField aniListTokenField = new PasswordField();
    private final Label         aniListStatus     = new Label("Not verified");
    private final Button        aniListVerifyBtn  = new Button("Verify");
    private final Button        saveBtn           = new Button("Save");

    // Wraps the token entry card so it can be collapsed once a token is
    // verified — there's no need to keep the form expanded and taking up
    // space in the left column after that.
    private TitledPane aniListPane;

    private AniListClient aniListClient;

    public TrackingPanel(MainWindow owner) {
        this.owner = owner;
        build();
        // After build(), so the status label and collapsible pane exist by the
        // time a stored token starts verifying against them.
        loadPrefs();
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
                hint("Get a token at anilist.co/settings/developer — create a "
                        + "client, choose the Implicit grant, then paste the token here."));
        aniListBox.getStyleClass().add("card");

        aniListPane = new TitledPane("AniList Tracking", aniListBox);
        aniListPane.setAnimated(true);
        aniListPane.getStyleClass().add("anilist-pane");
        // Starts expanded; verifyAniList() collapses it once a token checks out.
        aniListPane.setExpanded(true);

        Label heading = new Label("Tracking");
        heading.getStyleClass().add("heading");

        node.getChildren().addAll(heading, aniListPane, saveBtn);
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

        Async.run("anilist-verify", () -> {
            AniListClient client = new AniListClient(token);
            String username = client.verifyToken();
            Platform.runLater(() -> {
                aniListVerifyBtn.setDisable(false);
                if (username != null) {
                    aniListClient = client;
                    setStatus("✓ Logged in as " + username, "ok");
                    // Verified — collapse the form to save space.
                    aniListPane.setExpanded(false);
                } else {
                    aniListClient = null;
                    setStatus("✗ Invalid token", "error");
                }
            });
        });
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    /** The verified AniList client, or null while no token has checked out. */
    public AniListClient getAniListClient() { return aniListClient; }

    // ── Prefs ─────────────────────────────────────────────────────────────

    private void savePrefs() {
        Properties p = new Properties();
        p.setProperty("anilist.token", aniListTokenField.getText().trim());

        try {
            AppPrefs.save(PREFS_FILE, p, "cowatch tracking settings");
            Motion.confirm(saveBtn, "Saved ✓");
        } catch (IOException ex) {
            owner.showError("Save Failed", "Could not save tracking settings:\n" + ex.getMessage());
        }
    }

    private void loadPrefs() {
        String token = AppPrefs.load(PREFS_FILE).getProperty("anilist.token", "");
        if (token.isEmpty()) return;
        aniListTokenField.setText(token);
        // A stored token is only trusted once AniList confirms it — verify
        // rather than assuming, so an expired token shows as invalid instead
        // of failing silently on the first episode update.
        verifyAniList();
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
        aniListStatus.getStyleClass().removeAll(
                "status-ok", "status-error", "status-pending", "muted");
        aniListStatus.getStyleClass().add(switch (state) {
            case "ok"      -> "status-ok";
            case "error"   -> "status-error";
            case "pending" -> "status-pending";
            default        -> "muted";
        });
    }

    public VBox getNode() { return node; }
}
