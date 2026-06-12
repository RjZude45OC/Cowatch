package com.syncplay.client.ui.controllers;

import com.syncplay.client.ui.MainWindow;

import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * Playback controls panel.
 *  [ ⏮ -30s ] [ ← -5s ] [ ▶ Play ] [ ⏸ Pause ] [ +5s → ] [ +30s ⏭ ]
 *  Seek to: [___] s  [Go]
 *  Position: 00:14:32
 */
public class PlaybackControls {

    private final MainWindow owner;
    private final VBox node = new VBox(10);

    private final Button playBtn    = new Button("▶  Play");
    private final Button pauseBtn   = new Button("⏸  Pause");
    private final Button back5Btn   = new Button("← 5s");
    private final Button fwd5Btn    = new Button("5s →");
    private final Button back30Btn  = new Button("⏮ 30s");
    private final Button fwd30Btn   = new Button("30s ⏭");

    private final TextField seekField = new TextField();
    private final Button    seekBtn   = new Button("Go");
    private final Label     posLabel  = new Label("Position: 00:00:00");

    public PlaybackControls(MainWindow owner) {
        this.owner = owner;
        build();
        setEnabled(false);
    }

    private void build() {
        Label heading = new Label("Playback");
        heading.getStyleClass().add("heading");

        // ── Main controls row ──────────────────────────────────────────
        playBtn.getStyleClass().add("green");
        pauseBtn.getStyleClass().add("red");

        playBtn.setMaxWidth(Double.MAX_VALUE);
        pauseBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(playBtn,  Priority.ALWAYS);
        HBox.setHgrow(pauseBtn, Priority.ALWAYS);

        playBtn.setOnAction(e  -> owner.doPlay());
        pauseBtn.setOnAction(e -> owner.doPause());

        HBox playRow = new HBox(8, playBtn, pauseBtn);

        // ── Skip buttons row ──────────────────────────────────────────
        back30Btn.setMaxWidth(Double.MAX_VALUE);
        back5Btn.setMaxWidth(Double.MAX_VALUE);
        fwd5Btn.setMaxWidth(Double.MAX_VALUE);
        fwd30Btn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(back30Btn, Priority.ALWAYS);
        HBox.setHgrow(back5Btn,  Priority.ALWAYS);
        HBox.setHgrow(fwd5Btn,   Priority.ALWAYS);
        HBox.setHgrow(fwd30Btn,  Priority.ALWAYS);

        back30Btn.setOnAction(e -> owner.doSkipBackward(30));
        back5Btn.setOnAction(e  -> owner.doSkipBackward(5));
        fwd5Btn.setOnAction(e   -> owner.doSkipForward(5));
        fwd30Btn.setOnAction(e  -> owner.doSkipForward(30));

        HBox skipRow = new HBox(6, back30Btn, back5Btn, fwd5Btn, fwd30Btn);

        // ── Manual seek row ───────────────────────────────────────────
        seekField.setPromptText("seconds");
        seekField.setPrefWidth(70);
        seekField.setOnAction(e -> handleSeek());
        seekBtn.setOnAction(e   -> handleSeek());

        HBox seekRow = new HBox(8,
            new Label("Seek to (s):"), seekField, seekBtn);
        seekRow.setAlignment(Pos.CENTER_LEFT);

        // ── Position label ────────────────────────────────────────────
        posLabel.getStyleClass().add("subhead");

        VBox card = new VBox(8, heading, playRow, skipRow, seekRow, posLabel);
        card.getStyleClass().add("card");
        VBox.setVgrow(card, Priority.ALWAYS);
        node.getChildren().add(card);
        VBox.setVgrow(node, Priority.ALWAYS);
    }

    private void handleSeek() {
        try {
            double pos = Double.parseDouble(seekField.getText().trim());
            owner.doSeek(pos);
        } catch (NumberFormatException ex) {
            owner.showError("Seek position must be a number (seconds).");
        }
    }

    public void setEnabled(boolean enabled) {
        playBtn.setDisable(!enabled);
        pauseBtn.setDisable(!enabled);
        back5Btn.setDisable(!enabled);
        fwd5Btn.setDisable(!enabled);
        back30Btn.setDisable(!enabled);
        fwd30Btn.setDisable(!enabled);
        seekBtn.setDisable(!enabled);
        seekField.setDisable(!enabled);
    }

    public VBox getNode() { return node; }
}
