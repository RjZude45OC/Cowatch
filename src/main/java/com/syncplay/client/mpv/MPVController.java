package com.syncplay.client.mpv;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;

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

public class MPVController {

    private static final Logger log = Logger.getLogger(MPVController.class.getName());

    private static final String INSTANCE_ID  = String.valueOf(ProcessHandle.current().pid());
    private static final String SOCKET_PATH  = isWindows()
            ? "\\\\.\\pipe\\mpvsocket_" + INSTANCE_ID
            : "/tmp/mpvsocket_" + INSTANCE_ID;
    private static final String MPV_BIN            = getMpvBinary();
    private static final int    CONNECT_TIMEOUT_MS = 5_000;
    private static final int    CONNECT_POLL_MS    = 200;

    private Process      mpvProcess;
    private WinNT.HANDLE pipeHandle;

    private OutputStream writeStream;
    private final Object writeLock = new Object();

    private final ExecutorService readerExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mpv-reader");
                t.setDaemon(true);
                return t;
            });

    private final AtomicInteger       requestId     = new AtomicInteger(1);
    private volatile Consumer<String> eventListener;

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

    // ── LAUNCH ───────────────────────────────────────────────────

    public boolean launch(String fileOrUrl) {
        try {
            List<String> cmd = new ArrayList<>(Arrays.asList(
                    MPV_BIN,
                    "--input-ipc-server=" + SOCKET_PATH,
                    "--pause",
                    "--force-window=immediate",
                    "--keep-open=yes",
                    "--quiet",
                    fileOrUrl));
            log.info("Launching MPV: " + String.join(" ", cmd));

            mpvProcess = new ProcessBuilder(cmd).redirectErrorStream(true).start();

            Thread drainer = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(mpvProcess.getInputStream()))) {
                    while (r.readLine() != null) {}
                } catch (IOException ignored) {}
            }, "mpv-stdout-drainer");
            drainer.setDaemon(true);
            drainer.start();

            return waitForSocketAndConnect();
        } catch (Exception e) {
            log.severe("Failed to launch MPV: " + e.getMessage());
            return false;
        }
    }

    private boolean waitForSocketAndConnect() throws InterruptedException {
        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (new File(SOCKET_PATH).exists()) return connectIPC();
            Thread.sleep(CONNECT_POLL_MS);
        }
        log.severe("Timed out waiting for MPV socket");
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

