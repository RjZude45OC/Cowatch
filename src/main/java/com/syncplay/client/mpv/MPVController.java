package com.syncplay.client.mpv;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.*;

/**
 * Controls MPV via its JSON IPC socket.
 *
 * Cross-platform:
 *   Windows   → Named pipe:   \\.\pipe\mpvsocket
 *   Mac/Linux → Unix socket:  /tmp/mpvsocket
 */
public class MPVController {

    private static final Logger log = Logger.getLogger(MPVController.class.getName());

    private static final String SOCKET_PATH = getSocketPath();
    private static final String MPV_BIN     = getMpvBinary();

    private Process     mpvProcess;
    private PrintWriter ipcWriter;
    private OutputStream rawOut;        // for Windows pipe
    private InputStream  rawIn;         // for Windows pipe

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Consumer<String> eventListener;
    private int requestId = 1;

    // ─── OS Detection ────────────────────────────────────────────────────

    private static String getSocketPath() {
        return isWindows() ? "\\\\.\\pipe\\mpvsocket" : "/tmp/mpvsocket";
    }

    private static String getMpvBinary() {
        return isWindows() ? "mpv.exe" : "mpv";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    // ─── Launch MPV ──────────────────────────────────────────────────────

    /**
     * Launches MPV with IPC enabled, then connects to its socket.
     * @param fileOrUrl local file path or HTTP stream URL (e.g. Jellyfin)
     */
    public boolean launch(String fileOrUrl) {
        try {
            List<String> cmd = new ArrayList<>(Arrays.asList(
                MPV_BIN,
                "--input-ipc-server=" + SOCKET_PATH,
                "--pause",    // start paused so users can sync before playing
                "--quiet",
                fileOrUrl
            ));

            log.info("Launching MPV: " + String.join(" ", cmd));
            mpvProcess = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();

            // Give MPV time to create the socket
            Thread.sleep(1500);
            return connectIPC();

        } catch (Exception e) {
            log.severe("Failed to launch MPV: " + e.getMessage());
            return false;
        }
    }

    // ─── IPC Connection ──────────────────────────────────────────────────

    private boolean connectIPC() {
        try {
            if (isWindows()) {
                return connectWindowsPipe();
            } else {
                return connectUnixSocket();
            }
        } catch (Exception e) {
            log.severe("Failed to connect to MPV IPC: " + e.getMessage());
            return false;
        }
    }

    /** Mac + Linux: Unix domain socket via Java NIO (Java 16+) */
    private boolean connectUnixSocket() throws IOException {
        UnixDomainSocketAddress address =
            UnixDomainSocketAddress.of(SOCKET_PATH);

        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(address);

        rawOut   = channel.socket().getOutputStream();
        rawIn    = channel.socket().getInputStream();
        ipcWriter = new PrintWriter(new OutputStreamWriter(rawOut), true);

        startReaderThread(new BufferedReader(new InputStreamReader(rawIn)));
        subscribeToEvents();
        log.info("Connected to MPV Unix socket: " + SOCKET_PATH);
        return true;
    }

    /** Windows: named pipe via RandomAccessFile */
//    private boolean connectWindowsPipe() throws IOException {
//        RandomAccessFile pipe = new RandomAccessFile(SOCKET_PATH, "rw");
//
//        // Wrap pipe streams
//        rawOut = new OutputStream() {
//            public void write(int b)    throws IOException { pipe.write(b); }
//            public void write(byte[] b) throws IOException { pipe.write(b); }
//            public void write(byte[] b, int off, int len) throws IOException {
//                pipe.write(b, off, len);
//            }
//        };
//        rawIn = new InputStream() {
//            public int read()             throws IOException { return pipe.read(); }
//            public int read(byte[] b)     throws IOException { return pipe.read(b); }
//        };
//
//        ipcWriter = new PrintWriter(new OutputStreamWriter(rawOut), true);
//        startReaderThread(new BufferedReader(new InputStreamReader(rawIn)));
//        subscribeToEvents();
//        log.info("Connected to MPV Windows pipe: " + SOCKET_PATH);
//        return true;
//    }
        private boolean connectWindowsPipe() throws IOException {
            // RandomAccessFile on named pipes is unreliable for bidirectional IPC on Windows.
            // Use separate FileInputStream/FileOutputStream instead.

            // MPV's pipe is a byte-stream pipe — open write end first, then read
            FileOutputStream pipeOut = new FileOutputStream(SOCKET_PATH);
            FileInputStream  pipeIn  = new FileInputStream(SOCKET_PATH);

            rawOut    = pipeOut;
            rawIn     = pipeIn;
            ipcWriter = new PrintWriter(new OutputStreamWriter(pipeOut, "UTF-8"), true);

            startReaderThread(new BufferedReader(new InputStreamReader(pipeIn, "UTF-8")));
            subscribeToEvents();
            log.info("Connected to MPV Windows pipe: " + SOCKET_PATH);
            return true;
        }

    private void startReaderThread(BufferedReader reader) {
        executor.submit(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && eventListener != null) {
                        eventListener.accept(trimmed);
                    }
                }
            } catch (IOException e) {
                log.warning("MPV IPC read error: " + e.getMessage());
            }
        });
    }

    /**
     * Subscribe to time-pos property changes.
     * MPV will push updates every ~1 second and on every seek.
     * This is how we detect skips done directly inside MPV.
     */
    private void subscribeToEvents() {
        sendRaw("{\"command\":[\"observe_property\",1,\"time-pos\"],\"request_id\":" + requestId++ + "}");
        sendRaw("{\"command\":[\"observe_property\",2,\"pause\"],\"request_id\":" + requestId++ + "}");

        // Send a test command and log the reply — if you never see this, pipe writes are dead
        sendRaw("{\"command\":[\"get_version\"],\"request_id\":9999}");
    }

    // ─── Playback Control ────────────────────────────────────────────────

    public void play() {
        log.info("click play");
        sendCommand("set_property", "pause", false);
        log.info("MPV: play");
    }

    public void pause() {
        sendCommand("set_property", "pause", true);
        log.info("MPV: pause");
    }

    /** Seek to an absolute position in seconds */
    public void seekTo(double positionSeconds) {
        sendCommand("seek", positionSeconds, "absolute");
        log.info("MPV: seek to " + positionSeconds);
    }

    /** Ask MPV for current position — result arrives via eventListener */
    public void requestPosition() {
        sendCommand("get_property", "time-pos");
    }

    /** Open a new file/URL without restarting MPV */
    public void loadFile(String fileOrUrl) {
        sendCommand("loadfile", fileOrUrl);
    }

    // ─── IPC Message Builders ─────────────────────────────────────────────

    private void sendCommand(Object... args) {
        StringBuilder sb = new StringBuilder("{\"command\":[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(",");
            Object a = args[i];
            if (a instanceof String)  sb.append("\"").append(a).append("\"");
            else if (a instanceof Boolean) sb.append(a);
            else sb.append(a);
        }
        sb.append("],\"request_id\":").append(requestId++).append("}");
        sendRaw(sb.toString());
    }

