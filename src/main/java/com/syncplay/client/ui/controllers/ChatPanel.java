package com.syncplay.client.ui.controllers;

import com.syncplay.client.network.ServerConnection;

import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.json.JSONObject;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class ChatPanel {

    private final ServerConnection server;
    private final VBox node = new VBox(8);

    private final TextArea  chatArea = new TextArea();
    private final TextField input    = new TextField();
    private final Button    sendBtn  = new Button("Send");

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    public ChatPanel(ServerConnection server) {
        this.server = server;
        build();
    }

    private void build() {
        Label heading = new Label("Chat & Events");
        heading.getStyleClass().add("heading");

        chatArea.setEditable(false);
        chatArea.setWrapText(true);
        chatArea.getStyleClass().add("chat-area");
        // Let the text area grow to fill all available vertical space
        VBox.setVgrow(chatArea, Priority.ALWAYS);

        input.setPromptText("Type a message…");
        input.setOnAction(e -> handleSend());
        sendBtn.setOnAction(e -> handleSend());
        sendBtn.getStyleClass().add("accent");

        HBox inputRow = new HBox(8, input, sendBtn);
        HBox.setHgrow(input, Priority.ALWAYS);
        inputRow.setAlignment(Pos.CENTER);

        VBox card = new VBox(8, heading, chatArea, inputRow);
        card.getStyleClass().add("card");
        card.setFillWidth(true);
        VBox.setVgrow(chatArea, Priority.ALWAYS);
        // Card itself grows to fill the column
        VBox.setVgrow(card, Priority.ALWAYS);

        node.getChildren().add(card);
        node.setFillWidth(true);
        // VBox wrapper also grows
        VBox.setVgrow(node, Priority.ALWAYS);
    }

    private void handleSend() {
        String text = input.getText().trim();
        if (text.isEmpty()) return;
        if (server.isConnected()) server.sendChat(text);
        appendRaw("[you] " + text);
        input.clear();
    }

    public void appendRaw(String msg) {
        String time = LocalTime.now().format(TIME_FMT);
        chatArea.appendText("[" + time + "] " + msg + "\n");
    }

    public void appendMessage(String rawJson) {
        try {
            JSONObject obj  = new JSONObject(rawJson);
            String event    = obj.optString("event", "");
            String from     = obj.optString("from",  "?");
            String data     = obj.optString("data",  "");

            String line = switch (event) {
                case "chat"            -> "[" + from + "] " + data;
                case "play"            -> "▶ " + from + " pressed play at "  + formatPos(obj.optDouble("position", 0));
                case "pause"           -> "⏸ " + from + " paused at "        + formatPos(obj.optDouble("position", 0));
                case "seek"            -> "⏩ " + from + " seeked to "        + formatPos(obj.optDouble("position", 0));
                case "system","joined" -> "• " + data;
                case "error"           -> "⚠ " + data;
                default                -> rawJson;
            };
            appendRaw(line);
        } catch (Exception e) {
            appendRaw(rawJson);
        }
    }

    private String formatPos(double seconds) {
        int h = (int) seconds / 3600;
        int m = (int) (seconds % 3600) / 60;
        int s = (int) seconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    public VBox getNode() { return node; }
}