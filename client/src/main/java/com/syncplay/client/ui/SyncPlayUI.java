package com.cowatch.client.ui;

import com.cowatch.client.mpv.MPVController;
import com.cowatch.client.network.ServerConnection;
import com.cowatch.client.sync.SyncEngine;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.File;

/**
 * Main cowatch client UI (Swing — works on Windows, Mac, Linux without JavaFX).
 *
 * Layout:
 *   ┌─────────────────────────────────────┐
 *   │  Server: [host] [port]  [Connect]   │
 *   │  Room:   [name] Username:[name]     │
 *   │  File:   [path]         [Browse]    │
 *   │  [  Play  ] [  Pause  ] [  Seek  ]  │
 *   │─────────────────────────────────────│
 *   │  Chat log area                      │
 *   │─────────────────────────────────────│
 *   │  [chat input]           [  Send  ]  │
 *   └─────────────────────────────────────┘
 */
public class cowatchUI extends JFrame {

    private final MPVController    mpv    = new MPVController();
    private final ServerConnection server = new ServerConnection();
    private SyncEngine syncEngine;

    // ─── Connection Panel Fields
    private final JTextField hostField     = new JTextField("localhost", 15);
    private final JTextField portField     = new JTextField("5555", 5);
    private final JTextField roomField     = new JTextField("room1", 10);
    private final JTextField userField     = new JTextField("User1", 10);
    private final JButton    connectBtn    = new JButton("Connect");
    private final JLabel     statusLabel   = new JLabel("● Disconnected");

    // ─── File Panel
    private final JTextField fileField     = new JTextField(25);
    private final JButton    browseBtn     = new JButton("Browse...");
    private final JButton    launchBtn     = new JButton("Launch MPV");

    // ─── Control Panel
    private final JButton    playBtn       = new JButton("▶  Play");
    private final JButton    pauseBtn      = new JButton("⏸  Pause");
    private final JTextField seekField     = new JTextField("0", 6);
    private final JButton    seekBtn       = new JButton("Seek");

    // ─── Chat Panel
    private final JTextArea  chatArea      = new JTextArea(8, 40);
    private final JTextField chatInput     = new JTextField(30);
    private final JButton    sendBtn       = new JButton("Send");

    public cowatchUI() {
        super("cowatch");
        buildUI();
        wireActions();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // ─── Build UI ────────────────────────────────────────────────────────────

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        root.add(buildConnectionPanel(), BorderLayout.NORTH);
        root.add(buildChatPanel(),       BorderLayout.CENTER);
        root.add(buildStatusBar(),       BorderLayout.SOUTH);

        setContentPane(root);

        // Style
        playBtn.setBackground(new Color(46, 160, 67));
        playBtn.setForeground(Color.WHITE);
        pauseBtn.setBackground(new Color(218, 165, 32));
        connectBtn.setBackground(new Color(9, 105, 218));
        connectBtn.setForeground(Color.WHITE);
        statusLabel.setForeground(Color.RED);

        setControlsEnabled(false);
    }

    private JPanel buildConnectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Connection"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 5, 3, 5);
        c.fill   = GridBagConstraints.HORIZONTAL;

        // Row 0: host, port, connect
        addRow(panel, c, 0, "Server:", hostField, "Port:", portField, connectBtn);

        // Row 1: room, username
        addRow(panel, c, 1, "Room:", roomField, "Username:", userField, null);

        // Row 2: file picker
        c.gridy = 2; c.gridx = 0;
        panel.add(new JLabel("File:"), c);
        c.gridx = 1; c.gridwidth = 3;
        panel.add(fileField, c);
        c.gridx = 4; c.gridwidth = 1;
        panel.add(browseBtn, c);
        c.gridx = 5;
        panel.add(launchBtn, c);

        // Row 3: playback controls
        c.gridy = 3; c.gridx = 0; c.gridwidth = 6;
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        controls.add(playBtn);
        controls.add(pauseBtn);
        controls.add(new JLabel("  Seek to (s):"));
        controls.add(seekField);
        controls.add(seekBtn);
        panel.add(controls, c);

