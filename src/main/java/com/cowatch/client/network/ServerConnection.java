package com.cowatch.client.network;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.*;

public class ServerConnection {

    private static final Logger log = Logger.getLogger(ServerConnection.class.getName());

    /** How long to wait for the server before giving up, instead of the OS default of ~20s. */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private Socket socket;
    private PrintWriter out;
    private Thread readerThread;
    private volatile boolean connected = false;
    private volatile String endpoint = "";
    private volatile String room = "";

    /** Told when the server drops the connection without being asked to. */
    private volatile Runnable onConnectionLost;

    // Multiple handlers instead of one
    private final List<Consumer<String>> messageHandlers = new CopyOnWriteArrayList<>();

    // ─── Connect ─────────────────────────────────────────────────────────────

    public boolean connect(String host, int port) {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setKeepAlive(true);
            // The wire protocol is UTF-8. Relying on the platform default
            // corrupts non-ASCII chat and usernames on any machine whose
            // locale is not UTF-8 (e.g. GBK on a Chinese Windows install).
            out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            startReaderThread();
            connected = true;
            endpoint = host + ":" + port;
            log.info("Connected to server: " + host + ":" + port);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            log.severe("Failed to connect to server: " + e.getMessage());
            if (socket != null) try { socket.close(); } catch (IOException ignored) {}
            return false;
        }
    }

    private void startReaderThread() {
        readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    for (Consumer<String> handler : messageHandlers) {
                        handler.accept(trimmed);
                    }
                }
            } catch (IOException e) {
                if (connected) log.warning("Server connection lost: " + e.getMessage());
            } finally {
                // Still flagged connected here means nobody called disconnect():
                // the server went away on its own, and the UI needs to know.
                boolean unexpected = connected;
                connected = false;
                Runnable lost = onConnectionLost;
                if (unexpected && lost != null) lost.run();
            }
        }, "ServerReader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    // ─── Receive Events ──────────────────────────────────────────────────────

    /**
     * Adds a handler — all registered handlers receive every message.
     * Safe to call from any thread (CopyOnWriteArrayList).
     */
    public void addMessageHandler(Consumer<String> handler) {
        messageHandlers.add(handler);
    }

    /**
     * Kept for compatibility — clears existing handlers and sets this as the only one.
     * Prefer addMessageHandler() for new code.
     */
    public void setMessageHandler(Consumer<String> handler) {
        messageHandlers.clear();
        messageHandlers.add(handler);
    }

    public void removeMessageHandler(Consumer<String> handler) {
        messageHandlers.remove(handler);
    }

    // ─── Send Events (unchanged) ──────────────────────────────────────────────

    public void sendPlay(double position) {
        send(String.format("{\"event\":\"play\",\"position\":%.3f}", position));
    }

    public void sendPause(double position) {
        send(String.format("{\"event\":\"pause\",\"position\":%.3f}", position));
    }

    public void sendSeek(double position) {
        send(String.format("{\"event\":\"seek\",\"position\":%.3f}", position));
    }

    public void joinRoom(String roomId, String username) {
        room = roomId + " as " + username;
        send(new JSONObject()
                .put("event",    "join")
                .put("room",     roomId)
                .put("username", username)
                .toString());
    }

    /**
     * Sends a chat line. The payload is built with JSONObject rather than
     * string concatenation so a backslash, quote, newline or control character
     * in the message cannot produce a malformed frame.
     */
    public void sendChat(String message) {
        send(new JSONObject()
                .put("event", "chat")
                .put("data",  message)
                .toString());
    }

    /**
     * Announces what this client has started playing.
     *
     * <p>Identified by AniList media id and episode number rather than by file
     * path or library item id, because peers in a room may hold different copies
     * of the same episode: one streaming from Jellyfin, one playing a file a
     * Seanime auto-downloader fetched. A path would mean nothing on the other
     * side; an AniList id each client resolves against its own library does.
     *
     * <p>Position and pause state let the others open the episode at the same
     * point instead of at its start. A server that predates them drops both
     * fields, and receivers then start from zero.
     *
     * @param source   where this client got the episode: "jellyfin", "seanime" or "local"
     * @param position seconds into the episode, in the room's timeline
     * @param paused   whether this client is paused
     */
    public void sendMedia(int anilistId, int episode, String title, String source,
                          double position, boolean paused) {
        if (anilistId <= 0) return;   // nothing the other side could resolve
        send(new JSONObject()
                .put("event",     "media")
                .put("anilistId", anilistId)
                .put("episode",   episode)
                .put("title",     title  == null ? "" : title)
                .put("source",    source == null ? "" : source)
                .put("position",  Math.round(Math.max(0, position) * 1000.0) / 1000.0)
                .put("paused",    paused)
                .toString());
    }

    private void send(String json) {
        if (out != null && connected) {
            out.println(json);
            log.fine("Sent: " + json);
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    public void disconnect() {
        connected = false;
        messageHandlers.clear();
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        if (readerThread != null) readerThread.interrupt();
    }

    public boolean isConnected() { return connected; }

    /** host:port of the last connection, for the debug console. */
    public String getEndpoint() { return endpoint; }

    /** Room and username of the last join, for the debug console. */
    public String getRoom() { return room; }

    /**
     * Registers a callback for the server closing the connection on its own.
     * Runs on the reader thread. Not called for {@link #disconnect()}.
     */
    public void setOnConnectionLost(Runnable handler) { this.onConnectionLost = handler; }
}