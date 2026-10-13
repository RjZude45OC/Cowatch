package com.cowatch.client.ui;

import com.cowatch.client.util.LogBuffer;
import com.cowatch.client.util.LogBuffer.Entry;
import com.cowatch.client.util.LogBuffer.Source;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Developer console: live app log plus a readout of connection and sync state.
 *
 * <p>A separate window so it can sit beside the player without squeezing the
 * main layout. Toggled with Ctrl+` (F12 as a fallback for keyboards without a
 * backtick key). It reads from {@link LogBuffer}, which records from launch, so
 * opening it late still shows what happened before.
 */
public final class DebugConsole {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final int MAX_ROWS = 5_000;

    private final Stage stage = new Stage();
    private final Supplier<Map<String, String>> stateSupplier;

    private final ObservableList<Entry> rows = FXCollections.observableArrayList();
    private final FilteredList<Entry> visible = new FilteredList<>(rows);
    private final ListView<Entry> list = new ListView<>(visible);

    private final Map<Source, ToggleButton> sourceToggles = new EnumMap<>(Source.class);
    private final ComboBox<String> levelBox = new ComboBox<>();
    private final TextField search = new TextField();
    private final CheckBox hideTimePos = new CheckBox("Hide time-pos");
    private final ToggleButton follow = new ToggleButton("Follow");
    private final Label countLabel = new Label();
    private final GridPane stateGrid = new GridPane();

    private long lastSeq = 0;

    private final Timeline poll = new Timeline(new KeyFrame(Duration.millis(250), e -> drain()));
    private final Timeline statePoll = new Timeline(new KeyFrame(Duration.millis(500), e -> refreshState()));

    public DebugConsole(Stage owner, Supplier<Map<String, String>> stateSupplier) {
        this.stateSupplier = stateSupplier;
        poll.setCycleCount(Timeline.INDEFINITE);
        statePoll.setCycleCount(Timeline.INDEFINITE);

        BorderPane root = new BorderPane();
        root.getStyleClass().addAll("app-root", "debug-console");

        VBox top = new VBox(8, buildStateGrid(), buildToolbar());
        top.setPadding(new Insets(10, 10, 6, 10));
        root.setTop(top);

        list.getStyleClass().add("debug-log");
        list.setCellFactory(v -> new EntryCell());
        list.setPlaceholder(new Label("No log lines match the filters"));
        BorderPane.setMargin(list, new Insets(0, 10, 10, 10));
        root.setCenter(list);

        Scene scene = new Scene(root, 1000, 560);
        URL css = DebugConsole.class.getResource(CoWatchApp.CSS_PATH);
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        installToggleKey(scene, this::toggle);

        stage.setTitle("cowatch — debug console");
        stage.getIcons().setAll(owner.getIcons());
        stage.setScene(scene);
        stage.setOnShown(e -> { drain(); refreshState(); poll.play(); statePoll.play(); });
        stage.setOnHidden(e -> { poll.stop(); statePoll.stop(); });
    }

    /** Ctrl+` or F12 toggles the console. Filter, so focused text fields cannot swallow it. */
    public static void installToggleKey(Scene scene, Runnable action) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            boolean backtick = e.getCode() == KeyCode.BACK_QUOTE && e.isShortcutDown();
            if (backtick || e.getCode() == KeyCode.F12) {
                action.run();
                e.consume();
            }
        });
    }

    public void close() { stage.hide(); }

    public void toggle() {
        if (stage.isShowing()) {
            stage.hide();
        } else {
            stage.show();
            stage.toFront();
        }
    }

    // --- Layout -------------------------------------------------------------

    private GridPane buildStateGrid() {
        stateGrid.getStyleClass().add("debug-state");
        stateGrid.setHgap(12);
        stateGrid.setVgap(3);
        stateGrid.setPadding(new Insets(8, 10, 8, 10));
        ColumnConstraints key = new ColumnConstraints();
        key.setMinWidth(70);
        ColumnConstraints value = new ColumnConstraints();
        value.setHgrow(Priority.ALWAYS);
        stateGrid.getColumnConstraints().setAll(key, value);
        return stateGrid;
    }

    private Pane buildToolbar() {
        FlowPane sources = new FlowPane(4, 4);
        for (Source s : Source.values()) {
            ToggleButton t = new ToggleButton(label(s));
            t.getStyleClass().add("debug-chip");
            t.setSelected(true);
            t.selectedProperty().addListener((o, a, b) -> refilter());
            sourceToggles.put(s, t);
            sources.getChildren().add(t);
        }

        levelBox.getItems().setAll("All levels", "FINE and up", "INFO and up", "WARNING and up");
        levelBox.getSelectionModel().select(0);
        levelBox.valueProperty().addListener((o, a, b) -> refilter());

        search.setPromptText("Filter text");
        search.setPrefColumnCount(18);
        search.textProperty().addListener((o, a, b) -> refilter());

        hideTimePos.setSelected(true);
        hideTimePos.setTooltip(new Tooltip("mpv reports time-pos many times a second; hide those lines"));
        hideTimePos.selectedProperty().addListener((o, a, b) -> refilter());

        follow.setSelected(true);
        follow.getStyleClass().add("debug-chip");
        follow.selectedProperty().addListener((o, a, b) -> { if (b) scrollToEnd(); });

        Button copy = new Button("Copy");
        copy.getStyleClass().add("secondary");
        copy.setOnAction(e -> copyVisible());

        Button clear = new Button("Clear");
        clear.getStyleClass().add("secondary");
        clear.setOnAction(e -> rows.clear());

        countLabel.getStyleClass().add("subhead");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(8, levelBox, search, hideTimePos, spacer, countLabel, follow, copy, clear);
        controls.setAlignment(Pos.CENTER_LEFT);

        refilter();
        return new VBox(6, sources, controls);
    }

    private static String label(Source s) {
        return switch (s) {
            case APP     -> "App";
            case SYNC    -> "Sync";
            case SERVER  -> "Server";
            case SEANIME -> "Seanime";
            case MPV     -> "mpv";
            case MPV_IPC -> "mpv IPC";
        };
    }

    // --- Data ---------------------------------------------------------------

    private void drain() {
        List<Entry> fresh = LogBuffer.get().since(lastSeq);
        if (fresh.isEmpty()) return;
        lastSeq = fresh.get(fresh.size() - 1).seq();
        rows.addAll(fresh);
        int excess = rows.size() - MAX_ROWS;
        if (excess > 0) rows.remove(0, excess);
        updateCount();
        if (follow.isSelected()) scrollToEnd();
    }

    private void refilter() {
        Level min = switch (levelBox.getSelectionModel().getSelectedIndex()) {
            case 1  -> Level.FINE;
            case 2  -> Level.INFO;
            case 3  -> Level.WARNING;
            default -> Level.ALL;
        };
        String needle = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        boolean noTimePos = hideTimePos.isSelected();
        visible.setPredicate(e -> {
            ToggleButton t = sourceToggles.get(e.source());
            if (t != null && !t.isSelected()) return false;
            if (e.level().intValue() < min.intValue()) return false;
            if (noTimePos && e.source() == Source.MPV_IPC && e.message().contains("\"time-pos\"")) return false;
            return needle.isEmpty()
                    || e.message().toLowerCase().contains(needle)
                    || e.logger().toLowerCase().contains(needle);
        });
        updateCount();
        if (follow.isSelected()) scrollToEnd();
    }

    private void updateCount() {
        countLabel.setText(visible.size() + " / " + rows.size() + " lines");
    }

    private void scrollToEnd() {
        if (!visible.isEmpty()) list.scrollTo(visible.size() - 1);
    }

    private void refreshState() {
        Map<String, String> state;
        try {
            state = stateSupplier.get();
        } catch (RuntimeException ex) {
            state = Map.of("Error", "Could not read state: " + ex);
        }
        stateGrid.getChildren().clear();
        int row = 0;
        for (Map.Entry<String, String> kv : state.entrySet()) {
            Label k = new Label(kv.getKey());
            k.getStyleClass().add("debug-state-key");
            Label v = new Label(kv.getValue());
            v.getStyleClass().add("debug-state-value");
            v.setWrapText(true);
            stateGrid.addRow(row++, k, v);
        }
    }

    private void copyVisible() {
        String text = visible.stream().map(DebugConsole::format).collect(Collectors.joining(System.lineSeparator()));
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static String format(Entry e) {
        return TIME.format(Instant.ofEpochMilli(e.millis())) + " "
                + String.format("%-7s %-8s ", e.level().getName(), label(e.source()))
                + "[" + e.thread() + "] " + e.message();
    }

    // --- Rows -----------------------------------------------------------------

    private static final class EntryCell extends ListCell<Entry> {
        private static final String[] LEVEL_CLASSES = {
                "debug-level-severe", "debug-level-warning", "debug-level-info", "debug-level-fine" };

        EntryCell() {
            getStyleClass().add("debug-row");
        }

        @Override
        protected void updateItem(Entry e, boolean empty) {
            super.updateItem(e, empty);
            getStyleClass().removeAll(LEVEL_CLASSES);
            if (empty || e == null) {
                setText(null);
                return;
            }
            setText(format(e));
            int lvl = e.level().intValue();
            if (lvl >= Level.SEVERE.intValue())       getStyleClass().add("debug-level-severe");
            else if (lvl >= Level.WARNING.intValue()) getStyleClass().add("debug-level-warning");
            else if (lvl >= Level.INFO.intValue())    getStyleClass().add("debug-level-info");
            else                                      getStyleClass().add("debug-level-fine");
        }
    }
}
