package com.syncplay.client.mpv;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
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

    private Process      mpvProcess;
    private PrintWriter  ipcWriter;
    private OutputStream rawOut;
    private InputStream  rawIn;

    private final ExecutorService    executor  = Executors.newSingleThreadExecutor();
    private final AtomicInteger      reqId     = new AtomicInteger(1);
    private Consumer<String>         eventListener;

    // ─── OS Detection ────────────────────────────────────────────────────

    private static String getSocketPath() {
        return isWindows() ? "\\\\.\\pipe\\mpvsocket" : "/tmp/mpvsocket";
    }

    private static String getMpvBinary() {
        return isWindows() ? "mpv.exe" : "mpv";
    }

    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    // ─── Launch ──────────────────────────────────────────────────────────

    public boolean launch(String fileOrUrl) {
        try {
            List<String> cmd = new ArrayList<>(Arrays.asList(
                MPV_BIN,
                "--input-ipc-server=" + SOCKET_PATH,
                "--pause",   // always start paused — sync engine decides when to play
                "--quiet",
                fileOrUrl
            ));
            log.info("Launching MPV: " + String.join(" ", cmd));
            mpvProcess = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();

            Thread.sleep(1500); // wait for socket to be created
            return connectIPC();

        } catch (Exception e) {
            log.severe("Failed to launch MPV: " + e.getMessage());
            return false;
        }
    }

    // ─── IPC Connection ──────────────────────────────────────────────────

    private boolean connectIPC() {
        try {
            if (isWindows()) return connectWindowsPipe();
            else             return connectUnixSocket();
        } catch (Exception e) {
            log.severe("IPC connect failed: " + e.getMessage());
            return false;
        }
    }

    private boolean connectUnixSocket() throws IOException {
        UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(SOCKET_PATH);
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(addr);
        rawOut    = channel.socket().getOutputStream();
        rawIn     = channel.socket().getInputStream();
        ipcWriter = new PrintWriter(new OutputStreamWriter(rawOut), true);
        startReader(new BufferedReader(new InputStreamReader(rawIn)));
        subscribeToEvents();
        log.info("MPV IPC connected (Unix): " + SOCKET_PATH);
        return true;
    }

    private boolean connectWindowsPipe() throws IOException {
        RandomAccessFile pipe = new RandomAccessFile(SOCKET_PATH, "rw");
        rawOut = new OutputStream() {
            public void write(int b)                        throws IOException { pipe.write(b); }
            public void write(byte[] b)                     throws IOException { pipe.write(b); }
            public void write(byte[] b, int off, int len)   throws IOException { pipe.write(b, off, len); }
        };
        rawIn = new InputStream() {
            public int read()           throws IOException { return pipe.read(); }
            public int read(byte[] b)   throws IOException { return pipe.read(b); }
        };
        ipcWriter = new PrintWriter(new OutputStreamWriter(rawOut), true);
        startReader(new BufferedReader(new InputStreamReader(rawIn)));
        subscribeToEvents();
        log.info("MPV IPC connected (Windows pipe): " + SOCKET_PATH);
        return true;
    }

    private void startReader(BufferedReader reader) {
        executor.submit(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String t = line.trim();
                    if (!t.isEmpty() && eventListener != null) eventListener.accept(t);
                }
            } catch (IOException e) {
                log.warning("MPV reader closed: " + e.getMessage());
            }
        });
    }

    private void subscribeToEvents() {
        // Push time-pos every ~250ms and instantly on seek
        sendRaw("{\"command\":[\"observe_property\",1,\"time-pos\"],\"request_id\":" + reqId.getAndIncrement() + "}");
        // Push pause state changes immediately
        sendRaw("{\"command\":[\"observe_property\",2,\"pause\"],\"request_id\":" + reqId.getAndIncrement() + "}");
    }

    // ─── Playback Control ────────────────────────────────────────────────

    /**
     * Play — sends the correct MPV IPC command.
     * Uses "cycle pause" trick which is more reliable than set_property
     * when MPV might be in a transitional state.
     */
    public void play() {
        // set_property is the right call; ensure pause=false
        sendRaw("{\"command\":[\"set_property\",\"pause\",false],\"request_id\":"
            + reqId.getAndIncrement() + "}");
        log.info("MPV → play");
    }

    public void pause() {
        sendRaw("{\"command\":[\"set_property\",\"pause\",true],\"request_id\":"
            + reqId.getAndIncrement() + "}");
        log.info("MPV → pause");
    }

    public void seekTo(double seconds) {
        // "absolute" seek, keyframes=false for frame-accurate
        sendRaw("{\"command\":[\"seek\"," + String.format("%.3f", seconds)
            + ",\"absolute\",\"exact\"],\"request_id\":" + reqId.getAndIncrement() + "}");
        log.info("MPV → seek " + seconds);
    }

    public void requestPosition() {
        sendRaw("{\"command\":[\"get_property\",\"time-pos\"],\"request_id\":"
            + reqId.getAndIncrement() + "}");
    }

    public void loadFile(String fileOrUrl) {
        sendRaw("{\"command\":[\"loadfile\",\"" + fileOrUrl.replace("\\","\\\\") + "\"],\"request_id\":"
            + reqId.getAndIncrement() + "}");
    }

    // ─── Offset seek (for mismatch correction) ───────────────────────────

    /**
     * Seek relative to current position.
     * Positive = forward, negative = backward.
     */
    public void seekRelative(double offsetSeconds) {
        sendRaw("{\"command\":[\"seek\"," + String.format("%.3f", offsetSeconds)
            + ",\"relative\",\"exact\"],\"request_id\":" + reqId.getAndIncrement() + "}");
        log.info("MPV → seek relative " + offsetSeconds);
    }

    // ─── Raw send ────────────────────────────────────────────────────────

    private void sendRaw(String json) {
        if (ipcWriter != null) {
            ipcWriter.println(json);
            log.fine("→ MPV: " + json);
        } else {
            log.warning("sendRaw called but ipcWriter is null — MPV not connected yet");
        }
    }

    // ─── Event listener ──────────────────────────────────────────────────

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
