package com.cowatch.client.ui.controllers;

import com.cowatch.client.ui.MainWindow;

import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * Playback controls + mismatch offset panel.
 *
 * Controls:
 *   [ ⏮ -30s ] [ ← -5s ] [ ▶ Play ] [ ⏸ Pause ] [ +5s → ] [ +30s ⏭ ]
 *   Seek to: [___] s  [Go]
 *
 * Mismatch offset:
 *   Your file has a 90s cold-open others skip?
 *   Set offset = +90 → your position is adjusted automatically when syncing.
 */
public class PlaybackControls {

    private final MainWindow owner;
    private final VBox node = new VBox(10);

    // Playback buttons
    private final Button    playBtn   = new Button("▶  Play");
    private final Button    pauseBtn  = new Button("⏸  Pause");
    private final Button    back5Btn  = new Button("← 5s");
    private final Button    fwd5Btn   = new Button("5s →");
    private final Button    back30Btn = new Button("⏮ 30s");
    private final Button    fwd30Btn  = new Button("30s ⏭");
    private final TextField seekField = new TextField();
    private final Button    seekBtn   = new Button("Go");
    private final Label     posLabel  = new Label("Position: 00:00:00");

    // Mismatch offset
    private final TextField offsetField   = new TextField("0");
    private final Button    applyOffsetBtn = new Button("Apply");
    private final Button    detectOffsetBtn = new Button("Auto-detect…");
    private final Label     offsetStatus  = new Label("No offset");

    public PlaybackControls(MainWindow owner) {
        this.owner = owner;
        build();
        setEnabled(false);
    }

    private void build() {
        // ── Playback card ──────────────────────────────────────────────────
        playBtn.getStyleClass().add("green");
        pauseBtn.getStyleClass().add("red");
        playBtn.setMaxWidth(Double.MAX_VALUE);
        pauseBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(playBtn,  Priority.ALWAYS);
        HBox.setHgrow(pauseBtn, Priority.ALWAYS);
        playBtn.setOnAction(e  -> owner.doPlay());
        pauseBtn.setOnAction(e -> owner.doPause());
        HBox playRow = new HBox(8, playBtn, pauseBtn);

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

        seekField.setPromptText("seconds");
        seekField.setPrefWidth(80);
        seekField.setOnAction(e -> handleSeek());
        seekBtn.setOnAction(e   -> handleSeek());
        HBox seekRow = new HBox(8, new Label("Seek to (s):"), seekField, seekBtn);
        seekRow.setAlignment(Pos.CENTER_LEFT);

        posLabel.getStyleClass().add("subhead");

        VBox playCard = card("Playback", playRow, skipRow, seekRow, posLabel);

        // ── Mismatch offset card ───────────────────────────────────────────
        Label hint = new Label(
            "Use this if your file has extra content others don't have.\n" +
            "Example: your file has a 90s intro others skipped → set +90.\n" +
            "Your position will be adjusted automatically when syncing.");
        hint.setWrapText(true);
        hint.getStyleClass().add("subhead");

        offsetField.setPromptText("seconds (e.g. 90 or -30)");
        offsetField.setMaxWidth(Double.MAX_VALUE);

        applyOffsetBtn.getStyleClass().add("accent");
        applyOffsetBtn.setOnAction(e -> handleApplyOffset());

        detectOffsetBtn.setTooltip(new Tooltip(
            "Pause both players at matching frames, then enter the\n" +
            "difference in seconds and click Apply."));
        detectOffsetBtn.setOnAction(e -> showOffsetHelp());

        HBox offsetRow = new HBox(8, offsetField, applyOffsetBtn, detectOffsetBtn);
        HBox.setHgrow(offsetField, Priority.ALWAYS);

        offsetStatus.getStyleClass().add("subhead");
        offsetStatus.setStyle("-fx-text-fill: #8b8fa8;");

        VBox offsetCard = card("File Mismatch Offset", hint, offsetRow, offsetStatus);

        node.getChildren().addAll(playCard, offsetCard);
    }

    // ─── Handlers ─────────────────────────────────────────────────────────

    private void handleSeek() {
        try {
            double pos = Double.parseDouble(seekField.getText().trim());
            owner.doSeek(pos);
        } catch (NumberFormatException ex) {
            owner.showError("Seek position must be a number (seconds).");
        }
    }

    private void handleApplyOffset() {
        try {
            double offset = Double.parseDouble(offsetField.getText().trim());
            owner.doSetOffset(offset);
            if (offset == 0) {
                offsetStatus.setText("No offset");
                offsetStatus.setStyle("-fx-text-fill: #8b8fa8;");
            } else {
                offsetStatus.setText(String.format("Offset active: %+.1fs", offset));
                offsetStatus.setStyle("-fx-text-fill: #f0a040;");
            }
        } catch (NumberFormatException ex) {
            owner.showError("Offset must be a number (e.g. 90 or -30).");
        }
    }

    private void showOffsetHelp() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("How to find your offset");
        alert.setHeaderText("Finding the mismatch offset");
        alert.setContentText(
            "1. Both users pause on a recognisable scene (e.g. first line of dialogue).\n\n" +
            "2. User A reads their position from the position label below.\n" +
            "   User B reads their position.\n\n" +
            "3. The person whose file starts LATER sets:\n" +
            "   offset = (your position) - (other person's position)\n\n" +
            "Example:\n" +
            "  User A is at 0:01:30 (90s)\n" +
            "  User B is at 0:00:00 (0s)\n" +
            "  → User A sets offset = +90\n\n" +
            "After applying, sync will automatically compensate.");
        alert.showAndWait();
    }

    // ─── Public API ───────────────────────────────────────────────────────

    public void setEnabled(boolean enabled) {
        playBtn.setDisable(!enabled);
        pauseBtn.setDisable(!enabled);
        back5Btn.setDisable(!enabled);
        fwd5Btn.setDisable(!enabled);
        back30Btn.setDisable(!enabled);
        fwd30Btn.setDisable(!enabled);
        seekBtn.setDisable(!enabled);
        seekField.setDisable(!enabled);
        // offset is always enabled — can set before launch
    }

    public void updatePosition(double seconds) {
        int h = (int) seconds / 3600;
        int m = (int) (seconds % 3600) / 60;
        int s = (int) seconds % 60;
        posLabel.setText(String.format("Position: %02d:%02d:%02d", h, m, s));
    }

    // ─── UI helper ────────────────────────────────────────────────────────

    private VBox card(String title, javafx.scene.Node... children) {
        Label heading = new Label(title);
        heading.getStyleClass().add("heading");
        VBox card = new VBox(8);
        card.getStyleClass().add("card");
        card.getChildren().add(heading);
        card.getChildren().addAll(children);
        return card;
    }

    public VBox getNode() { return node; }
}
