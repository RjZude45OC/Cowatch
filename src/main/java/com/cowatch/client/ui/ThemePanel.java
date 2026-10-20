package com.cowatch.client.ui;

import com.cowatch.client.ui.Themes.Theme;

import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Theme tab: every theme with its colours, a filter like fbtui's (by
 * name, "built-in", "monkeytype", "light" or "dark"), and a random pick.
 * Clicking a theme applies it at once and keeps it for the next launch.
 */
final class ThemePanel {

    private final VBox node = new VBox(10);
    private final FilteredList<Theme> shown =
            new FilteredList<>(FXCollections.observableArrayList(Themes.all()));
    private final ListView<Theme> list = new ListView<>(shown);
    private final Label count = new Label();

    ThemePanel() {
        node.setPadding(new Insets(12));
        node.setFillWidth(true);

        TextField filter = new TextField();
        filter.setPromptText("Search, e.g. nord, light, monkeytype");
        filter.setMaxWidth(Double.MAX_VALUE);
        filter.textProperty().addListener((obs, was, text) -> {
            String[] words = text.toLowerCase(Locale.ROOT).trim().split("\\s+");
            shown.setPredicate(t -> Arrays.stream(words).allMatch(t.searchText()::contains));
            updateCount();
        });

        Button random = new Button("Random");
        random.getStyleClass().add("secondary");
        random.setOnAction(e -> {
            if (shown.isEmpty()) return;
            Theme t = shown.get(ThreadLocalRandom.current().nextInt(shown.size()));
            list.getSelectionModel().select(t);
            list.scrollTo(t);
        });
        HBox top = new HBox(8, filter, random);
        HBox.setHgrow(filter, Priority.ALWAYS);

        count.getStyleClass().add("muted");

        list.setCellFactory(v -> new ThemeCell());
        list.setPlaceholder(new Label("No theme matches"));
        VBox.setVgrow(list, Priority.ALWAYS);
        list.getSelectionModel().select(Themes.current());
        list.scrollTo(Themes.current());
        list.getSelectionModel().selectedItemProperty().addListener((obs, was, t) -> {
            if (t == null || t == Themes.current()) return;
            Themes.choose(t);
            list.refresh();   // moves the "in use" dot
            updateCount();
        });

        node.getChildren().addAll(top, count, list);
        updateCount();
    }

    private void updateCount() {
        count.setText(shown.size() + " of " + Themes.all().size() + " themes \u00b7 using " + Themes.current().name);
    }

    Region getNode() { return node; }

    /** Name, kind, and the theme's own colours as swatches. */
    private static final class ThemeCell extends ListCell<Theme> {
        @Override
        protected void updateItem(Theme t, boolean empty) {
            super.updateItem(t, empty);
            if (empty || t == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            HBox swatches = new HBox(0,
                    swatch(t.bg, t.text), swatch(t.accent, t.text), swatch(t.text, t.text),
                    swatch(t.dim, t.text), swatch(t.error, t.text));
            Label name = new Label((t == Themes.current() ? "\u25cf " : "") + t.name);
            name.getStyleClass().add("theme-name");
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            Label kind = new Label(t.kind.equals(Themes.DEFAULT) ? "default" : t.kind);
            kind.getStyleClass().add("theme-kind");
            HBox row = new HBox(10, swatches, name, gap, kind);
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
        }

        private static Rectangle swatch(int fill, int edge) {
            Rectangle r = new Rectangle(14, 14, rgb(fill));
            r.setStroke(rgb(edge).deriveColor(0, 1, 1, 0.25));
            return r;
        }

        private static Color rgb(int c) {
            return Color.rgb((c >> 16) & 255, (c >> 8) & 255, c & 255);
        }
    }
}
