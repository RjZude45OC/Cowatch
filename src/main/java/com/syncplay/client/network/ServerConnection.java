package com.syncplay.client.network;

import java.io.*;
import java.net.*;
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
            out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream()), true);
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
                    new InputStreamReader(socket.getInputStream()))) {
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
        send(String.format(
                "{\"event\":\"join\",\"room\":\"%s\",\"username\":\"%s\"}",
                roomId, username));
    }

    public void sendChat(String message) {
        String safe = message.replace("\"", "\\\"");
        send(String.format("{\"event\":\"chat\",\"data\":\"%s\"}", safe));
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