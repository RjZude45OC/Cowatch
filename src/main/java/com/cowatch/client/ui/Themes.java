package com.cowatch.client.ui;

import com.cowatch.client.util.AppPrefs;

import javafx.collections.ListChangeListener;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Window;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Colour themes: cowatch's own, plus fbtui's built-in presets and every
 * monkeytype theme (generated into {@code themes.txt}).
 *
 * <p>dark.css draws everything from the {@code -sp-*} tokens on {@code .root},
 * so a theme is only a set of token values. They are set as an inline style
 * on the root of every window, dialogs and popups included, which overrides
 * the stylesheet's defaults without touching any other rule.
 */
public final class Themes {

    private static final Logger log = Logger.getLogger(Themes.class.getName());

    private static final String PREFS_FILE = "ui.properties";
    private static final String KEY = "theme";

    /** The look dark.css defines; choosing it clears the overrides. */
    public static final String DEFAULT = "cowatch";

    /** One theme's base colours, as in fbtui. */
    public static final class Theme {
        public final String name;
        /** "cowatch", "built-in" (fbtui's presets) or "monkeytype". */
        public final String kind;
        public final int bg, accent, text, dim, ok, error, select, highlight;

        Theme(String name, String kind, int[] c) {
            this.name = name;
            this.kind = kind;
            bg = c[0]; accent = c[1]; text = c[2]; dim = c[3];
            ok = c[4]; error = c[5]; select = c[6]; highlight = c[7];
        }

        public boolean isLight() { return lum(bg) > 140; }

        /** What the filter matches: name, kind, and light or dark. */
        String searchText() {
            return (name + " " + kind + " " + (isLight() ? "light" : "dark")).toLowerCase(Locale.ROOT);
        }

        /** The {@code -sp-*} tokens for this theme, as an inline style. */
        String style() {
            int muted = mix(bg, dim, 0.6);
            // Dark text on a bright accent (serika's yellow), white otherwise.
            int onAccent = lum(accent) > 150 ? (lum(bg) < lum(text) ? bg : text) : 0xffffff;
            return "-sp-bg: " + hex(bg) + ";"
                    + "-sp-surface: " + hex(mix(bg, text, 0.04)) + ";"
                    + "-sp-surface2: " + hex(mix(bg, text, 0.07)) + ";"
                    + "-sp-surface3: " + hex(mix(bg, text, 0.10)) + ";"
                    + "-sp-elevated: " + hex(mix(bg, text, 0.13)) + ";"
                    + "-sp-border: " + hex(mix(bg, text, 0.16)) + ";"
                    + "-sp-border-dim: " + hex(mix(bg, text, 0.10)) + ";"
                    + "-sp-accent: " + hex(accent) + ";"
                    + "-sp-accent-hover: " + hex(mix(accent, bg, 0.12)) + ";"
                    + "-sp-accent-press: " + hex(mix(accent, bg, 0.25)) + ";"
                    + "-sp-accent-dim: " + rgba(accent, 0.16) + ";"
                    + "-sp-accent-glow: " + rgba(accent, 0.4) + ";"
                    + "-sp-green: " + hex(ok) + ";"
                    + "-sp-green-dim: " + hex(mix(bg, ok, 0.25)) + ";"
                    + "-sp-red: " + hex(error) + ";"
                    + "-sp-red-dim: " + hex(mix(bg, error, 0.25)) + ";"
                    + "-sp-yellow: " + hex(highlight) + ";"
                    + "-sp-text: " + hex(text) + ";"
                    + "-sp-text-dim: " + hex(dim) + ";"
                    + "-sp-text-muted: " + hex(muted) + ";"
                    + "-sp-text-on-accent: " + hex(onAccent) + ";";
        }
    }

    private static final List<Theme> ALL = load();
    private static Theme current = find(DEFAULT).orElse(ALL.get(0));

    private Themes() {}

    /** Every theme: cowatch first, then fbtui's presets, then monkeytype's. */
    public static List<Theme> all() { return ALL; }

    public static Theme current() { return current; }

    public static Optional<Theme> find(String name) {
        return ALL.stream().filter(t -> t.name.equals(name)).findFirst();
    }

    /**
     * Applies the saved theme to every window from now on. Call once, before
     * the first window shows.
     */
    public static void install() {
        String saved = AppPrefs.load(PREFS_FILE).getProperty(KEY, DEFAULT);
        current = find(saved).orElse(current);
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) watch(w);
            }
        });
        for (Window w : Window.getWindows()) watch(w);
    }

    /** Shows {@code theme} everywhere right away, without saving it. */
    public static void preview(Theme theme) {
        current = theme;
        for (Window w : Window.getWindows()) {
            if (w.getScene() != null) style(w.getScene().getRoot());
        }
    }

    /** Shows {@code theme} and keeps it for the next launch. */
    public static void choose(Theme theme) {
        preview(theme);
        Properties p = AppPrefs.load(PREFS_FILE);
        p.setProperty(KEY, theme.name);
        try {
            AppPrefs.save(PREFS_FILE, p, "cowatch appearance");
        } catch (IOException e) {
            log.warning("Could not save the theme: " + e.getMessage());
        }
    }

    private static void watch(Window w) {
        hook(w.getScene());
        w.sceneProperty().addListener((obs, was, scene) -> hook(scene));
    }

    /** Styles a scene's root now and whenever the root is swapped. */
    private static void hook(Scene scene) {
        if (scene == null) return;
        style(scene.getRoot());
        if (scene.getProperties().putIfAbsent(Themes.class, Boolean.TRUE) == null) {
            scene.rootProperty().addListener((obs, was, root) -> style(root));
        }
    }

    private static void style(Parent root) {
        if (root != null) root.setStyle(current.kind.equals(DEFAULT) ? "" : current.style());
    }

    private static List<Theme> load() {
        List<Theme> list = new ArrayList<>();
        // dark.css's own values, so the default shows in the list like any other.
        list.add(new Theme(DEFAULT, DEFAULT, new int[] {
                0x0d0f18, 0x7c6af7, 0xe2e4f0, 0x8b90a8, 0x2eca6a, 0xe05260, 0x252a3d, 0xf0a040 }));
        try (InputStream in = Themes.class.getResourceAsStream("/com/cowatch/client/themes.txt")) {
            if (in == null) {
                log.warning("themes.txt not found; only the default theme is available");
                return Collections.unmodifiableList(list);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                if (f.length != 10) continue;
                int[] c = new int[8];
                for (int i = 0; i < 8; i++) c[i] = Integer.parseInt(f[i + 2], 16);
                list.add(new Theme(f[0], f[1], c));
            }
        } catch (IOException | NumberFormatException e) {
            log.warning("Could not read themes.txt: " + e.getMessage());
        }
        return Collections.unmodifiableList(list);
    }

    // --- colour maths ----------------------------------------------------

    static int lum(int c) {
        return (((c >> 16) & 255) * 299 + ((c >> 8) & 255) * 587 + (c & 255) * 114) / 1000;
    }

    /** {@code a} moved {@code t} of the way towards {@code b}. */
    static int mix(int a, int b, double t) {
        int r = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int x = (a >> shift) & 255, y = (b >> shift) & 255;
            r |= ((int) Math.round(x + (y - x) * t)) << shift;
        }
        return r;
    }

    static String hex(int c) { return String.format("#%06x", c & 0xffffff); }

    private static String rgba(int c, double alpha) {
        return "rgba(" + ((c >> 16) & 255) + "," + ((c >> 8) & 255) + "," + (c & 255) + "," + alpha + ")";
    }
}
