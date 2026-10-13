package com.cowatch.client.ui.controllers;

import com.cowatch.client.model.*;
import com.cowatch.client.ui.MainWindow;
import com.cowatch.client.ui.Motion;
import com.cowatch.client.util.AppPrefs;

import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.IOException;
import java.util.Properties;

/**
 * Left-side configuration panel.
 * Sections:
 *  1. Sync mode selector
 *  2. Connection card: host, port, username  (room removed — server handles rooms)
 *  3. Playback: whether mpv starts playing or opens paused, and whether
 *     this client opens the episodes others in the room start
 *  4. Connect + Save buttons
 * Settings are persisted via {@link AppPrefs}, in the platform's per-user
 * configuration directory.
 */
public class ConfigPanel {

    private static final String PREFS_FILE = "cowatch.properties";

    private final MainWindow  owner;
    private final VBox        node = new VBox(8);

    private SyncMode          selectedMode = SyncMode.JELLYFIN;
    private final ToggleGroup modeGroup    = new ToggleGroup();

    private final TextField hostField = field("localhost");
    private final TextField portField = field("5555");
    private final TextField userField = field("User1");

    private final ComboBox<StartBehavior> startBox = new ComboBox<>();

    /** On by default: following the room is the point of joining one. */
    private final CheckBox followBox = new CheckBox("Open what others in the room play");

    private final Button connectBtn = new Button("Connect");
    private final Button saveBtn    = new Button("Save");

    public ConfigPanel(MainWindow owner) {
        this.owner = owner;
        loadPrefs();
        build();
    }

