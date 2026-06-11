package com.syncplay.client.ui.controllers;

import com.syncplay.client.sync.SyncEngine;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public class OffsetPanel {

    private final VBox  node       = new VBox(12);
    private final Label valueLabel = new Label("0.0 s");

    private SyncEngine syncEngine;
    private double     offset = 0.0;

    public OffsetPanel() {
        build();
    }

    private void build() {
        node.setPadding(new Insets(0));

        Label title = heading("Playback Offset");

        // Value display — uses offset-value class for monospaced centred styling
        valueLabel.getStyleClass().add("offset-value");
        valueLabel.setMaxWidth(Double.MAX_VALUE);

        Button mBig   = adjBtn("−1 s",   -1.0);
        Button pBig   = adjBtn("+1 s",   +1.0);
        Button mSmall = adjBtn("−0.1 s", -0.1);
        Button pSmall = adjBtn("+0.1 s", +0.1);

        // Row 1: coarse ±1 s flanking value display
        HBox coarseRow = new HBox(8, mBig, valueLabel, pBig);
        coarseRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(valueLabel, Priority.ALWAYS);
        mBig.setMaxWidth(Double.MAX_VALUE);
        pBig.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(mBig, Priority.SOMETIMES);
        HBox.setHgrow(pBig, Priority.SOMETIMES);

        // Row 2: fine ±0.1 s full width
        mSmall.setMaxWidth(Double.MAX_VALUE);
        pSmall.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(mSmall, Priority.ALWAYS);
        HBox.setHgrow(pSmall, Priority.ALWAYS);
        HBox fineRow = new HBox(8, mSmall, pSmall);

        Label hint = new Label("+ = your file has extra content at the start");
        hint.getStyleClass().add("subhead");
        hint.setWrapText(true);

        Button reset = new Button("Reset Offset");
        reset.setMaxWidth(Double.MAX_VALUE);
        reset.getStyleClass().add("accent");
        reset.setPadding(new Insets(7, 0, 7, 0));
        reset.setOnAction(e -> applyOffset(0.0));

        VBox card = card(title, coarseRow, fineRow, hint, reset);
        node.getChildren().add(card);
    }

    private void applyOffset(double value) {
        offset = Math.round(value * 10.0) / 10.0;
        String sign = offset > 0 ? "+" : "";
        valueLabel.setText(sign + offset + " s");
        if (syncEngine != null) syncEngine.setOffset(offset);
    }

    private Button adjBtn(String label, double delta) {
        Button b = new Button(label);
        b.setOnAction(e -> applyOffset(offset + delta));
        b.getStyleClass().add("mode-btn");
        return b;
    }

    private VBox card(javafx.scene.Node... children) {
        VBox c = new VBox(8, children);
        c.getStyleClass().add("card");
        return c;
    }

    private Label heading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("heading");
        return l;
    }

    public void setSyncEngine(SyncEngine engine) {
        this.syncEngine = engine;
        if (engine != null) engine.setOffset(offset);
    }

    public VBox getNode() { return node; }
}