//    private boolean connectUnixSocket() throws IOException {
//        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
//        ch.connect(UnixDomainSocketAddress.of(SOCKET_PATH));
//
//        synchronized (writeLock) {
//            writeStream = Channels.newOutputStream(ch);
//        }
//        startReaderThread(new BufferedReader(
//                new InputStreamReader(Channels.newInputStream(ch))));
//        subscribeToEvents();
//        return true;
//    }
    private boolean connectUnixSocket() throws IOException {
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(SOCKET_PATH));

        synchronized (writeLock) {
            writeStream = new BufferedOutputStream(Channels.newOutputStream(ch));
        }
        startReaderThread(new BufferedReader(
                new InputStreamReader(Channels.newInputStream(ch))));
        subscribeToEvents();
        log.info("Connected Unix socket: " + SOCKET_PATH);
        return true;
    }
    private boolean connectWindowsPipe() {
        Kernel32 k = Kernel32.INSTANCE;

        WinNT.HANDLE h = k.CreateFileW(
                new WString(SOCKET_PATH),
                Kernel32.GENERIC_READ | Kernel32.GENERIC_WRITE,
                0, null,
                Kernel32.OPEN_EXISTING,
                Kernel32.FILE_FLAG_OVERLAPPED,
                null);

        int err = k.GetLastError();
        if (h == null || err != 0) {
            log.severe("CreateFile failed, error=" + err);
            return false;
        }
        pipeHandle = h;

        // Write stream — own OVERLAPPED + event
        WinNT.HANDLE writeEvent = k.CreateEventW(null, true, false, null);
        OVL writeOVL = new OVL();
        writeOVL.hEvent = writeEvent;
        writeOVL.write();

        OutputStream out = new OutputStream() {
            @Override public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }
            @Override public void write(byte[] buf, int off, int len) throws IOException {
                byte[] chunk = (off == 0 && len == buf.length)
                        ? buf : Arrays.copyOfRange(buf, off, off + len);
                k.ResetEvent(writeEvent);
                boolean ok = k.WriteFile(pipeHandle, chunk, chunk.length, null, writeOVL);
                int we = k.GetLastError();
                if (!ok && we != Kernel32.ERROR_IO_PENDING)
                    throw new IOException("WriteFile error=" + we);
                int[] xfer = {0};
                if (!k.GetOverlappedResult(pipeHandle, writeOVL, xfer, true))
                    throw new IOException("GetOverlappedResult(write) error=" + k.GetLastError());
            }
        };

        // Read stream — own OVERLAPPED + event
        WinNT.HANDLE readEvent = k.CreateEventW(null, true, false, null);
        OVL readOVL = new OVL();
        readOVL.hEvent = readEvent;
        readOVL.write();

        InputStream in = new InputStream() {
            final Memory tmp = new Memory(4096);
            @Override public int read() throws IOException {
                byte[] b = new byte[1];
                int n = read(b, 0, 1);
                return n == -1 ? -1 : (b[0] & 0xFF);
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

        synchronized (writeLock) {
            writeStream = new BufferedOutputStream(out);
        }
        startReaderThread(new BufferedReader(new InputStreamReader(in)));
        subscribeToEvents();
        log.info("Connected Windows pipe (overlapped): " + SOCKET_PATH);
        return true;
    }

    // ── READER THREAD ────────────────────────────────────────────

    private void startReaderThread(BufferedReader reader) {
        readerExecutor.submit(() -> {
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
            }
        });
    }
    public void waitForExit() throws InterruptedException {
        if (mpvProcess != null) mpvProcess.waitFor();
    }
    // ── SUBSCRIPTIONS ────────────────────────────────────────────

    private void subscribeToEvents() {
        sendRaw("{\"command\":[\"observe_property\",1,\"time-pos\"]}");
        sendRaw("{\"command\":[\"observe_property\",2,\"pause\"]}");
        sendRaw("{\"command\":[\"observe_property\",3,\"duration\"]}");
    }

    // ── PLAYBACK CONTROLS ────────────────────────────────────────

    public void play()  {
        sendRaw("{\"command\":[\"set_property\",\"pause\",false]}");
        log.info("MPV: play");
    }
    public void pause() {
        sendRaw("{\"command\":[\"set_property\",\"pause\",true]}");
        log.info("MPV: pause");
    }
    public void seekTo(double s) {
        sendRaw("{\"command\":[\"seek\"," + s + ",\"absolute\"]}");
        log.info("MPV: seek → " + s);
    }
    public void requestPosition() {
        int id = requestId.getAndIncrement();
        sendRaw("{\"command\":[\"get_property\",\"time-pos\"],\"request_id\":" + id + "}");
    }
    public void loadFile(String f) {
        sendRaw("{\"command\":[\"loadfile\",\"" + escapeJson(f) + "\"]}");
    }

//    public void sendRaw(String json) {
//        String clean = json.strip();
//        if (clean.isEmpty()) return;
//        synchronized (writeLock) {
//            log.info(String.valueOf(writeStream == null));
//            if (writeStream == null) return;
//            try {
//                writeStream.write((clean + "\n").getBytes());
//                writeStream.flush();
//            } catch (Exception e) {
//                log.warning("Failed to send MPV command: " + e.getMessage());
//            }
//        }
//    }
    public void sendRaw(String json) {
        String clean = json.strip();
        if (clean.isEmpty()) return;
        synchronized (writeLock) {
            if (writeStream == null) { log.warning("sendRaw: null stream"); return; }
            try {
                long t = System.currentTimeMillis();
                writeStream.write((clean + "\n").getBytes());
                writeStream.flush();
                long ms = System.currentTimeMillis() - t;
                if (ms > 100) log.warning("sendRaw SLOW " + ms + "ms: " + clean);
                else log.info("sendRaw ✓ " + ms + "ms → " + clean);
            } catch (Exception e) {
                log.warning("sendRaw FAILED: " + e.getMessage() + " | " + clean);
            }
        }
    }
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public void setEventListener(Consumer<String> l) { this.eventListener = l; }
    public boolean isRunning() { return mpvProcess != null && mpvProcess.isAlive(); }

    // ── SHUTDOWN ─────────────────────────────────────────────────

    public void close() {
        readerExecutor.shutdownNow();
        synchronized (writeLock) {
            try { if (writeStream != null) writeStream.close(); } catch (Exception ignored) {}
            writeStream = null;
        }
        if (pipeHandle != null) {
            Kernel32.INSTANCE.CloseHandle(pipeHandle);
            pipeHandle = null;
        }
        if (mpvProcess != null) mpvProcess.destroy();
    }
}