    private void build() {
        node.setPadding(new Insets(12));

        // ── 1. Mode selector ─────────────────────────────────────────
        HBox modeRow = new HBox(6);
        for (SyncMode m : SyncMode.values()) {
            ToggleButton tb = new ToggleButton(m.icon + "  " + m.label);
            tb.setToggleGroup(modeGroup);
            tb.setUserData(m);
            tb.getStyleClass().add("mode-btn");
            tb.setMaxWidth(Double.MAX_VALUE);
            tb.setWrapText(false);
            HBox.setHgrow(tb, Priority.ALWAYS);
            if (m == selectedMode) tb.setSelected(true);
            modeRow.getChildren().add(tb);
        }
        modeGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) { old.setSelected(true); return; }
            modeGroup.getToggles().forEach(t ->
                    ((ToggleButton) t).getStyleClass().remove("selected"));
            ((ToggleButton) now).getStyleClass().add("selected");
            selectedMode = (SyncMode) now.getUserData();
            owner.getMediaPanel().previewMode(selectedMode);
        });
        modeGroup.getToggles().stream()
                .filter(Toggle::isSelected).map(t -> (ToggleButton) t).findFirst()
                .ifPresent(tb -> tb.getStyleClass().add("selected"));

        VBox modeCard = card(heading("Sync Mode"), modeRow);

        // ── 2. Connection card ────────────────────────────────────────
        // Host + Port on one row, Username below full-width
        HBox hostRow = new HBox(8,
                labeled("Host", hostField),
                labeledFixed(portField));

        VBox connCard = card(
                heading("Connection"),
                hostRow,
                labeled("Username", userField));

        // ── 3. Playback card ──────────────────────────────────────────
        // Read at each launch, so a change applies to the next player opened.
        StartBehavior restored = startBox.getValue();
        startBox.getItems().setAll(StartBehavior.values());
        startBox.setValue(restored != null ? restored : StartBehavior.AUTO);
        Label startHint = new Label("In a room, starting paused lets everyone load the "
                + "episode before one person presses play.");
        startHint.getStyleClass().add("muted");
        startHint.setWrapText(true);

        followBox.setWrapText(true);
        Label followHint = new Label("When someone starts an episode, cowatch opens the same "
                + "one from your Jellyfin, local folder or Seanime library and jumps to "
                + "where they are.");
        followHint.getStyleClass().add("muted");
        followHint.setWrapText(true);

        VBox playCard = card(
                heading("Playback"),
                labeled("When mpv opens", startBox),
                startHint,
                followBox,
                followHint);

        // ── 4. Action row: Connect + Save ─────────────────────────────
        connectBtn.getStyleClass().add("accent");
        connectBtn.setMaxWidth(Double.MAX_VALUE);
        connectBtn.setOnAction(e -> handleConnect());
        HBox.setHgrow(connectBtn, Priority.ALWAYS);

        saveBtn.getStyleClass().add("secondary");
        saveBtn.setOnAction(e -> savePrefs());

        HBox actionRow = new HBox(8, connectBtn, saveBtn);
        actionRow.setFillHeight(true);

        node.getChildren().addAll(modeCard, connCard, playCard, actionRow);
    }

    // ── Handlers ─────────────────────────────────────────────────────────

    private void handleConnect() {
        String host = hostField.getText().trim();
        String user = userField.getText().trim();
        if (host.isEmpty()) {
            Motion.shake(hostField);
            owner.showError("Invalid Input", "Enter the address of the cowatch server.");
            return;
        }
        if (user.isEmpty()) {
            Motion.shake(userField);
            owner.showError("Invalid Input", "Enter a display name so the room knows who you are.");
            return;
        }
        try {
            int port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
            SessionConfig config = SessionConfig.builder()
                    .serverHost(host)
                    .serverPort(port)
                    .roomId("room1")
                    .username(user)
                    .mode(selectedMode)
                    .build();

            connectBtn.setDisable(true);
            connectBtn.setText("Connecting…");
            owner.onConnect(config);
        } catch (NumberFormatException ex) {
            Motion.shake(portField);
            owner.showError("Invalid Input", "Port must be a number from 1 to 65535.");
        } catch (IOException e) {
            owner.showError("Connection Failed", e.getMessage(), () -> {
                connectBtn.setDisable(false);
                connectBtn.setText("Connect");
            });
        }
    }

    public void onConnected()    { connectBtn.setText("Connected ✓"); }

    /** The sync mode picked here, including one restored from saved settings. */
    public SyncMode getSelectedMode() { return selectedMode; }

    /** Whether mpv should play or open paused, as chosen here. */
    public StartBehavior getStartBehavior() {
        StartBehavior b = startBox.getValue();
        return b != null ? b : StartBehavior.AUTO;
    }
    /** Whether to open the episode someone else in the room starts. */
    public boolean isFollowRoom() { return followBox.isSelected(); }

    public void onDisconnected() { connectBtn.setDisable(false); connectBtn.setText("Connect"); }

    // ── Preferences persistence ───────────────────────────────────────────

    private void savePrefs() {
        Properties p = new Properties();
        p.setProperty("host",     hostField.getText().trim());
        p.setProperty("port",     portField.getText().trim());
        p.setProperty("username", userField.getText().trim());
        p.setProperty("mode",     selectedMode.name());
        p.setProperty("start",    getStartBehavior().name());
        p.setProperty("follow",   String.valueOf(followBox.isSelected()));

        try {
            AppPrefs.save(PREFS_FILE, p, "cowatch settings");
            Motion.confirm(saveBtn, "Saved ✓");
        } catch (IOException ex) {
            owner.showError("Save Failed", "Could not save settings:\n" + ex.getMessage());
        }
    }

    private void loadPrefs() {
        Properties p = AppPrefs.load(PREFS_FILE);
        hostField.setText(p.getProperty("host",     hostField.getText()));
        portField.setText(p.getProperty("port",     portField.getText()));
        userField.setText(p.getProperty("username", userField.getText()));
        try {
            selectedMode = SyncMode.valueOf(p.getProperty("mode", selectedMode.name()));
        } catch (IllegalArgumentException ignored) {
            // Unknown mode in an older or hand-edited file — keep the default.
        }
        followBox.setSelected(!"false".equals(p.getProperty("follow", "true")));
        try {
            startBox.setValue(StartBehavior.valueOf(p.getProperty("start", "AUTO")));
        } catch (IllegalArgumentException ignored) {
            startBox.setValue(StartBehavior.AUTO);
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private VBox card(Node... children) {
        VBox c = new VBox(6, children);
        c.getStyleClass().add("card");
        return c;
    }

    private Label heading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("heading");
        return l;
    }

    /** Full-width labeled column. */
    private VBox labeled(String labelText, Control field) {
        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("muted");
        field.setMaxWidth(Double.MAX_VALUE);
        VBox col = new VBox(3, lbl, field);
        col.setFillWidth(true);
        HBox.setHgrow(col, Priority.ALWAYS);
        return col;
    }

    /** Fixed-width labeled column (e.g. Port). */
    private VBox labeledFixed(Control field) {
        Label lbl = new Label("Port");
        lbl.getStyleClass().add("muted");
        field.setPrefWidth(70);
        field.setMaxWidth(70);
        return new VBox(3, lbl, field);
    }

    private TextField field(String prompt) {
        TextField tf = new TextField();
        tf.setPromptText(prompt);
        tf.setText(prompt);
        return tf;
    }

    public VBox getNode() { return node; }
}