//    private void sendRaw(String json) {
//        if (ipcWriter != null) ipcWriter.println(json);
//    }
//    private void sendRaw(String json) {
//        if (ipcWriter != null) {
//            ipcWriter.println(json);
//            ipcWriter.flush(); // force flush — critical on Windows named pipes
//            log.fine("IPC sent: " + json);  // add this so you can confirm sends in log
//        }
//    }
        private void sendRaw(String json) {
            executor.submit(() -> {
                try {
                    synchronized (rawOut) {
                        rawOut.write((json + "\n").getBytes());
                        rawOut.flush();
                    }
                } catch (IOException e) {
                    log.warning("Failed to send MPV command: " + e.getMessage());
                }
            });
        }
    // ─── Event Listener ──────────────────────────────────────────────────

    /** Receives raw JSON lines pushed by MPV (property changes, events, replies) */
    public void setEventListener(Consumer<String> listener) {
        this.eventListener = listener;
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────

    public void close() {
        executor.shutdownNow();
        try { if (rawOut != null) rawOut.close(); } catch (IOException ignored) {}
        try { if (rawIn  != null) rawIn.close();  } catch (IOException ignored) {}
        if (mpvProcess != null) mpvProcess.destroy();
    }

    public boolean isRunning() {
        return mpvProcess != null && mpvProcess.isAlive();
    }
}
