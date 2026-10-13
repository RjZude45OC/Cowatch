package com.cowatch.client.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.net.URL;

/**
 * A small always-on-top strip in the style of an old desktop music player:
 *
 * <pre>Show name · Episode 7          [↺10] [▶] [10↻] [⤢]
 * 00:14:32 / 00:23:40</pre>
 *
 * <p>Shown in place of the main window while it is minimised during playback,
 * so the window's library browser and settings are out of the way but the
 * episode, the time and play/pause are still one click away. Expanding it, or
 * restoring the main window from the taskbar, puts things back.
 *
 * <p>Drag it anywhere by its background. It remembers where it was put for the
 * rest of the session.
 */
public class MiniPlayer {

    private static final Duration TICK = Duration.millis(500);

    private final MainWindow owner;
    private final Stage      mainStage;
    private final Stage      stage = new Stage(StageStyle.TRANSPARENT);

    private final Label  titleLabel = new Label("Now playing");
    private final Label  timeLabel  = new Label("00:00:00");
    private final Button playPause  = button("▶", "Play / pause");
    private final Button back10     = button("↺10", "Back 10 seconds");
    private final Button fwd10      = button("10↻", "Forward 10 seconds");
    private final Button expand     = button("⤢", "Back to the full window");

    private final Timeline ticker = new Timeline(new KeyFrame(TICK, e -> refresh()));

    private double dragDx, dragDy;
    private boolean placed = false;

    public MiniPlayer(MainWindow owner, Stage mainStage) {
        this.owner     = owner;
        this.mainStage = mainStage;
        ticker.setCycleCount(Animation.INDEFINITE);
        build();
    }

    private void build() {
        titleLabel.getStyleClass().add("now-playing-show");
        titleLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        titleLabel.setMinWidth(0);
        timeLabel.getStyleClass().add("position-value");

        VBox info = new VBox(1, titleLabel, timeLabel);
        info.setAlignment(Pos.CENTER_LEFT);
        info.setMinWidth(0);
        info.setPrefWidth(0);
        HBox.setHgrow(info, Priority.ALWAYS);

        playPause.getStyleClass().add("green");
        playPause.setOnAction(e -> owner.doPlayPause());
        back10.setOnAction(e -> owner.doSkipBackward(10));
        fwd10.setOnAction(e -> owner.doSkipForward(10));
        expand.setOnAction(e -> restoreMain());

        HBox root = new HBox(8, info, back10, playPause, fwd10, expand);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(8, 10, 8, 12));
        root.getStyleClass().addAll("app-root", "mini-player");
        root.setPrefWidth(420);

        // No title bar, so the strip itself is the handle.
        root.setOnMousePressed(e -> {
            dragDx = stage.getX() - e.getScreenX();
            dragDy = stage.getY() - e.getScreenY();
        });
        root.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() + dragDx);
            stage.setY(e.getScreenY() + dragDy);
            placed = true;
        });

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        URL css = CoWatchApp.class.getResource(CoWatchApp.CSS_PATH);
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        stage.setScene(scene);
        stage.setTitle("cowatch");
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.initOwner(null);
        for (Image icon : mainStage.getIcons()) stage.getIcons().add(icon);
    }

    /** Pops the strip up, in the bottom right corner the first time. */
    public void show() {
        refresh();
        if (!placed) {
            stage.sizeToScene();
            Rectangle2D area = Screen.getPrimary().getVisualBounds();
            double w = stage.getWidth()  > 0 ? stage.getWidth()  : 420;
            double h = stage.getHeight() > 0 ? stage.getHeight() : 60;
            stage.setX(area.getMaxX() - w - 24);
            stage.setY(area.getMaxY() - h - 24);
        }
        stage.show();
        ticker.play();
    }

    public void hide() {
        ticker.stop();
        stage.hide();
    }

    public boolean isShowing() { return stage.isShowing(); }

    /** Brings the full window back and puts the strip away. */
    private void restoreMain() {
        hide();
        mainStage.setIconified(false);
        mainStage.show();
        mainStage.toFront();
    }

    /** Same wording as the transport bar. */
    public void setNowPlaying(String show, String episode) {
        boolean known = show != null && !show.isBlank();
        String text = known ? show : "Now playing";
        if (episode != null && !episode.isBlank()) text += "  ·  " + episode;
        titleLabel.setText(text);
        titleLabel.setTooltip(new Tooltip(text));
    }

    private void refresh() {
        String pos = formatTime(owner.getPosition());
        double duration = owner.getDuration();
        timeLabel.setText(duration > 0 ? pos + " / " + formatTime(duration) : pos);
        setPlayGlyph(playPause, owner.isPaused());
    }

    /** One button for both: shows what pressing it will do. */
    public static void setPlayGlyph(Button b, boolean paused) {
        String glyph = paused ? "▶" : "⏸";
        if (!glyph.equals(b.getText())) {
            b.setText(glyph);
            b.getStyleClass().removeAll("green", "red");
            b.getStyleClass().add(paused ? "green" : "red");
        }
    }

    static String formatTime(double seconds) {
        if (seconds < 0 || Double.isNaN(seconds)) seconds = 0;
        long total = (long) seconds;
        return String.format("%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
    }

    private static Button button(String glyph, String tip) {
        Button b = new Button(glyph);
        b.getStyleClass().add("transport-btn");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setTooltip(new Tooltip(tip));
        return b;
    }
}
