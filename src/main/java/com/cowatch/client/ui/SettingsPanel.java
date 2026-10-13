package com.cowatch.client.ui;

import com.cowatch.client.ui.controllers.ConfigPanel;
import com.cowatch.client.ui.controllers.TrackingPanel;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * The drawer everything set-once lives in: the cowatch server connection, the
 * sync mode, and the AniList token.
 *
 * <p>It slides in from the right edge of the main window and the media browser
 * shrinks to make room, rather than opening a separate window that could end
 * up behind the main one. The gear in the header toggles it.
 *
 * <p>The panels are built once and kept. A JavaFX node has a single parent, and
 * keeping one instance also preserves whatever the user had typed but not yet
 * saved while the drawer is closed.
 */
public class SettingsPanel {

    /** Drawer width once fully open in a wide window. */
    private static final double WIDTH = 400;

    /**
     * Below this window width the drawer takes the whole window instead, and
     * MainWindow takes the media panel out while it is open - squeezing both
     * side by side would leave neither usable. Matches MainWindow's chat split.
     */
    public static final double FULL_WIDTH_BELOW = 900;

    private static final Duration SLIDE = Duration.millis(240);

    /**
     * Clipped holder whose width animates. The content inside keeps its full
     * width and is pinned to the holder's left edge, so as the holder widens
     * the panel appears to slide in rather than being squashed.
     */
    private final StackPane  node = new StackPane();
    private final BorderPane content;
    private final TabPane    tabs;

    /** How much of the drawer is on screen, 0 when closed. */
    private final DoubleProperty shownWidth = new SimpleDoubleProperty(0);

    private Timeline slide;
    private boolean  open;
    private Runnable onShownChanged;
    private Runnable onClosed;

    public SettingsPanel(ConfigPanel configPanel, TrackingPanel trackingPanel) {
        Tab server = new Tab("Server", configPanel.getNode());
        Tab track  = new Tab("Tracking", trackingPanel.getNode());
        tabs = new TabPane(server, track);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        content = new BorderPane(tabs);
        content.getStyleClass().add("settings-root");
        content.setPrefWidth(WIDTH);
        content.setMinWidth(Region.USE_PREF_SIZE);
        content.setMaxWidth(Region.USE_PREF_SIZE);

        node.getChildren().add(content);
        StackPane.setAlignment(content, Pos.TOP_LEFT);
        node.getStyleClass().add("settings-drawer");
        node.minWidthProperty().bind(shownWidth);
        node.prefWidthProperty().bind(shownWidth);
        node.maxWidthProperty().bind(shownWidth);
        node.visibleProperty().bind(shownWidth.greaterThan(0));

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(node.widthProperty());
        clip.heightProperty().bind(node.heightProperty());
        node.setClip(clip);

        // Keep an open drawer inside a window that is resized narrower.
        node.parentProperty().addListener((obs, oldParent, newParent) -> {
            if (newParent instanceof Region parent) {
                parent.widthProperty().addListener((o, oldW, newW) -> {
                    boolean sliding = slide != null && slide.getStatus() == Timeline.Status.RUNNING;
                    if (open && !sliding) {
                        double target = targetWidth();
                        content.setPrefWidth(target);
                        shownWidth.set(target);
                    }
                });
            }
        });
    }

    /** Opens the drawer on {@code tabIndex} (0 = Server, 1 = Tracking). */
    public void show(int tabIndex) {
        tabs.getSelectionModel().select(tabIndex);
        setOpen(true);
    }

    public void show() { show(0); }

    public void hide() { setOpen(false); }

    /** What the header gear does: open if closed, close if open. */
    public void toggle() { setOpen(!open); }

    public boolean isShown() { return open; }

    /** Runs whenever the drawer starts opening or closing. */
    public void setOnShownChanged(Runnable handler) { this.onShownChanged = handler; }

    /** Runs once a close has finished sliding out. */
    public void setOnClosed(Runnable handler) { this.onClosed = handler; }

    private void setOpen(boolean wantOpen) {
        if (wantOpen == open) return;
        open = wantOpen;

        double target = open ? targetWidth() : 0;
        if (open) content.setPrefWidth(target);

        if (slide != null) slide.stop();
        slide = new Timeline(new KeyFrame(SLIDE,
                new KeyValue(shownWidth, target, Interpolator.EASE_BOTH)));
        if (!open) slide.setOnFinished(e -> { if (onClosed != null) onClosed.run(); });
        slide.play();

        if (onShownChanged != null) onShownChanged.run();
    }

    private double targetWidth() {
        double available = node.getParent() instanceof Region parent ? parent.getWidth() : WIDTH;
        if (available <= 0) return WIDTH;
        return available < FULL_WIDTH_BELOW ? available : WIDTH;
    }

    public Region getNode() { return node; }
}
