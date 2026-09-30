package com.cowatch.client.ui.controllers;

import com.cowatch.client.ui.MainWindow;
import com.cowatch.client.ui.Motion;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

/**
 * Playback controls panel.
 *  [ ▶ Play ] [ ⏸ Pause ]
 *  [ ⏮ -30s ] [ ← -5s ] [ +5s → ] [ +30s ⏭ ]
 *  Seek to: [___]  [Go]
 *  Position: 00:14:32
 */
public class PlaybackControls {

    /** How often the position readout refreshes while mpv is playing. */
    private static final Duration TICK = Duration.millis(500);

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
    private final Label     posLabel  = new Label("00:00:00");

    /** Drives posLabel. Runs only while the controls are enabled. */
    private final Timeline positionTicker =
            new Timeline(new KeyFrame(TICK, e -> refreshPosition()));

    public PlaybackControls(MainWindow owner) {
        this.owner = owner;
        positionTicker.setCycleCount(Animation.INDEFINITE);
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
        for (Button b : new Button[] { back30Btn, back5Btn, fwd5Btn, fwd30Btn }) {
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
        }

        back30Btn.setOnAction(e -> owner.doSkipBackward(30));
        back5Btn.setOnAction(e  -> owner.doSkipBackward(5));
        fwd5Btn.setOnAction(e   -> owner.doSkipForward(5));
        fwd30Btn.setOnAction(e  -> owner.doSkipForward(30));

        HBox skipRow = new HBox(6, back30Btn, back5Btn, fwd5Btn, fwd30Btn);

        // ── Manual seek row ───────────────────────────────────────────
        seekField.setPromptText("1:23:45 or 83");
        seekField.setPrefWidth(110);
        seekField.setOnAction(e -> handleSeek());
        seekBtn.setOnAction(e   -> handleSeek());

        Label seekLabel = new Label("Seek to");
        seekLabel.getStyleClass().add("muted");

        HBox seekRow = new HBox(8, seekLabel, seekField, seekBtn);
        seekRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(seekField, Priority.ALWAYS);
        seekField.setMaxWidth(Double.MAX_VALUE);

        // ── Position readout ──────────────────────────────────────────
        posLabel.getStyleClass().add("position-value");
        posLabel.setMaxWidth(Double.MAX_VALUE);
        posLabel.setAlignment(Pos.CENTER);

        VBox card = new VBox(8, heading, playRow, skipRow, seekRow, posLabel);
        card.getStyleClass().add("card");
        VBox.setVgrow(card, Priority.ALWAYS);
        node.getChildren().add(card);
        VBox.setVgrow(node, Priority.ALWAYS);
    }

    // ── Seeking ───────────────────────────────────────────────────────────

    private void handleSeek() {
        Double seconds = parseTimecode(seekField.getText());
        if (seconds == null) {
            Motion.shake(seekField);
            owner.showError("Invalid Position",
                    "Enter a position as seconds (83) or as a timecode (1:23 or 1:23:45).");
            return;
        }
        owner.doSeek(seconds);
    }

    /**
     * Parses a seek target as plain seconds or as {@code mm:ss} / {@code h:mm:ss}.
     *
     * @return the position in seconds, or null if the text is not a valid time
     */
    static Double parseTimecode(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return null;

        String[] parts = text.split(":");
        if (parts.length > 3) return null;

        try {
            double seconds = 0;
            for (String part : parts) {
                double value = Double.parseDouble(part.trim());
                if (value < 0) return null;
                seconds = seconds * 60 + value;
            }
            return seconds;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ── Position readout ──────────────────────────────────────────────────

    private void refreshPosition() {
        posLabel.setText(formatTime(owner.getPosition()));
    }

    private static String formatTime(double seconds) {
        if (seconds < 0 || Double.isNaN(seconds)) seconds = 0;
        long total = (long) seconds;
        return String.format("%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
    }

    // ── Enable / disable ──────────────────────────────────────────────────

    /**
     * Enables or disables every control, and starts or stops the position
     * ticker with them — a stopped player has nothing to report, and leaving
     * the timeline running would keep waking the FX thread for no reason.
     */
    public void setEnabled(boolean enabled) {
        playBtn.setDisable(!enabled);
        pauseBtn.setDisable(!enabled);
        back5Btn.setDisable(!enabled);
        fwd5Btn.setDisable(!enabled);
        back30Btn.setDisable(!enabled);
        fwd30Btn.setDisable(!enabled);
        seekBtn.setDisable(!enabled);
        seekField.setDisable(!enabled);

        if (enabled) {
            positionTicker.play();
        } else {
            positionTicker.stop();
            posLabel.setText("00:00:00");
        }
    }

    public VBox getNode() { return node; }
}
