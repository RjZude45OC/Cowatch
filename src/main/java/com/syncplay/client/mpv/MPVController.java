package com.syncplay.client.mpv;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.*;

/**
 * Controls MPV via JSON IPC.
 *
 * Windows : Named pipe  →  \\.\pipe\mpvsocket
 * Linux / macOS : Unix socket  →  /tmp/mpvsocket
 */
public class MPVController {

    private static final Logger log = Logger.getLogger(MPVController.class.getName());

    private static final String SOCKET_PATH = getSocketPath();
    private static final String MPV_BIN     = getMpvBinary();

    /** How long to wait for the socket/pipe to appear after MPV starts. */
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int CONNECT_POLL_MS    = 200;

    private Process mpvProcess;

    // Written once after connect; read from two threads (reader + ipcExecutor).
    // Guarded by synchronized(writeLock).
    private OutputStream writeStream;
    private final Object writeLock = new Object();

    private final ExecutorService readerExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mpv-reader");
                t.setDaemon(true);
                return t;
            });

    private final ExecutorService ipcExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mpv-ipc-writer");
                t.setDaemon(true);
                return t;
            });

    private final AtomicInteger requestId = new AtomicInteger(1);

    /** Called on every JSON line received from MPV (reader thread). */
    private volatile Consumer<String> eventListener;

    // ─────────────────────────────────────────────────────────────

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String getSocketPath() {
        return isWindows() ? "\\\\.\\pipe\\mpvsocket" : "/tmp/mpvsocket";
    }

    private static String getMpvBinary() {
        return isWindows() ? "mpv.exe" : "mpv";
    }

    // ─────────────────────────────────────────────────────────────
    // LAUNCH
    // ─────────────────────────────────────────────────────────────

    public boolean launch(String fileOrUrl) {
        try {
            List<String> cmd = new ArrayList<>(Arrays.asList(
                    MPV_BIN,
                    "--input-ipc-server=" + SOCKET_PATH,
                    "--pause",
                    "--quiet",
                    fileOrUrl
            ));

            log.info("Launching MPV: " + String.join(" ", cmd));

            mpvProcess = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();

            // Drain MPV's stdout/stderr so it doesn't block on a full pipe buffer
            Thread stdoutDrainer = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(mpvProcess.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        log.finest("[mpv-stdout] " + line);
                    }
                } catch (IOException ignored) {}
            }, "mpv-stdout-drainer");
            stdoutDrainer.setDaemon(true);
            stdoutDrainer.start();

            return waitForSocketAndConnect();

        } catch (Exception e) {
            log.severe("Failed to launch MPV: " + e.getMessage());
            return false;
        }
    }

    /**
     * Polls until the socket/pipe is available (MPV creates it asynchronously),
     * then connects. Avoids a hard-coded sleep.
     */
    private boolean waitForSocketAndConnect() throws InterruptedException {
        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (socketExists()) {
                return connectIPC();
            }
            Thread.sleep(CONNECT_POLL_MS);
        }

        log.severe("Timed out waiting for MPV socket: " + SOCKET_PATH);
        return false;
    }

    private boolean socketExists() {
        if (isWindows()) {
            // On Windows the pipe appears as a file; just try to stat it
            return new File(SOCKET_PATH).exists();
        } else {
            return new File(SOCKET_PATH).exists();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // CONNECT
    // ─────────────────────────────────────────────────────────────

    private boolean connectIPC() {
        try {
            if (isWindows()) {
                return connectWindowsPipe();
            } else {
                return connectUnixSocket();
            }
        } catch (Exception e) {
            log.severe("Failed to connect IPC: " + e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // UNIX SOCKET  (Java 16+ UnixDomainSocket)
    // ─────────────────────────────────────────────────────────────

    private boolean connectUnixSocket() throws IOException {
        UnixDomainSocketAddress address =
                UnixDomainSocketAddress.of(SOCKET_PATH);

        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(address);

        // Use Channels.newInputStream/newOutputStream — channel.socket() is NOT
        // supported for Unix-domain SocketChannels on Java 16+
        InputStream  in  = Channels.newInputStream(channel);
        OutputStream out = Channels.newOutputStream(channel);

        synchronized (writeLock) {
            writeStream = out;
        }

        startReaderThread(new BufferedReader(new InputStreamReader(in)));
        subscribeToEvents();

        log.info("Connected to MPV Unix socket: " + SOCKET_PATH);
        return true;
    }

    // ─────────────────────────────────────────────────────────────
    // WINDOWS NAMED PIPE
    //
    // MPV creates the pipe as a byte-stream pipe.  We need TWO separate
    // native file handles — one opened for reading, one for writing —
    // because a single RandomAccessFile handle in "rw" mode causes
    // read() and write() to share a file pointer, which corrupts IPC.
    //
    // FileInputStream / FileOutputStream each open the pipe independently
    // (each gets its own native HANDLE) which is the correct approach.
    // ─────────────────────────────────────────────────────────────

    private boolean connectWindowsPipe() throws IOException {
        // Open two separate handles to the same pipe
        FileOutputStream pipeOut = new FileOutputStream(SOCKET_PATH);
        FileInputStream  pipeIn  = new FileInputStream(SOCKET_PATH);

        synchronized (writeLock) {
            writeStream = new BufferedOutputStream(pipeOut);
        }

        startReaderThread(new BufferedReader(new InputStreamReader(pipeIn)));
        subscribeToEvents();

        log.info("Connected to MPV Windows pipe: " + SOCKET_PATH);
        return true;
    }

    // ─────────────────────────────────────────────────────────────
    // READER THREAD
    // ─────────────────────────────────────────────────────────────

    private void startReaderThread(BufferedReader reader) {
        readerExecutor.submit(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        Consumer<String> listener = eventListener;
                        if (listener != null) {
                            listener.accept(trimmed);
                        }
                    }
                }
            } catch (IOException e) {
                log.warning("MPV reader closed: " + e.getMessage());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // EVENT SUBSCRIPTIONS
    // ─────────────────────────────────────────────────────────────

    private void subscribeToEvents() {
        // observe_property is safe to call immediately after connection;
        // MPV queues the command even if playback hasn't started yet.
        sendRaw("{\"command\":[\"observe_property\",1,\"time-pos\"]}");
        sendRaw("{\"command\":[\"observe_property\",2,\"pause\"]}");
        sendRaw("{\"command\":[\"observe_property\",3,\"duration\"]}");
    }

    // ─────────────────────────────────────────────────────────────
    // PLAYBACK CONTROL
    // ─────────────────────────────────────────────────────────────

    public void play() {
        sendCommand("set_property", "pause", false);
        log.info("MPV: play");
    }

    public void pause() {
        sendCommand("set_property", "pause", true);
        log.info("MPV: pause");
    }

    public void seekTo(double positionSeconds) {
        sendCommand("seek", positionSeconds, "absolute");
        log.info("MPV: seek → " + positionSeconds);
    }

    public void requestPosition() {
        sendCommandWithId("get_property", "time-pos");
    }

    public void loadFile(String fileOrUrl) {
        sendCommand("loadfile", fileOrUrl);
    }

    // ─────────────────────────────────────────────────────────────
    // COMMAND BUILDERS
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds {"command":[...]} — no request_id (fire-and-forget).
     */
    private void sendCommand(Object... args) {
        sendRaw(buildCommand(false, args));
    }

    /**
     * Builds {"command":[...],"request_id":N} — reply will carry the same id.
     */
    private void sendCommandWithId(Object... args) {
        int id = requestId.getAndIncrement();
        sendRaw(buildCommand(true, args) + buildRequestId(id));
        // Note: full JSON is assembled in buildRawWithId; keeping separate for clarity
        sendRaw("{\"command\":[\"get_property\",\"time-pos\"],\"request_id\":"
                + id + "}");
    }

    private String buildCommand(boolean withId, Object... args) {
        StringBuilder sb = new StringBuilder("{\"command\":[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(",");
            Object arg = args[i];
            if (arg instanceof String) {
                sb.append("\"").append(arg).append("\"");
            } else if (arg instanceof Boolean) {
                sb.append(arg);
            } else {
                sb.append(arg);
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildRequestId(int id) {
        return ",\"request_id\":" + id + "}";
    }

    // ─────────────────────────────────────────────────────────────
    // THREAD-SAFE WRITE
    // ─────────────────────────────────────────────────────────────

    /**
     * Enqueues a raw JSON string to be sent to MPV.
     * The string must NOT contain newlines (MPV uses newline as message delimiter).
     * A trailing newline is appended here.
     */
    public void sendRaw(String json) {
        // Trim the string — text blocks may have leading/trailing whitespace
        String clean = json.strip();
        if (clean.isEmpty()) return;

        ipcExecutor.submit(() -> {
            synchronized (writeLock) {
                if (writeStream == null) return;
                try {
                    writeStream.write((clean + "\n").getBytes());
                    writeStream.flush();
                } catch (Exception e) {
                    log.warning("Failed to send MPV command: " + e.getMessage());
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────

    public void setEventListener(Consumer<String> listener) {
        this.eventListener = listener;
    }

    public boolean isRunning() {
        return mpvProcess != null && mpvProcess.isAlive();
    }

    // ─────────────────────────────────────────────────────────────
    // SHUTDOWN
    // ─────────────────────────────────────────────────────────────

    public void close() {
        readerExecutor.shutdownNow();
        ipcExecutor.shutdownNow();

        synchronized (writeLock) {
            try {
                if (writeStream != null) writeStream.close();
            } catch (Exception ignored) {}
            writeStream = null;
        }

        if (mpvProcess != null) {
            mpvProcess.destroy();
        }
    }
}