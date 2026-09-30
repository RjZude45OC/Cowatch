package com.cowatch.client.ui.controllers;

import com.cowatch.client.ui.MainWindow;
import com.cowatch.client.ui.Motion;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

/**
 * The transport bar pinned to the bottom of the window while mpv is running:
 *
 * <pre>00:14:32   [-30] [-5] [play] [pause] [+5] [+30]            [...]</pre>
 *
 * <p>Previously a tall card in a permanent side column. It is now one row, and
 * it only exists on screen while there is something to control - mpv has its
 * own keyboard shortcuts, so these buttons matter mainly for the remote-synced
 * case. The two rarely-used tools, jumping to a timecode and nudging the sync
 * offset, live behind the overflow button instead of costing permanent space.
 */
public class PlaybackControls {

    /** How often the position readout refreshes while mpv is playing. */
    private static final Duration TICK = Duration.millis(500);

    private final MainWindow  owner;
    private final OffsetPanel offsetPanel;
    private final HBox node = new HBox(6);

    private final Button playBtn   = transportBtn("\u25B6",   "Play");
    private final Button pauseBtn  = transportBtn("\u23F8",   "Pause");
    private final Button back5Btn  = transportBtn("\u21BA5",  "Back 5 seconds");
    private final Button fwd5Btn   = transportBtn("5\u21BB",  "Forward 5 seconds");
    private final Button back30Btn = transportBtn("\u21BA30", "Back 30 seconds");
    private final Button fwd30Btn  = transportBtn("30\u21BB", "Forward 30 seconds");

    private final TextField  seekField = new TextField();
    private final Button     seekBtn   = new Button("Go");
    private final Label      posLabel  = new Label("00:00:00");
    private final MenuButton moreBtn   = new MenuButton("\u22EF");

    /** Drives posLabel. Runs only while the controls are enabled. */
    private final Timeline positionTicker =
            new Timeline(new KeyFrame(TICK, e -> refreshPosition()));

    public PlaybackControls(MainWindow owner, OffsetPanel offsetPanel) {
        this.owner       = owner;
        this.offsetPanel = offsetPanel;
        positionTicker.setCycleCount(Animation.INDEFINITE);
        build();
        setEnabled(false);
    }

    private void build() {
        playBtn.getStyleClass().add("green");
        pauseBtn.getStyleClass().add("red");

        playBtn.setOnAction(e   -> owner.doPlay());
        pauseBtn.setOnAction(e  -> owner.doPause());
        back30Btn.setOnAction(e -> owner.doSkipBackward(30));
        back5Btn.setOnAction(e  -> owner.doSkipBackward(5));
        fwd5Btn.setOnAction(e   -> owner.doSkipForward(5));
        fwd30Btn.setOnAction(e  -> owner.doSkipForward(30));

        posLabel.getStyleClass().add("position-value");
        posLabel.setMinWidth(76);

        HBox transport = new HBox(4, back30Btn, back5Btn, playBtn, pauseBtn, fwd5Btn, fwd30Btn);
        transport.setAlignment(Pos.CENTER);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        buildOverflowMenu();

        node.getChildren().addAll(posLabel, transport, spacer, moreBtn);
        node.setAlignment(Pos.CENTER_LEFT);
        node.setPadding(new Insets(7, 12, 7, 12));
        node.getStyleClass().add("transport-bar");
    }

    /**
     * Builds the overflow popover: jump-to-timecode and the sync offset.
     *
     * <p>Both items keep the menu open on click ({@code setHideOnClick(false)}),
     * since typing a timecode or stepping the offset takes several interactions
     * and a menu that closed on the first one would be unusable.
     */
    private void buildOverflowMenu() {
        seekField.setPromptText("1:23:45 or 83");
        seekField.setPrefWidth(120);
        seekField.setOnAction(e -> handleSeek());
        seekBtn.getStyleClass().add("accent");
        seekBtn.setOnAction(e -> handleSeek());

        HBox seekRow = new HBox(6, seekField, seekBtn);
        seekRow.setAlignment(Pos.CENTER_LEFT);
        VBox seekBox = new VBox(5, menuHeading("Jump to position"), seekRow);
        seekBox.setPadding(new Insets(2));

        CustomMenuItem seekItem = new CustomMenuItem(seekBox);
        seekItem.setHideOnClick(false);

        VBox offsetBox = new VBox(5, menuHeading("Sync offset"), offsetPanel.getNode());
        offsetBox.setPadding(new Insets(2));
        CustomMenuItem offsetItem = new CustomMenuItem(offsetBox);
        offsetItem.setHideOnClick(false);

        moreBtn.getStyleClass().add("overflow-btn");
        moreBtn.setTooltip(new Tooltip("More playback tools"));
        moreBtn.getItems().addAll(seekItem, new SeparatorMenuItem(), offsetItem);
    }

    private static Label menuHeading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("heading");
        return l;
    }

    // --- Seeking ----------------------------------------------------------

    private void handleSeek() {
        Double seconds = parseTimecode(seekField.getText());
        if (seconds == null) {
            Motion.shake(seekField);
            owner.showError("Invalid Position",
                    "Enter a position as seconds (83) or as a timecode (1:23 or 1:23:45).");
            return;
        }
        moreBtn.hide();
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

    // --- Position readout -------------------------------------------------

    private void refreshPosition() {
        posLabel.setText(formatTime(owner.getPosition()));
    }

    private static String formatTime(double seconds) {
        if (seconds < 0 || Double.isNaN(seconds)) seconds = 0;
        long total = (long) seconds;
        return String.format("%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
    }

    // --- Enable / disable -------------------------------------------------

    /**
     * Enables or disables every control, and starts or stops the position
     * ticker with them - a stopped player has nothing to report, and leaving
     * the timeline running would keep waking the FX thread for no reason.
     *
     * <p>The bar also hides itself entirely while disabled. A row of dead
     * buttons is worse than no row, and the window gets the height back.
     */
    public void setEnabled(boolean enabled) {
        for (Node n : new Node[] { playBtn, pauseBtn, back5Btn, fwd5Btn,
                                   back30Btn, fwd30Btn, seekBtn, seekField, moreBtn }) {
            n.setDisable(!enabled);
        }

        node.setVisible(enabled);
        node.setManaged(enabled);

        if (enabled) {
            positionTicker.play();
        } else {
            positionTicker.stop();
            posLabel.setText("00:00:00");
            moreBtn.hide();
        }
    }

    private static Button transportBtn(String glyph, String tip) {
        Button b = new Button(glyph);
        b.getStyleClass().add("transport-btn");
        b.setTooltip(new Tooltip(tip));
        return b;
    }

    public HBox getNode() { return node; }
}
