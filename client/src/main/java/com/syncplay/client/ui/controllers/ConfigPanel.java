package com.syncplay.client.ui.controllers;

import com.syncplay.client.model.*;
import com.syncplay.client.ui.MainWindow;

import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * Left-side configuration panel.
 *
 * Sections:
 *  1. Sync mode selector (3 toggle buttons)
 *  2. Sync server config (host, port)
 *  3. Session config (room, username)
 *  4. Connect button
 */
public class ConfigPanel {

    private final MainWindow owner;
    private final VBox node = new VBox(12);

    // Mode
    private SyncMode selectedMode = SyncMode.JELLYFIN_TO_JELLYFIN;
    private final ToggleGroup modeGroup = new ToggleGroup();

    // Server fields
    private final TextField hostField = field("localhost",     200);
    private final TextField portField = field("5555",           60);

    // Session fields
    private final TextField roomField = field("room1",         140);
    private final TextField userField = field("User1",         140);

    // Connect button
    private final Button connectBtn = new Button("Connect to Server");

    public ConfigPanel(MainWindow owner) {
        this.owner = owner;
        build();
    }

    private void build() {
        node.setPadding(new Insets(0));

        // ── 1. Mode selector ──────────────────────────────────────────────
        Label modeHeading = heading("Sync Mode");
        HBox modeRow = new HBox(8);
        for (SyncMode m : SyncMode.values()) {
            ToggleButton tb = new ToggleButton(m.icon + "  " + m.label);
            tb.setToggleGroup(modeGroup);
            tb.setUserData(m);
            tb.getStyleClass().add("mode-btn");
            tb.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(tb, Priority.ALWAYS);
            if (m == selectedMode) tb.setSelected(true);
            modeRow.getChildren().add(tb);
        }
        modeGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) { old.setSelected(true); return; }
            // Update style classes
            modeGroup.getToggles().forEach(t ->
                ((ToggleButton) t).getStyleClass().remove("selected"));
            ((ToggleButton) now).getStyleClass().add("selected");
            selectedMode = (SyncMode) now.getUserData();
        });
        // Set initial selected style
        modeGroup.getToggles().stream()
            .filter(Toggle::isSelected)
            .map(t -> (ToggleButton) t)
            .findFirst()
            .ifPresent(tb -> tb.getStyleClass().add("selected"));

        Label modeDesc = new Label(selectedMode.description);
        modeDesc.setWrapText(true);
        modeDesc.getStyleClass().add("subhead");
        modeGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now != null) modeDesc.setText(((SyncMode) now.getUserData()).description);
        });

        VBox modeCard = card(modeHeading, modeRow, modeDesc);

        // ── 2. Server config ──────────────────────────────────────────────
        HBox serverRow = new HBox(8,
            labeled("Host", hostField),
            labeled("Port", portField));

        VBox serverCard = card(heading("Relay Server"), serverRow);

        // ── 3. Session config ─────────────────────────────────────────────
        HBox sessionRow = new HBox(8,
            labeled("Room", roomField),
            labeled("Username", userField));

        VBox sessionCard = card(heading("Session"), sessionRow);

        // ── 4. Connect button ─────────────────────────────────────────────
        connectBtn.getStyleClass().add("accent");
        connectBtn.setMaxWidth(Double.MAX_VALUE);
        connectBtn.setPadding(new Insets(10, 0, 10, 0));
        connectBtn.setOnAction(e -> handleConnect());

        node.getChildren().addAll(modeCard, serverCard, sessionCard, connectBtn);
    }

    private void handleConnect() {
        try {
            int port = Integer.parseInt(portField.getText().trim());
            SessionConfig config = SessionConfig.builder()
                .serverHost(hostField.getText().trim())
                .serverPort(port)
                .roomId(roomField.getText().trim())
                .username(userField.getText().trim())
                .mode(selectedMode)
                .build();

            connectBtn.setDisable(true);
            connectBtn.setText("Connecting…");
            owner.onConnect(config);

        } catch (NumberFormatException ex) {
            owner.showError("Port must be a number.");
        }
    }

    public void onConnected() {
        connectBtn.setText("Connected ✓");
        // keep disabled — user should disconnect via close
    }

    public SyncMode getSelectedMode() { return selectedMode; }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private VBox card(Node... children) {
        VBox card = new VBox(8, children);
        card.getStyleClass().add("card");
        return card;
    }

    private Label heading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("heading");
        return l;
    }

    private HBox labeled(String labelText, Control field) {
        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("muted");
        lbl.setMinWidth(60);
        VBox col = new VBox(4, lbl, field);
        HBox.setHgrow(col, Priority.ALWAYS);
        field.setMaxWidth(Double.MAX_VALUE);
        return new HBox(col);
    }

    private TextField field(String prompt, double prefWidth) {
        TextField tf = new TextField();
        tf.setPromptText(prompt);
        tf.setText(prompt);
        tf.setPrefWidth(prefWidth);
        return tf;
    }

    public VBox getNode() { return node; }
}
