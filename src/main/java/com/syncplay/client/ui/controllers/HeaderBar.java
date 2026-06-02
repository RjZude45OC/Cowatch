package com.syncplay.client.ui.controllers;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

public class HeaderBar {

    private final HBox   node        = new HBox(10);
    private final Label  statusDot   = new Label("●");
    private final Label  statusLabel = new Label("Disconnected");
    private final Label  roomLabel   = new Label("");

    public HeaderBar() {
        Label logo = new Label("▶  SyncPlay");
        logo.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #7c6af7;");

        statusDot.getStyleClass().addAll("status-dot", "disconnected");
        statusLabel.setStyle("-fx-text-fill: #8b8fa8; -fx-font-size: 12px;");
        roomLabel.setStyle("-fx-text-fill: #8b8fa8; -fx-font-size: 12px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox status = new HBox(5, statusDot, statusLabel, roomLabel);
        status.setAlignment(Pos.CENTER_RIGHT);

        node.getChildren().addAll(logo, spacer, status);
        node.setPadding(new Insets(12, 16, 12, 16));
        node.setAlignment(Pos.CENTER_LEFT);
        node.setStyle("-fx-background-color: #1a1d27; -fx-border-color: #2e3148; -fx-border-width: 0 0 1 0;");
    }

    public void setConnected(String host, String room) {
        statusDot.getStyleClass().removeAll("disconnected", "loading");
        statusDot.getStyleClass().add("connected");
        statusLabel.setText("Connected to " + host);
        roomLabel.setText("  |  Room: " + room);
    }

    public void setDisconnected() {
        statusDot.getStyleClass().removeAll("connected", "loading");
        statusDot.getStyleClass().add("disconnected");
        statusLabel.setText("Disconnected");
        roomLabel.setText("");
    }

    public HBox getNode() { return node; }
}
