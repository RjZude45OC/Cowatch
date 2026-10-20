package com.cowatch.client.update;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.function.DoubleConsumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds newer builds on the repo's GitHub Releases and installs them in place.
 *
 * <p>A release is published by hand with a {@code vX.Y.Z} tag; CI then builds
 * it, stamps X.Y.Z into {@code cowatch-build.properties} and attaches the
 * installers. "Newer" is the latest full release with a bigger version.
 * The prerelease test builds from pushes to main are never offered.
 * Installing always happens after the app exits: a small script waits for
 * this process to end, swaps in the new version and starts it again, because
 * none of the three platforms let a running app overwrite its own files
 * reliably.
 */
public final class Updater {

    private static final Logger log = Logger.getLogger(Updater.class.getName());

    private static final String RELEASES_API =
            "https://api.github.com/repos/RjZude45OC/Cowatch/releases/latest";
    private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");

    /** Name ending of the per-user Linux build (a tarred jpackage app image). */
    static final String LINUX_TARBALL = "-linux-x64.tar.gz";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** A release newer than this copy, with the file this install needs. */
    public static final class Update {
        public final String version;
        public final String assetName;
        public final URI assetUrl;
        public final long size;

        Update(String version, String assetName, URI assetUrl, long size) {
            this.version = version;
            this.assetName = assetName;
            this.assetUrl = assetUrl;
            this.size = size;
        }
    }

    private enum Os { WINDOWS, MAC, LINUX, OTHER }

    private Updater() {}

    /**
     * This copy's release version, e.g. "1.2.0", or empty for a local or
     * test build, which never updates.
     */
    public static String currentVersion() {
        try (InputStream in = Updater.class.getResourceAsStream("/cowatch-build.properties")) {
            if (in == null) return "";
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version", "").trim();
            return VERSION.matcher(v).matches() ? v : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Compares "X.Y.Z" versions (a leading "v" is ignored). */
    static int compareVersions(String a, String b) {
        Matcher ma = VERSION.matcher(a), mb = VERSION.matcher(b);
        if (!ma.matches() || !mb.matches()) throw new IllegalArgumentException(a + " / " + b);
        for (int i = 1; i <= 3; i++) {
            int c = Integer.compare(Integer.parseInt(ma.group(i)), Integer.parseInt(mb.group(i)));
            if (c != 0) return c;
        }
        return 0;
    }

    /**
     * The launcher jpackage installed, e.g. {@code .../cowatch.exe}. Absent
     * when run from the IDE or with {@code java -jar}, where there is nothing
     * to update.
     */
    private static Optional<Path> launcher() {
        String p = System.getProperty("jpackage.app-path");
        return p == null || p.isBlank() ? Optional.empty() : Optional.of(Paths.get(p));
    }

    private static Os os() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) return Os.WINDOWS;
        if (name.startsWith("mac")) return Os.MAC;
        if (name.startsWith("linux")) return Os.LINUX;
        return Os.OTHER;
    }

