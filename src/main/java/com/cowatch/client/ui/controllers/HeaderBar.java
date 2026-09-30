package com.cowatch.client.ui.controllers;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;

/**
 * The single always-visible chrome of the app.
 *
 * <p>Everything that used to occupy a permanent left-hand column now hangs off
 * this bar: the gear opens the settings window, the speech bubble toggles the
 * chat column, and connecting happens through the one button that is relevant
 * at the time. Only the controls that apply to the current state are shown at
 * all - a disconnected client has nothing to disconnect from, and a connected
 * one has no reason to offer Connect again.
 */
public class HeaderBar {

    private final HBox   node          = new HBox(8);
    private final Label  statusDot     = new Label("\u25CF");
    private final Label  statusLabel   = new Label("Not connected");
    // Ellipsis: this one opens the settings window, it does not connect.
    private final Button connectBtn    = new Button("Connect…");
    private final Button chatBtn        = iconButton("\uD83D\uDCAC", "Show chat");
    private final Button settingsBtn    = iconButton("\u2699", "Settings");
    private final Button disconnectBtn  = new Button("Disconnect");

    private Runnable onDisconnect;
    private Runnable onConnect;
    private Runnable onToggleChat;
    private Runnable onOpenSettings;

    public HeaderBar() {
        Label logo = new Label("\u25B6  cowatch");
        logo.getStyleClass().add("header-logo");

        statusDot.getStyleClass().addAll("status-dot", "disconnected");
        statusLabel.getStyleClass().add("header-status");

        connectBtn.getStyleClass().add("accent");
        connectBtn.setOnAction(e -> fire(onConnect));

        disconnectBtn.getStyleClass().add("disconnect-btn");
        disconnectBtn.setOnAction(e -> fire(onDisconnect));

        chatBtn.setOnAction(e -> fire(onToggleChat));
        settingsBtn.setOnAction(e -> fire(onOpenSettings));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox status = new HBox(6, statusDot, statusLabel);
        status.setAlignment(Pos.CENTER_LEFT);
        status.getStyleClass().add("header-status-group");

        node.getChildren().addAll(
                logo, status, spacer, connectBtn, disconnectBtn, chatBtn, settingsBtn);
        node.setPadding(new Insets(8, 12, 8, 14));
        node.setAlignment(Pos.CENTER_LEFT);
        node.getStyleClass().add("header-bar");

        setDisconnected();
    }

    // --- State ------------------------------------------------------------

    public void setConnected(String host, String room) {
        statusDot.getStyleClass().removeAll("disconnected", "loading");
        statusDot.getStyleClass().add("connected");
        statusLabel.setText(host + " \u00B7 " + room);
        show(connectBtn, false);
        show(disconnectBtn, true);
    }

    public void setDisconnected() {
        statusDot.getStyleClass().removeAll("connected", "loading");
        statusDot.getStyleClass().add("disconnected");
        statusLabel.setText("Not connected");
        show(connectBtn, true);
        show(disconnectBtn, false);
    }

    /** Marks the chat toggle as active while the chat column is on screen. */
    public void setChatShown(boolean shown) {
        chatBtn.getStyleClass().remove("icon-btn-active");
        if (shown) chatBtn.getStyleClass().add("icon-btn-active");
        chatBtn.setTooltip(new Tooltip(shown ? "Hide chat" : "Show chat"));
        if (shown) setChatUnread(false);
    }

    /**
     * Dots the chat toggle when a message arrives while the column is hidden -
     * without it, hiding chat by default would silently swallow activity.
     */
    public void setChatUnread(boolean unread) {
        chatBtn.getStyleClass().remove("icon-btn-unread");
        if (unread) chatBtn.getStyleClass().add("icon-btn-unread");
    }

    // --- Wiring -----------------------------------------------------------

    public void setOnDisconnect(Runnable handler)   { this.onDisconnect   = handler; }
    public void setOnConnect(Runnable handler)      { this.onConnect      = handler; }
    public void setOnToggleChat(Runnable handler)   { this.onToggleChat   = handler; }
    public void setOnOpenSettings(Runnable handler) { this.onOpenSettings = handler; }

    // --- Helpers ----------------------------------------------------------

    private static void fire(Runnable handler) {
        if (handler != null) handler.run();
    }

    /** Hides a control and stops it reserving layout space at the same time. */
    private static void show(Button button, boolean visible) {
        button.setVisible(visible);
        button.setManaged(visible);
    }

    private static Button iconButton(String glyph, String tip) {
        Button b = new Button(glyph);
        b.getStyleClass().add("icon-btn");
        b.setTooltip(new Tooltip(tip));
        return b;
    }

    public HBox getNode() { return node; }
}
