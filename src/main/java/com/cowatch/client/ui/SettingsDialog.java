package com.cowatch.client.ui;

import com.cowatch.client.ui.controllers.ConfigPanel;
import com.cowatch.client.ui.controllers.TrackingPanel;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.net.URL;

/**
 * The drawer everything set-once lives in: the cowatch server connection, the
 * sync mode, and the AniList token.
 *
 * <p>These used to sit in a permanent left-hand column that cost roughly a
 * third of the window for controls a session touches once. The panels
 * themselves are unchanged and are simply re-parented in here.
 *
 * <p>One Stage is built lazily and then reused. A JavaFX node has a single
 * parent, so rebuilding the window per open would have to move the same panels
 * each time; keeping one instance also preserves whatever the user had typed
 * but not yet saved.
 */
public class SettingsDialog {

    private final Stage owner;
    private final ConfigPanel   configPanel;
    private final TrackingPanel trackingPanel;

    private Stage   stage;
    private TabPane tabs;

    public SettingsDialog(Stage owner, ConfigPanel configPanel, TrackingPanel trackingPanel) {
        this.owner         = owner;
        this.configPanel   = configPanel;
        this.trackingPanel = trackingPanel;
    }

    /** Shows the window, selecting {@code tabIndex} (0 = Server, 1 = Tracking). */
    public void show(int tabIndex) {
        if (stage == null) build();
        tabs.getSelectionModel().select(tabIndex);
        if (stage.isShowing()) stage.requestFocus();
        else                   stage.show();
    }

    public void show() { show(0); }

    public void hide() {
        if (stage != null) stage.hide();
    }

    private void build() {
        Tab server = new Tab("Server", configPanel.getNode());
        Tab track  = new Tab("Tracking", trackingPanel.getNode());
        tabs = new TabPane(server, track);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        BorderPane root = new BorderPane(tabs);
        root.getStyleClass().addAll("app-root", "settings-root");
        BorderPane.setMargin(tabs, new Insets(0));

        Scene scene = new Scene(root, 420, 500);
        URL css = CoWatchApp.class.getResource(CoWatchApp.CSS_PATH);
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        stage = new Stage();
        stage.setTitle("cowatch \u2014 Settings");
        stage.initOwner(owner);
        // Non-modal on purpose: connecting is done from in here, and the user
        // should be able to watch the main window react without closing this.
        stage.initModality(Modality.NONE);
        stage.setScene(scene);
        stage.setMinWidth(380);
        stage.setMinHeight(420);
    }
}
