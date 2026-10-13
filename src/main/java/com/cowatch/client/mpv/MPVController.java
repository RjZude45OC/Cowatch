package com.cowatch.client.mpv;

import com.cowatch.client.util.Async;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.*;

public class MPVController {

    private static final Logger log = Logger.getLogger(MPVController.class.getName());

    private static final String INSTANCE_ID        = String.valueOf(ProcessHandle.current().pid());
    private static final String SOCKET_BASE = isWindows()
            ? "\\\\.\\pipe\\mpvsocket_" + INSTANCE_ID
            : "/tmp/mpvsocket_" + INSTANCE_ID;

    private final java.util.concurrent.atomic.AtomicInteger launchCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private volatile String currentSocketPath = SOCKET_BASE + "_0";

    private static final int    CONNECT_TIMEOUT_MS = 5_000;
    private static final int    CONNECT_POLL_MS    = 200;

    /** Line break for multi-line messages shown in a dialog. */
    private static final String NL = System.lineSeparator();

    private Process      mpvProcess;
    private WinNT.HANDLE pipeHandle;

    /**
     * True when this controller is driving an mpv it did not start - the one
     * Seanime launched, for instance.
     *
     * <p>mpv's IPC socket accepts several clients at once, so cowatch can sit
     * alongside whatever launched the player and both read and write it. What it
     * must not do in that state is take ownership: no killing the process it does
     * not own, and no deleting the socket file another program is listening on.
     */
    private volatile boolean attached = false;

    /** Liveness for the attached case, where there is no Process to poll. */
    private volatile boolean connectionAlive = false;

    /** Released when the reader thread sees the IPC stream end. */
    private volatile CountDownLatch connectionClosed = new CountDownLatch(1);

    private OutputStream  writeStream;
    private SocketChannel unixChannel;
    private final Object  writeLock = new Object();

    private ExecutorService readerExecutor       = newReaderExecutor();
    private ExecutorService writerExecutor       = newWriterExecutor();
    private ExecutorService writeTimeoutExecutor = newTimeoutExecutor();

    private final AtomicInteger       requestId     = new AtomicInteger(1);
    private volatile Consumer<String> eventListener;

    // Temp playlist file written for multi-URL launches
    private Path tempPlaylistFile;

    // ── Executor factories ────────────────────────────────────────

    private static ExecutorService newReaderExecutor() {
        return Executors.newSingleThreadExecutor(Async.namedDaemonFactory("mpv-reader"));
    }
    private static ExecutorService newWriterExecutor() {
        return Executors.newSingleThreadExecutor(Async.namedDaemonFactory("mpv-writer"));
    }
    private static ExecutorService newTimeoutExecutor() {
        return Executors.newCachedThreadPool(Async.namedDaemonFactory("mpv-write-timeout"));
    }

    // ── JNA Kernel32 ─────────────────────────────────────────────

    public interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        int GENERIC_READ         = 0x80000000;
        int GENERIC_WRITE        = 0x40000000;
        int OPEN_EXISTING        = 3;
        int FILE_FLAG_OVERLAPPED = 0x40000000;
        int ERROR_IO_PENDING     = 997;

