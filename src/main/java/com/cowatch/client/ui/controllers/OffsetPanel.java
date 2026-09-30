package com.cowatch.client.ui.controllers;

import com.cowatch.client.sync.SyncEngine;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * Nudges this client's playback position relative to everyone else's, for the
 * case where two copies of the same episode do not start at the same frame.
 *
 * <p>Lives in the transport bar's overflow popover rather than on screen
 * permanently: it is a playback-time tool, but one most sessions never touch.
 * The layout is therefore a single row sized for a popover, not a column.
 */
public class OffsetPanel {

    private final VBox  node       = new VBox(8);
    private final Label valueLabel = new Label("0.0 s");

    private SyncEngine syncEngine;
    private double     offset = 0.0;

    public OffsetPanel() {
        build();
    }

    private void build() {
        node.setPadding(new Insets(2));
        node.setFillWidth(true);
        node.setMaxWidth(300);

        valueLabel.getStyleClass().add("offset-value");
        valueLabel.setAlignment(Pos.CENTER);
        valueLabel.setMinWidth(66);

        HBox row = new HBox(4,
                adjBtn("\u22121",   -1.0),
                adjBtn("\u22120.1", -0.1),
                valueLabel,
                adjBtn("+0.1",      +0.1),
                adjBtn("+1",        +1.0));
        row.setAlignment(Pos.CENTER);
        HBox.setHgrow(valueLabel, Priority.ALWAYS);

        Label hint = new Label("Positive = your copy has extra content at the start.");
        hint.getStyleClass().add("subhead");
        hint.setWrapText(true);
        hint.setMaxWidth(280);

        Button reset = new Button("Reset to 0");
        reset.getStyleClass().add("secondary");
        reset.setMaxWidth(Double.MAX_VALUE);
        reset.setOnAction(e -> applyOffset(0.0));

        node.getChildren().addAll(row, hint, reset);
    }

    private void applyOffset(double value) {
        offset = Math.round(value * 10.0) / 10.0;
        valueLabel.setText((offset > 0 ? "+" : "") + offset + " s");
        if (syncEngine != null) syncEngine.setOffset(offset);
    }

    private Button adjBtn(String label, double delta) {
        Button b = new Button(label);
        b.getStyleClass().add("mode-btn");
        b.setMinWidth(42);
        b.setOnAction(e -> applyOffset(offset + delta));
        return b;
    }

    /** Current offset - MainWindow preserves it across a reconnect. */
    public double getOffset() { return offset; }

    public void setSyncEngine(SyncEngine engine) {
        this.syncEngine = engine;
        if (engine != null) engine.setOffset(offset);
    }

    public Node getNode() { return node; }
}
