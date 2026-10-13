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
 * AniList token settings.
 *
 * <p>A tab in {@link com.cowatch.client.ui.SettingsPanel}. It used to be a
 * collapsible card in the main window's left column, which meant a form that is
 * filled in once per install occupied permanent screen space; the collapsible
 * wrapper it needed for that is gone with it.
 */
public class TrackingPanel {

    private static final String PREFS_FILE = "cowatch-tracking.properties";

    private final MainWindow owner;
    private final VBox       node = new VBox(10);

    private final PasswordField aniListTokenField = new PasswordField();
    private final Label         aniListStatus     = new Label("Not verified");
    private final Button        aniListVerifyBtn  = new Button("Verify");
    private final Button        saveBtn           = new Button("Save");

    private AniListClient aniListClient;

    public TrackingPanel(MainWindow owner) {
        this.owner = owner;
        build();
        // After build(), so the status label exists by the time a stored token
        // starts verifying against it.
        loadPrefs();
    }

    private void build() {
        node.setPadding(new Insets(12));
        node.setFillWidth(true);

        aniListTokenField.setPromptText("Paste AniList OAuth token");
        aniListTokenField.setMaxWidth(Double.MAX_VALUE);

        aniListStatus.getStyleClass().add("muted");
        aniListStatus.setWrapText(true);

        aniListVerifyBtn.getStyleClass().add("secondary");
        aniListVerifyBtn.setOnAction(e -> verifyAniList());

        saveBtn.getStyleClass().add("accent");
        saveBtn.setOnAction(e -> savePrefs());

        HBox actions = new HBox(8, saveBtn, aniListVerifyBtn);

        VBox card = new VBox(8,
                heading("AniList"),
                label("OAuth token"),
                aniListTokenField,
                actions,
                aniListStatus,
                hint("Get a token at anilist.co/settings/developer: create a client, "
                        + "choose the Implicit grant, then paste the token here. Progress "
                        + "is reported once an episode passes 80% watched."));
        card.getStyleClass().add("card");

        node.getChildren().add(card);
    }

    // --- Verify -----------------------------------------------------------

    private void verifyAniList() {
        String token = aniListTokenField.getText().trim();
        if (token.isEmpty()) {
            setStatus("No token entered", "error");
            return;
        }
        aniListVerifyBtn.setDisable(true);
        setStatus("Verifying...", "pending");

        Async.run("anilist-verify", () -> {
            AniListClient client = new AniListClient(token);
            String username = client.verifyToken();
            Platform.runLater(() -> {
                aniListVerifyBtn.setDisable(false);
                if (username != null) {
                    aniListClient = client;
                    setStatus("Signed in as " + username, "ok");
                } else {
                    aniListClient = null;
                    setStatus("Invalid token", "error");
                }
            });
        });
    }

    // --- Accessors --------------------------------------------------------

    /** The verified AniList client, or null while no token has checked out. */
    public AniListClient getAniListClient() { return aniListClient; }

    // --- Prefs ------------------------------------------------------------

    private void savePrefs() {
        Properties p = new Properties();
        p.setProperty("anilist.token", aniListTokenField.getText().trim());

        try {
            AppPrefs.save(PREFS_FILE, p, "cowatch tracking settings");
            Motion.confirm(saveBtn, "Saved");
        } catch (IOException ex) {
            owner.showError("Save Failed", "Could not save tracking settings:\n" + ex.getMessage());
        }
    }

    private void loadPrefs() {
        String token = AppPrefs.load(PREFS_FILE).getProperty("anilist.token", "");
        if (token.isEmpty()) return;
        aniListTokenField.setText(token);
        // A stored token is only trusted once AniList confirms it - verify
        // rather than assuming, so an expired token shows as invalid instead
        // of failing silently on the first episode update.
        verifyAniList();
    }

    // --- UI helpers -------------------------------------------------------

    private Label heading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("heading");
        return l;
    }

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