        WinNT.HANDLE CreateFileW(WString name, int access, int share,
                                 Pointer sec, int disposition, int flags, WinNT.HANDLE tmpl);
        WinNT.HANDLE CreateEventW(Pointer sec, boolean manualReset,
                                  boolean initial, WString name);
        boolean ReadFile(WinNT.HANDLE h, Memory buf, int toRead,
                         Pointer lpBytes, OVL ov);
        boolean WriteFile(WinNT.HANDLE h, byte[] buf, int toWrite,
                          Pointer lpBytes, OVL ov);
        boolean GetOverlappedResult(WinNT.HANDLE h, OVL ov,
                                    int[] transferred, boolean wait);
        boolean ResetEvent(WinNT.HANDLE h);
        boolean CloseHandle(WinNT.HANDLE h);
        int     GetLastError();
    }

    // ── Platform helpers ──────────────────────────────────────────

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
    private static boolean isMac() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }

    private static String getMpvBinary() {
        if (isWindows()) return "mpv.exe";
        String[] candidates = {
                "/opt/homebrew/bin/mpv",
                "/usr/local/bin/mpv",
                "/usr/bin/mpv",
                "/usr/local/sbin/mpv",
                "/snap/bin/mpv"
        };
        for (String p : candidates) {
            if (new File(p).exists()) { log.info("Found MPV at: " + p); return p; }
        }
        log.warning("MPV not found in known paths, falling back to 'mpv'");
        return "mpv";
    }

    // ── LAUNCH ───────────────────────────────────────────────────

    /**
     * Launch MPV with a single file or URL.
     * @return null on success, human-readable error on failure.
     */
    public String launch(String fileOrUrl, boolean startPaused) {
        return launch(List.of(fileOrUrl), 0, startPaused);
    }

    /**
     * Launch MPV with a playlist of files/URLs, starting at the given index.
     * Uses --playlist-start=N so MPV loads all entries but begins at startIndex.
     *
     * @param entries    ordered list of file paths or URLs
     * @param startIndex 0-based index of the episode to start from
     * @param startPaused open paused and wait for play, rather than play at once
     * @return null on success, human-readable error on failure.
     */
    public String launch(List<String> entries, int startIndex, boolean startPaused) {
        if (entries == null || entries.isEmpty()) return "No media entries provided.";
        reset();
        currentSocketPath = (isWindows()
                ? "\\\\.\\pipe\\mpvsocket_" + INSTANCE_ID
                : "/tmp/mpvsocket_" + INSTANCE_ID)
                + "_" + launchCount.incrementAndGet();
        String bin = getMpvBinary();
        if (!isWindows()) {
            File binFile = new File(bin);
            if (!binFile.isAbsolute() || !binFile.exists()) {
                return "MPV not found.\n\n"
                        + "Install MPV and try again:\n"
                        + (isMac()
                        ? "  brew install mpv\n\nIf already installed, check:\n  /opt/homebrew/bin/mpv\n  /usr/local/bin/mpv"
                        : "  sudo apt install mpv\n  # or: sudo pacman -S mpv");
            }
        }

        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(bin);
            cmd.add("--input-ipc-server=" + currentSocketPath);
            if (startPaused) cmd.add("--pause");
            cmd.add("--force-window=immediate");
            cmd.add("--keep-open=yes");
            cmd.add("--quiet");

            if (entries.size() == 1) {
                // Single entry — pass directly, no playlist file needed
                cmd.add(entries.get(0));
            } else {
                // Multiple entries — write an M3U playlist file and use --playlist
                tempPlaylistFile = writeTempPlaylist(entries);
                cmd.add("--playlist=" + tempPlaylistFile.toAbsolutePath());
                cmd.add("--playlist-start=" + Math.max(0, Math.min(startIndex, entries.size() - 1)));
            }

            log.info("Launching MPV: " + String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (!isWindows()) {
                pb.environment().merge("PATH",
                        "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/usr/sbin:/bin:/snap/bin",
                        (existing, extra) -> extra + ":" + existing);
            }
            pb.redirectErrorStream(true);
            mpvProcess = pb.start();

            Thread drainer = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(mpvProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    while (r.readLine() != null) {}
                } catch (IOException ignored) {}
            }, "mpv-stdout-drainer");
            drainer.setDaemon(true);
            drainer.start();

            boolean connected = waitForSocketAndConnect();
            if (connected) connectionAlive = true;
            if (!connected) return "MPV launched but IPC socket connection timed out.\n"
                    + "The file may be invalid or MPV crashed on startup.";
            return null;

        } catch (IOException e) {
            log.severe("Failed to start MPV process: " + e.getMessage());
            return "Failed to start MPV: " + e.getMessage();
        } catch (Exception e) {
            log.severe("Unexpected error launching MPV: " + e.getMessage());
            return "Unexpected error: " + e.getMessage();
        }
    }

    /**
     * Attaches to an mpv that something else already started, over its existing
     * IPC socket, without launching a player of its own.
     *
     * <p>This is how cowatch joins playback a viewer began in Seanime: Seanime
     * runs mpv with {@code --input-ipc-server}, mpv accepts more than one IPC
     * client at a time, and so cowatch can both observe that player and drive
     * it. Sync therefore works in both directions - the room sees his pause, and
     * a pause from the room reaches his player.
     *
     * <p>Which socket to use is the caller's problem: Seanime either uses a fixed
     * name from its settings or generates a fresh one per launch, and
     * {@link com.cowatch.client.seanime.SeanimeClient#mpvSocketCandidates()}
     * works out which.
     *
     * @param socketPath the IPC socket or Windows named pipe mpv is listening on
     * @return null on success, or a human-readable reason it failed
     */
    public String attach(String socketPath) {
        if (socketPath == null || socketPath.isBlank()) {
            return "No mpv socket to attach to." + NL + NL
                 + "Start the episode in Seanime first, with mpv as its media player.";
        }
        reset();

        String path = socketPath.trim();
        // mpv accepts a bare name on Windows and prepends the pipe namespace
        // itself, so a value copied straight out of Seanime's settings may be
        // missing the prefix CreateFileW needs.
        if (isWindows() && !path.startsWith("\\\\")) {
            path = "\\\\.\\pipe\\" + path.replace('/', '\\');
        }
        currentSocketPath = path;
        attached = true;

        if (!isWindows() && !new File(path).exists()) {
            attached = false;
            return "No mpv IPC socket at " + path + "." + NL + NL
                 + "Start playback in Seanime first - the socket only exists while "
                 + "its player is running.";
        }

        // A second client can briefly find every pipe instance busy while mpv
        // spins up the next one, so give it a few tries before giving up.
        boolean connected = false;
        for (int attempt = 0; attempt < 3 && !connected; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(CONNECT_POLL_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            connected = connectIPC();
        }
        if (!connected) {
            attached = false;
            return "Found no mpv listening on " + path + "." + NL + NL
                 + "Start playback in Seanime first, and check the socket name matches "
                 + "the one in its Media Player settings.";
        }
        connectionAlive = true;
        log.info("Attached to existing mpv at " + path);
        return null;
    }

    /**
     * Write a plain-text M3U playlist file to the system temp directory.
     * Each line is one file path or URL. MPV reads this with --playlist=.
     */
    private Path writeTempPlaylist(List<String> entries) throws IOException {
        Path f = Files.createTempFile("cowatch-playlist-", ".m3u");
        f.toFile().deleteOnExit();
        try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
            w.write("#EXTM3U");
            w.newLine();
            for (String entry : entries) {
                w.write(entry);
                w.newLine();
            }
        }
        log.info("Wrote playlist (" + entries.size() + " entries): " + f);
        return f;
    }

    private boolean waitForSocketAndConnect() throws InterruptedException {
        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (new File(currentSocketPath).exists()) return connectIPC();
            Thread.sleep(CONNECT_POLL_MS);
        }
        log.severe("Timed out waiting for MPV socket at " + currentSocketPath);
        return false;
    }

    // ── CONNECT ──────────────────────────────────────────────────

    private boolean connectIPC() {
        try {
            return isWindows() ? connectWindowsPipe() : connectUnixSocket();
        } catch (Exception e) {
            log.severe("IPC connect failed: " + e.getMessage());
            return false;
        }
    }

    private boolean connectUnixSocket() throws IOException {
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(currentSocketPath));
        this.unixChannel = ch;
        startReaderThread(new BufferedReader(
                new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8)));
        subscribeToEvents();
        log.info("Connected Unix socket: " + currentSocketPath);
        return true;
    }

    private boolean connectWindowsPipe() {
        Kernel32 k = Kernel32.INSTANCE;
        WinNT.HANDLE h = k.CreateFileW(
                new WString(currentSocketPath),
                Kernel32.GENERIC_READ | Kernel32.GENERIC_WRITE,
                0, null, Kernel32.OPEN_EXISTING, Kernel32.FILE_FLAG_OVERLAPPED, null);

        int err = k.GetLastError();
        // CreateFileW reports failure as INVALID_HANDLE_VALUE, not null.
        boolean valid = h != null && !WinBase.INVALID_HANDLE_VALUE.equals(h);
        if (!valid || err != 0) {
            log.severe("CreateFile failed, error=" + err);
            if (valid) k.CloseHandle(h);
            return false;
        }
        pipeHandle = h;

        WinNT.HANDLE writeEvent = k.CreateEventW(null, true, false, null);
        OVL writeOVL = new OVL();
        writeOVL.hEvent = writeEvent;
        writeOVL.write();

        OutputStream out = new OutputStream() {
            @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
            @Override public void write(byte[] buf, int off, int len) throws IOException {
                byte[] chunk = (off == 0 && len == buf.length)
                        ? buf : Arrays.copyOfRange(buf, off, off + len);
                k.ResetEvent(writeEvent);
                boolean ok = k.WriteFile(pipeHandle, chunk, chunk.length, null, writeOVL);
                int we = k.GetLastError();
                if (!ok && we != Kernel32.ERROR_IO_PENDING) throw new IOException("WriteFile error=" + we);
                int[] xfer = {0};
                if (!k.GetOverlappedResult(pipeHandle, writeOVL, xfer, true))
                    throw new IOException("GetOverlappedResult(write) error=" + k.GetLastError());
            }
        };

        WinNT.HANDLE readEvent = k.CreateEventW(null, true, false, null);
        OVL readOVL = new OVL();
        readOVL.hEvent = readEvent;
        readOVL.write();

        InputStream in = new InputStream() {
            final Memory tmp = new Memory(4096);
            @Override public int read() throws IOException {
                byte[] b = new byte[1]; int n = read(b, 0, 1); return n == -1 ? -1 : (b[0] & 0xFF);
            }
            @Override public int read(byte[] dst, int off, int len) throws IOException {
                int toRead = Math.min(len, (int) tmp.size());
                k.ResetEvent(readEvent);
                boolean ok = k.ReadFile(pipeHandle, tmp, toRead, null, readOVL);
                int re = k.GetLastError();
                if (!ok && re != Kernel32.ERROR_IO_PENDING) return -1;
                int[] xfer = {0};
                if (!k.GetOverlappedResult(pipeHandle, readOVL, xfer, true)) return -1;
                if (xfer[0] == 0) return -1;
                tmp.read(0, dst, off, xfer[0]);
                return xfer[0];
            }
        };

        synchronized (writeLock) { writeStream = new BufferedOutputStream(out); }
        startReaderThread(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        subscribeToEvents();
        log.info("Connected Windows pipe (overlapped): " + currentSocketPath);
        return true;
    }

    // ── READER THREAD ────────────────────────────────────────────

    private void startReaderThread(BufferedReader reader) {
        readerExecutor.submit(() -> {
            log.info("Reader thread started");
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String t = line.trim();
                    if (!t.isEmpty()) {
                        log.finest("MPV ← " + t);
                        Consumer<String> l = eventListener;
                        if (l != null) l.accept(t);
                    }
                }
                log.warning("MPV reader: stream ended");
            } catch (IOException e) {
                log.warning("MPV reader died: " + e.getMessage());
            } finally {
                // The only exit signal available when attached: mpv closing its
                // end of the socket.
                connectionAlive = false;
                connectionClosed.countDown();
            }
        });
    }

    public void waitForExit() throws InterruptedException {
        if (attached) { connectionClosed.await(); return; }
        if (mpvProcess != null) mpvProcess.waitFor();
    }

    // ── SUBSCRIPTIONS ────────────────────────────────────────────

    private void subscribeToEvents() {
        sendRaw("{\"command\":[\"observe_property\",1,\"time-pos\"]}");
        sendRaw("{\"command\":[\"observe_property\",2,\"pause\"]}");
        sendRaw("{\"command\":[\"observe_property\",3,\"duration\"]}");
        // Track playlist position so UI can show "next episode" state
        sendRaw("{\"command\":[\"observe_property\",4,\"playlist-pos\"]}");
        sendRaw("{\"command\":[\"observe_property\",5,\"playlist-count\"]}");
        // What is actually loaded. Only cowatch-launched playback knows this
        // from its own playlist; when attached to someone else's mpv it is the
        // only way to find out what is on screen.
        sendRaw("{\"command\":[\"observe_property\",6,\"path\"]}");
        sendRaw("{\"command\":[\"observe_property\",7,\"media-title\"]}");
    }

    // ── PLAYBACK CONTROLS ────────────────────────────────────────

    public void play()  { sendRaw("{\"command\":[\"set_property\",\"pause\",false]}"); log.info("MPV: play"); }
    public void pause() { sendRaw("{\"command\":[\"set_property\",\"pause\",true]}");  log.info("MPV: pause"); }

    public void seekTo(double s) {
        sendRaw("{\"command\":[\"seek\"," + s + ",\"absolute\"]}");
        log.info("MPV: seek → " + s);
    }

    public void nextChapter() {
        sendRaw("{\"command\":[\"playlist-next\",\"weak\"]}");
        log.info("MPV: playlist-next");
    }

    public void prevChapter() {
        sendRaw("{\"command\":[\"playlist-prev\",\"weak\"]}");
        log.info("MPV: playlist-prev");
    }

    public void requestPosition() {
        int id = requestId.getAndIncrement();
        sendRaw("{\"command\":[\"get_property\",\"time-pos\"],\"request_id\":" + id + "}");
    }

    public void loadFile(String f) {
        sendRaw("{\"command\":[\"loadfile\",\"" + escapeJson(f) + "\"]}");
    }

    // ── SEND ─────────────────────────────────────────────────────

    public void sendRaw(String json) {
        String clean = json.strip();
        if (clean.isEmpty()) return;
        writerExecutor.submit(() -> {
            try {
                byte[] bytes = (clean + "\n").getBytes(StandardCharsets.UTF_8);
                if (unixChannel != null) {
                    ByteBuffer buf = ByteBuffer.wrap(bytes);
                    while (buf.hasRemaining()) unixChannel.write(buf);
                    log.finest("sendRaw ✓ → " + clean);
                } else {
                    synchronized (writeLock) {
                        if (writeStream == null) { log.warning("sendRaw: null stream, dropping: " + clean); return; }
                        writeStream.write(bytes);
                        writeStream.flush();
                        log.finest("sendRaw ✓ → " + clean);
                    }
                }
            } catch (Exception e) {
                log.warning("sendRaw FAILED: " + e.getMessage() + " | " + clean);
            }
        });
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── STATE ─────────────────────────────────────────────────────

    public void setEventListener(Consumer<String> l) { this.eventListener = l; }
    /**
     * True while there is a player to control.
     *
     * <p>An attached player has no Process to poll, so its liveness is the state
     * of the IPC connection instead.
     */
    public boolean isRunning() {
        if (attached) return connectionAlive;
        return mpvProcess != null && mpvProcess.isAlive();
    }

    /** True when driving an mpv started by something else. */
    public boolean isAttached() { return attached; }

    // ── RESET + SHUTDOWN ─────────────────────────────────────────

    private void reset() {
        readerExecutor.shutdownNow();
        writerExecutor.shutdownNow();
        writeTimeoutExecutor.shutdownNow();

        try { if (unixChannel != null) unixChannel.close(); } catch (Exception ignored) {}
        unixChannel = null;

        synchronized (writeLock) {
            try { if (writeStream != null) writeStream.close(); } catch (Exception ignored) {}
            writeStream = null;
        }

        if (pipeHandle != null) {
            try { Kernel32.INSTANCE.CloseHandle(pipeHandle); } catch (Exception ignored) {}
            pipeHandle = null;
        }

        if (mpvProcess != null) { mpvProcess.destroy(); mpvProcess = null; }

        // Delete temp playlist if any
        if (tempPlaylistFile != null) {
            try { Files.deleteIfExists(tempPlaylistFile); } catch (Exception ignored) {}
            tempPlaylistFile = null;
        }

        // Only clean up a socket this controller created. Deleting the one
        // Seanime's mpv is listening on would break that player.
        if (!isWindows() && !attached) {
            try { Files.deleteIfExists(Path.of(currentSocketPath)); } catch (Exception ignored) {}
        }
        attached        = false;
        connectionAlive = false;
        connectionClosed.countDown();   // release anyone waiting in waitForExit()
        connectionClosed = new CountDownLatch(1);
        if (mpvProcess != null) {
            mpvProcess.destroy();
            try { mpvProcess.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            mpvProcess = null;
        }
        readerExecutor       = newReaderExecutor();
        writerExecutor       = newWriterExecutor();
        writeTimeoutExecutor = newTimeoutExecutor();
    }

    public void close() { reset(); }
}