    /**
     * Asks GitHub for the latest release. Empty when this copy is up to date,
     * is a local build, or the check failed (logged, never shown: a missed
     * check just means trying again next launch).
     */
    public static Optional<Update> check() {
        String current = currentVersion();
        Optional<Path> launcher = launcher();
        if (current.isEmpty() || launcher.isEmpty()) {
            log.info("Update check skipped: not an installed release build");
            return Optional.empty();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RELEASES_API))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "cowatch-client")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warning("Update check: GitHub answered " + res.statusCode());
                return Optional.empty();
            }
            JSONObject rel = new JSONObject(res.body());
            String tag = rel.optString("tag_name");
            if (!VERSION.matcher(tag).matches() || compareVersions(tag, current) <= 0) {
                log.info("Update check: " + current + " is up to date (latest " + tag + ")");
                return Optional.empty();
            }
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            JSONArray assets = rel.optJSONArray("assets");
            for (int j = 0; assets != null && j < assets.length(); j++) {
                JSONObject a = assets.getJSONObject(j);
                String name = a.optString("name");
                // A release edited from a test build can still hold that
                // build's installers; only this version's files count.
                if (name.contains(version) && wants(name, launcher.get())) {
                    log.info("Update available: " + version + " (running " + current + ")");
                    return Optional.of(new Update(version, name,
                            URI.create(a.getString("browser_download_url")), a.optLong("size", -1)));
                }
            }
            // CI attaches the installers a few minutes after the release is
            // published; until then there is nothing to offer yet.
            log.info("Update check: " + tag + " has no installer for this system yet");
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            log.warning("Update check failed: " + e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Whether a release asset is the installer this copy can update from. */
    private static boolean wants(String asset, Path launcher) {
        switch (os()) {
            case WINDOWS: return asset.endsWith(".msi");
            case MAC:     return asset.endsWith(".dmg");
            case LINUX:   return linuxUserInstall(launcher) ? asset.endsWith(LINUX_TARBALL) : asset.endsWith(".deb");
            default:      return false;
        }
    }

    /**
     * The tarball build lives wherever the user unpacked it and they own it;
     * the .deb lives in /opt and needs root. Writability tells them apart.
     */
    private static boolean linuxUserInstall(Path launcher) {
        Path appDir = launcher.getParent().getParent();   // <app>/bin/cowatch
        return Files.isWritable(appDir) && Files.isWritable(appDir.getParent());
    }

    /** Downloads the installer to a temp folder, reporting 0..1 progress. */
    public static Path download(Update u, DoubleConsumer progress) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("cowatch-update-");
        Path file = dir.resolve(u.assetName);
        HttpRequest req = HttpRequest.newBuilder(u.assetUrl)
                .header("User-Agent", "cowatch-client")
                .GET().build();
        HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) throw new IOException("Download failed: HTTP " + res.statusCode());
        long total = res.headers().firstValueAsLong("Content-Length").orElse(u.size);
        try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (Thread.interrupted()) throw new InterruptedException();
                out.write(buf, 0, n);
                done += n;
                if (total > 0) progress.accept((double) done / total);
            }
        }
        if (u.size > 0 && Files.size(file) != u.size) {
            throw new IOException("Download was incomplete. Please try again.");
        }
        return file;
    }

    /**
     * Starts the script that installs {@code installer} once this process has
     * exited, then relaunches the app. The caller must quit right after.
     */
    public static void installAfterExit(Path installer) throws IOException {
        Path launcher = launcher().orElseThrow(() -> new IOException("Not an installed copy"));
        long pid = ProcessHandle.current().pid();
        Path dir = installer.getParent();
        Path logFile = dir.resolve("install.log");
        ProcessBuilder pb;
        switch (os()) {
            case WINDOWS: {
                Path script = write(dir.resolve("install.ps1"), windowsScript(pid, installer, launcher, logFile));
                pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString());
                break;
            }
            case MAC: {
                Path app = launcher.getParent().getParent().getParent();   // X.app/Contents/MacOS/x
                if (!Files.isWritable(app.getParent())) {
                    throw new IOException("cowatch can't write to " + app.getParent()
                            + ". Move cowatch to your Applications folder and try again.");
                }
                Path script = write(dir.resolve("install.sh"), macScript(pid, installer, app));
                pb = new ProcessBuilder("/bin/sh", script.toString());
                break;
            }
            case LINUX: {
                Path script = write(dir.resolve("install.sh"), linuxScript(pid, installer, launcher));
                pb = new ProcessBuilder("setsid", "/bin/sh", script.toString());
                break;
            }
            default:
                throw new IOException("Self-update is not supported on this system");
        }
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        pb.start();
        log.info("Update installer started; log at " + logFile);
    }

    private static Path write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** Single-quoted shell literal. */
    private static String sh(Object s) {
        return "'" + s.toString().replace("'", "'\\''") + "'";
    }

    /** Single-quoted PowerShell literal. */
    private static String ps(Object s) {
        return "'" + s.toString().replace("'", "''") + "'";
    }

    /**
     * msiexec does a silent per-user upgrade (no admin prompt), into the
     * folder this copy already runs from so the relaunch path stays valid.
     */
    static String windowsScript(long pid, Path msi, Path exe, Path logFile) {
        // No trailing backslash: before the closing quote it would escape it.
        String installDir = exe.getParent().toString();
        return "$ErrorActionPreference = 'Continue'\r\n"
                + "Wait-Process -Id " + pid + " -Timeout 60 -ErrorAction SilentlyContinue\r\n"
                + "$msiArgs = @('/i', '\"' + " + ps(msi) + " + '\"', 'INSTALLDIR=\"' + " + ps(installDir)
                + " + '\"', '/qn', '/norestart', '/l*v', '\"' + " + ps(logFile.resolveSibling("msiexec.log")) + " + '\"')\r\n"
                + "$p = Start-Process -FilePath 'msiexec.exe' -ArgumentList $msiArgs -Wait -PassThru\r\n"
                + "\"msiexec exited with $($p.ExitCode)\"\r\n"
                + "Start-Process -FilePath " + ps(exe) + "\r\n";
    }

    /**
     * Copies the new .app from the disk image over the old one, keeping the
     * old copy until the new one is in place so a failed copy leaves a
     * working app behind.
     */
    static String macScript(long pid, Path dmg, Path app) {
        return "#!/bin/sh\n"
                + "while kill -0 " + pid + " 2>/dev/null; do sleep 0.5; done\n"
                + "APP=" + sh(app) + "\n"
                + "MNT=$(mktemp -d)\n"
                + "hdiutil attach -nobrowse -readonly -noautoopen -mountpoint \"$MNT\" " + sh(dmg) + " || exit 1\n"
                + "SRC=$(find \"$MNT\" -maxdepth 1 -name '*.app' | head -n 1)\n"
                + "if [ -n \"$SRC\" ]; then\n"
                + "  rm -rf \"$APP.old\"\n"
                + "  if mv \"$APP\" \"$APP.old\" && ditto \"$SRC\" \"$APP\"; then rm -rf \"$APP.old\";\n"
                + "  else echo 'copy failed, restoring'; rm -rf \"$APP\"; mv \"$APP.old\" \"$APP\"; fi\n"
                + "fi\n"
                + "hdiutil detach \"$MNT\" -quiet\n"
                + "xattr -dr com.apple.quarantine \"$APP\" 2>/dev/null\n"
                + "open \"$APP\"\n";
    }

    /**
     * A tarball install is swapped like the macOS app; a .deb install goes
     * through dpkg with pkexec, which shows the desktop's password prompt.
     */
    static String linuxScript(long pid, Path installer, Path launcher) {
        String wait = "while kill -0 " + pid + " 2>/dev/null; do sleep 0.5; done\n";
        String relaunch = "nohup " + sh(launcher) + " >/dev/null 2>&1 &\n";
        if (installer.getFileName().toString().endsWith(".deb")) {
            return "#!/bin/sh\n" + wait
                    + "if command -v pkexec >/dev/null; then pkexec dpkg -i " + sh(installer) + "\n"
                    + "else xdg-open " + sh(installer) + "; exit 0; fi\n"
                    + relaunch;
        }
        Path appDir = launcher.getParent().getParent();
        return "#!/bin/sh\n" + wait
                + "APP=" + sh(appDir) + "\n"
                + "NEW=$(mktemp -d " + sh(appDir.getParent().resolve(".cowatch-update-XXXXXX")) + ")\n"
                + "tar -xzf " + sh(installer) + " -C \"$NEW\" || exit 1\n"
                + "SRC=$(find \"$NEW\" -mindepth 1 -maxdepth 1 -type d | head -n 1)\n"
                + "rm -rf \"$APP.old\"\n"
                + "if mv \"$APP\" \"$APP.old\" && mv \"$SRC\" \"$APP\"; then rm -rf \"$APP.old\";\n"
                + "else echo 'swap failed, restoring'; rm -rf \"$APP\"; mv \"$APP.old\" \"$APP\"; fi\n"
                + "rm -rf \"$NEW\"\n"
                + relaunch;
    }
}
