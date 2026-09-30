package com.cowatch.client.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Single source of truth for where cowatch keeps its settings, and the only
 * place that reads or writes them.
 *
 * <p>Files live in the platform's per-user configuration directory:
 * <ul>
 *   <li>Windows — {@code %APPDATA%\cowatch\}</li>
 *   <li>macOS   — {@code ~/Library/Application Support/cowatch/}</li>
 *   <li>Linux   — {@code $XDG_CONFIG_HOME/cowatch/} (or {@code ~/.config/cowatch/})</li>
 * </ul>
 *
 * <p>Values are stored in plain text. Do not put anything here that would be
 * damaging to leak; see {@link #isSecret(String)} for the keys that are
 * deliberately kept out of the file.
 */
public final class AppPrefs {

    private static final Logger log = Logger.getLogger(AppPrefs.class.getName());

    /** Property-key suffixes never written to disk, because the file is unencrypted. */
    private static final String[] SECRET_SUFFIXES = { ".password", ".secret" };

    private AppPrefs() {}

    /** The per-user cowatch configuration directory. Not guaranteed to exist yet. */
    public static Path dir() {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return (appData != null ? Paths.get(appData) : Paths.get(home)).resolve("cowatch");
        }
        if (os.contains("mac")) {
            return Paths.get(home, "Library", "Application Support", "cowatch");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return (xdg != null ? Paths.get(xdg) : Paths.get(home, ".config")).resolve("cowatch");
    }

    /** Resolves a settings file name against {@link #dir()}. */
    public static Path file(String fileName) {
        return dir().resolve(fileName);
    }

    /**
     * Loads a settings file. A missing or unreadable file yields empty
     * properties rather than an error — first run is not a failure.
     */
    public static Properties load(String fileName) {
        Properties p = new Properties();
        Path path = file(fileName);
        if (!Files.exists(path)) return p;
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        } catch (IOException e) {
            log.warning("Could not read " + path + ": " + e.getMessage());
        }
        return p;
    }

    /**
     * Writes a settings file, creating the directory if needed. Keys matching
     * {@link #isSecret(String)} are dropped before writing.
     *
     * @throws IOException if the file could not be written; callers surface this
     *                     to the user rather than failing silently.
     */
    public static void save(String fileName, Properties p, String comment) throws IOException {
        Properties safe = new Properties();
        for (String key : p.stringPropertyNames()) {
            if (!isSecret(key)) safe.setProperty(key, p.getProperty(key));
        }
        Path path = file(fileName);
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path)) {
            safe.store(out, comment);
        }
    }

    /**
     * True for keys holding a credential that must not land in an unencrypted
     * file. Such values stay in memory for the session only.
     */
    public static boolean isSecret(String key) {
        for (String suffix : SECRET_SUFFIXES) {
            if (key.endsWith(suffix)) return true;
        }
        return false;
    }
}
