package com.cowatch.client.network;

import java.io.*;
import java.net.*;
import java.util.function.Consumer;
import java.util.logging.*;

/**
 * Manages the TCP connection to the cowatch relay server.
 * Sends sync events and receives events from other users in the room.
 */
public class ServerConnection {

    private static final Logger log = Logger.getLogger(ServerConnection.class.getName());

    private Socket socket;
    private PrintWriter out;
    private Thread readerThread;
    private Consumer<String> messageHandler;
    private boolean connected = false;

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
                    if (messageHandler != null) messageHandler.accept(line.trim());
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

    // ─── Send Events ─────────────────────────────────────────────────────────

    /** Tell the server (and all room members) to play */
    public void sendPlay(double position) {
        send(String.format(
            "{\"event\":\"play\",\"position\":%.3f}", position));
    }

    /** Tell the server (and all room members) to pause */
    public void sendPause(double position) {
        send(String.format(
            "{\"event\":\"pause\",\"position\":%.3f}", position));
    }

    /** Tell the server (and all room members) to seek */
    public void sendSeek(double position) {
        send(String.format(
            "{\"event\":\"seek\",\"position\":%.3f}", position));
    }

    /** Join a room with a username */
    public void joinRoom(String roomId, String username) {
        send(String.format(
            "{\"event\":\"join\",\"room\":\"%s\",\"username\":\"%s\"}",
            roomId, username));
    }

    /** Send a chat message */
    public void sendChat(String message) {
        // Escape quotes in message
        String safe = message.replace("\"", "\\\"");
        send(String.format("{\"event\":\"chat\",\"data\":\"%s\"}", safe));
    }

    private void send(String json) {
        if (out != null && connected) {
            out.println(json);
            log.fine("Sent: " + json);
        }
    }

    // ─── Receive Events ──────────────────────────────────────────────────────

    /** Set handler that receives raw JSON messages from the server */
    public void setMessageHandler(Consumer<String> handler) {
        this.messageHandler = handler;
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    public void disconnect() {
        connected = false;
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        if (readerThread != null) readerThread.interrupt();
    }

    public boolean isConnected() { return connected; }
}