        return panel;
    }

    private void addRow(JPanel p, GridBagConstraints c, int row,
                        String l1, JComponent f1, String l2, JComponent f2, JComponent btn) {
        c.gridy = row;
        c.gridwidth = 1;
        c.gridx = 0; p.add(new JLabel(l1), c);
        c.gridx = 1; p.add(f1, c);
        c.gridx = 2; p.add(new JLabel(l2), c);
        c.gridx = 3; p.add(f2, c);
        if (btn != null) { c.gridx = 4; p.add(btn, c); }
    }

    private JPanel buildChatPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Chat"));

        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(chatArea), BorderLayout.CENTER);

        JPanel inputRow = new JPanel(new BorderLayout(5, 0));
        inputRow.add(chatInput, BorderLayout.CENTER);
        inputRow.add(sendBtn,   BorderLayout.EAST);
        panel.add(inputRow, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(statusLabel);
        return bar;
    }

    // ─── Wire Actions ────────────────────────────────────────────────────────

    private void wireActions() {
        // Connect to server
        connectBtn.addActionListener(e -> handleConnect());

        // Browse for file
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                fileField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        // Launch MPV
        launchBtn.addActionListener(e -> handleLaunch());

        // Playback controls
        playBtn.addActionListener(e -> {
            if (syncEngine != null) syncEngine.localPlay();
        });
        pauseBtn.addActionListener(e -> {
            if (syncEngine != null) syncEngine.localPause();
        });
        seekBtn.addActionListener(e -> {
            if (syncEngine != null) {
                try {
                    double pos = Double.parseDouble(seekField.getText().trim());
                    syncEngine.localSeek(pos);
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(this, "Invalid seek position");
                }
            }
        });

        // Chat
        sendBtn.addActionListener(e -> handleSendChat());
        chatInput.addActionListener(e -> handleSendChat());

        // Close
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                server.disconnect();
                mpv.close();
            }
        });
    }

    // ─── Handlers ────────────────────────────────────────────────────────────

    private void handleConnect() {
        String host     = hostField.getText().trim();
        int    port     = Integer.parseInt(portField.getText().trim());
        String room     = roomField.getText().trim();
        String username = userField.getText().trim();

        if (server.connect(host, port)) {
            // Hook server messages to chat display
            server.setMessageHandler(msg -> SwingUtilities.invokeLater(() -> {
                appendChat(msg);
                // SyncEngine also gets this via its own hook
            }));

            // Create sync engine
            syncEngine = new SyncEngine(mpv, server);
            server.joinRoom(room, username);

            setStatus("● Connected to " + host + ":" + port + " | Room: " + room, new Color(46, 160, 67));
            connectBtn.setEnabled(false);
        } else {
            setStatus("● Connection failed", Color.RED);
        }
    }

    private void handleLaunch() {
        String filePath = fileField.getText().trim();
        if (filePath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select a media file first.");
            return;
        }
        if (!new File(filePath).exists()) {
            JOptionPane.showMessageDialog(this, "File not found: " + filePath);
            return;
        }
        launchBtn.setEnabled(false);
        launchBtn.setText("Launching...");
        new Thread(() -> {
            boolean ok = mpv.launch(filePath);
            SwingUtilities.invokeLater(() -> {
                if (ok) {
                    launchBtn.setText("MPV Running ✓");
                    setControlsEnabled(true);
                    appendChat("[system] MPV launched successfully");
                } else {
                    launchBtn.setEnabled(true);
                    launchBtn.setText("Launch MPV");
                    JOptionPane.showMessageDialog(this,
                        "Failed to launch MPV.\nMake sure MPV is installed and on your PATH.");
                }
            });
        }).start();
    }

    private void handleSendChat() {
        String text = chatInput.getText().trim();
        if (!text.isEmpty() && server.isConnected()) {
            server.sendChat(text);
            appendChat("[" + userField.getText().trim() + "] " + text);
            chatInput.setText("");
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void appendChat(String line) {
        chatArea.append(line + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private void setControlsEnabled(boolean enabled) {
        playBtn.setEnabled(enabled);
        pauseBtn.setEnabled(enabled);
        seekBtn.setEnabled(enabled);
        seekField.setEnabled(enabled);
    }

    // ─── Entry Point ─────────────────────────────────────────────────────────

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new cowatchUI();
        });
    }
}
