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

    private Socket socket;
    private PrintWriter out;
    private Thread readerThread;
    private boolean connected = false;

    // Multiple handlers instead of one
    private final List<Consumer<String>> messageHandlers = new CopyOnWriteArrayList<>();

    // ─── Connect ─────────────────────────────────────────────────────────────

    public boolean connect(String host, int port) {
        try {
            socket = new Socket(host, port);
            socket.setKeepAlive(true);
            // The wire protocol is UTF-8. Relying on the platform default
            // corrupts non-ASCII chat and usernames on any machine whose
            // locale is not UTF-8 (e.g. GBK on a Chinese Windows install).
            out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            startReaderThread();
            connected = true;
            log.info("Connected to server: " + host + ":" + port);
            return true;
        } catch (IOException e) {
            log.severe("Failed to connect to server: " + e.getMessage());
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
                connected = false;
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
}