package com.cowatch.client.ui;

import javafx.animation.*;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Motion design for cowatch. JavaFX CSS has no transitions or keyframes,
 * so all animation lives here:
 *
 *  1. staggeredReveal()   — one orchestrated curtain-up: every card fades in
 *                           while rising 14px, 55ms apart, in reading order.
 *  2. installPressSquish()— tactile micro-interaction: any button press
 *                           squishes to 96% and releases back.
 *  3. crossfade()         — soft re-entry when the responsive layout swaps.
 *  4. pulse() / shake()   — opt-in: connecting dot, invalid-input feedback.
 *  5. confirm()           — a button briefly says "Saved ✓", then goes back.
 */
public final class Motion {

    private Motion() {}

    /** The page-load moment. Call once, after the first layout pass. */
    public static void staggeredReveal(Parent root) {
        root.applyCss();
        List<Node> cards = new ArrayList<>(root.lookupAll(".card"));
        cards.sort(Comparator
                .comparingDouble(Motion::sceneY)
                .thenComparingDouble(Motion::sceneX));

        int i = 0;
        for (Node card : cards) {
            card.setOpacity(0);
            card.setTranslateY(14);

            FadeTransition fade = new FadeTransition(Duration.millis(380), card);
            fade.setFromValue(0);
            fade.setToValue(1);
            fade.setDelay(Duration.millis(55L * i));
            fade.setInterpolator(Interpolator.EASE_OUT);

            TranslateTransition rise = new TranslateTransition(Duration.millis(420), card);
            rise.setFromY(14);
            rise.setToY(0);
            rise.setDelay(Duration.millis(55L * i));
            rise.setInterpolator(Interpolator.EASE_OUT);

            fade.play();
            rise.play();
            i++;
        }
    }

    /** Every button in the app squishes slightly under the finger/cursor. */
    public static void installPressSquish(Scene scene) {
        final ButtonBase[] pressed = new ButtonBase[1];

        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            ButtonBase b = findButton(e.getTarget());
            if (b != null && !b.isDisabled()) {
                pressed[0] = b;
                b.setScaleX(0.96);
                b.setScaleY(0.96);
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (pressed[0] != null) {
                pressed[0].setScaleX(1.0);
                pressed[0].setScaleY(1.0);
                pressed[0] = null;
            }
        });
    }

    /** Soft re-entry used when the responsive layout mode changes. */
    public static void crossfade(Node node) {
        if (node == null) return;
        FadeTransition ft = new FadeTransition(Duration.millis(220), node);
        ft.setFromValue(0.35);
        ft.setToValue(1.0);
        ft.play();
    }

    /** Pulsing "connecting…" indicator. Keep the handle and stop() it later. */
    public static Timeline pulse(Node node) {
        Timeline t = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(node.scaleXProperty(), 1.0),
                        new KeyValue(node.scaleYProperty(), 1.0)),
                new KeyFrame(Duration.millis(650),
                        new KeyValue(node.scaleXProperty(), 1.4),
                        new KeyValue(node.scaleYProperty(), 1.4)));
        t.setAutoReverse(true);
        t.setCycleCount(Animation.INDEFINITE);
        t.play();
        return t;
    }

    /** Horizontal shake — e.g. Motion.shake(portField) on invalid input. */
    public static void shake(Node node) {
        TranslateTransition tt = new TranslateTransition(Duration.millis(45), node);
        tt.setFromX(0);
        tt.setToX(6);
        tt.setCycleCount(6);
        tt.setAutoReverse(true);
        tt.setOnFinished(e -> node.setTranslateX(0));
        tt.play();
    }

    /**
     * Flashes a short confirmation on a button or label, then restores the
     * original text. Runs on the JavaFX timer, so it needs no background
     * thread and cannot outlive the window.
     */
    public static void confirm(Labeled node, String message) {
        String original = node.getText();
        node.setText(message);
        PauseTransition hold = new PauseTransition(Duration.seconds(2));
        hold.setOnFinished(e -> node.setText(original));
        hold.play();
    }

    // ── internals ─────────────────────────────────────────────────────────

    private static ButtonBase findButton(Object target) {
        Node n = target instanceof Node ? (Node) target : null;
        while (n != null && !(n instanceof ButtonBase)) n = n.getParent();
        return (ButtonBase) n;
    }

    private static double sceneY(Node n) {
        Bounds b = n.localToScene(n.getBoundsInLocal());
        return b != null ? b.getMinY() : 0;
    }

    private static double sceneX(Node n) {
        Bounds b = n.localToScene(n.getBoundsInLocal());
        return b != null ? b.getMinX() : 0;
    }
}