package com.cowatch.client.ui.controllers;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;

public class HeaderBar {

    private final HBox   node           = new HBox(10);
    private final Label  statusDot      = new Label("●");
    private final Label  statusLabel    = new Label("Disconnected");
    private final Label  roomLabel      = new Label("");
    private final Button disconnectBtn  = new Button("Disconnect");

    private Runnable onDisconnect;

    public HeaderBar() {
        Label logo = new Label("▶  cowatch");
        logo.getStyleClass().add("header-logo");

        statusDot.getStyleClass().addAll("status-dot", "disconnected");
        statusLabel.getStyleClass().add("header-status");
        roomLabel.getStyleClass().add("header-status");

        disconnectBtn.getStyleClass().add("disconnect-btn");
        disconnectBtn.setVisible(false);   // hidden while disconnected
        disconnectBtn.setManaged(false);   // takes no space when hidden
        disconnectBtn.setOnAction(e -> {
            if (onDisconnect != null) onDisconnect.run();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox status = new HBox(5, statusDot, statusLabel, roomLabel);
        status.setAlignment(Pos.CENTER_RIGHT);

        node.getChildren().addAll(logo, spacer, status, disconnectBtn);
        node.setPadding(new Insets(12, 16, 12, 16));
        node.setAlignment(Pos.CENTER_LEFT);
        node.getStyleClass().add("header-bar");
    }

    public void setConnected(String host, String room) {
        statusDot.getStyleClass().removeAll("disconnected", "loading");
        statusDot.getStyleClass().add("connected");
        statusLabel.setText("Connected to " + host);
        roomLabel.setText("  |  Room: " + room);
        disconnectBtn.setVisible(true);
        disconnectBtn.setManaged(true);
    }

    public void setDisconnected() {
        statusDot.getStyleClass().removeAll("connected", "loading");
        statusDot.getStyleClass().add("disconnected");
        statusLabel.setText("Disconnected");
        roomLabel.setText("");
        disconnectBtn.setVisible(false);
        disconnectBtn.setManaged(false);
    }

    /** Called when user clicks Disconnect. */
    public void setOnDisconnect(Runnable handler) {
        this.onDisconnect = handler;
    }

    public HBox getNode() { return node; }
}