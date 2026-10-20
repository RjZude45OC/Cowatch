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
 * <p>CI publishes every push to main as a release tagged {@code build-N} and
 * stamps N into {@code cowatch-build.properties}, so "newer" is just a bigger
 * N. Installing always happens after the app exits: a small script waits for
 * this process to end, swaps in the new version and starts it again, because
 * none of the three platforms let a running app overwrite its own files
 * reliably.
 */
public final class Updater {

    private static final Logger log = Logger.getLogger(Updater.class.getName());

    private static final String RELEASES_API =
            "https://api.github.com/repos/RjZude45OC/Cowatch/releases?per_page=20";
    private static final Pattern BUILD_TAG = Pattern.compile("build-(\\d+)");

    /** Asset name of the per-user Linux build (a tarred jpackage app image). */
    static final String LINUX_TARBALL = "cowatch-linux-x64.tar.gz";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** A release newer than this build, with the file this install needs. */
    public static final class Update {
        public final int build;
        public final String assetName;
        public final URI assetUrl;
        public final long size;

        Update(int build, String assetName, URI assetUrl, long size) {
            this.build = build;
            this.assetName = assetName;
            this.assetUrl = assetUrl;
            this.size = size;
        }
    }

    private enum Os { WINDOWS, MAC, LINUX, OTHER }

    private Updater() {}

    /** This build's CI run number, or 0 for a local build, which never updates. */
    public static int currentBuild() {
        try (InputStream in = Updater.class.getResourceAsStream("/cowatch-build.properties")) {
            if (in == null) return 0;
            Properties p = new Properties();
            p.load(in);
            return Integer.parseInt(p.getProperty("build", "0").trim());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
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
     * Asks GitHub for the newest build. Empty when this copy is up to date,
     * is a local build, or the check failed (logged, never shown: a missed
     * check just means trying again next launch).
     */
    public static Optional<Update> check() {
        int current = currentBuild();
        Optional<Path> launcher = launcher();
        if (current <= 0 || launcher.isEmpty()) {
            log.info("Update check skipped: not an installed CI build");
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
            Update best = null;
            JSONArray releases = new JSONArray(res.body());
            for (int i = 0; i < releases.length(); i++) {
                JSONObject rel = releases.getJSONObject(i);
                if (rel.optBoolean("draft")) continue;
                Matcher m = BUILD_TAG.matcher(rel.optString("tag_name"));
                if (!m.matches()) continue;
                int build = Integer.parseInt(m.group(1));
                if (build <= current || (best != null && build <= best.build)) continue;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets == null) continue;
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject a = assets.getJSONObject(j);
                    String name = a.optString("name");
                    if (wants(name, launcher.get())) {
                        best = new Update(build, name,
                                URI.create(a.getString("browser_download_url")), a.optLong("size", -1));
                        break;
                    }
                }
            }
            if (best != null) log.info("Update available: build " + best.build + " (running " + current + ")");
            return Optional.ofNullable(best);
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
            case LINUX:   return linuxUserInstall(launcher) ? asset.equals(LINUX_TARBALL) : asset.endsWith(".deb");
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
