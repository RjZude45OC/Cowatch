package com.cowatch.client.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Keeps the most recent log records in memory for the debug console.
 *
 * <p>Installed on the {@code com.cowatch} logger at {@link Level#ALL}, so the
 * FINE/FINEST lines (raw mpv IPC, outgoing server frames) are kept here even
 * though the console handler on the root logger still only prints INFO and up.
 * The console polls with {@link #since}; nothing here touches JavaFX.
 */
public final class LogBuffer extends Handler {

    /** Where a line came from, for the console's filter toggles. */
    public enum Source { APP, SYNC, SERVER, SEANIME, MPV, MPV_IPC }

    public record Entry(long seq, long millis, Level level, Source source,
                        String logger, String thread, String message) {}

    private static final int CAPACITY = 5_000;
    private static final LogBuffer INSTANCE = new LogBuffer();

    /** Held so the logger and its level are not garbage collected away. */
    private static Logger appLogger;

    private final ArrayDeque<Entry> entries = new ArrayDeque<>(CAPACITY);
    private long nextSeq = 1;

    private LogBuffer() {
        setLevel(Level.ALL);
    }

    public static LogBuffer get() { return INSTANCE; }

    /**
     * Starts capturing. Safe to call more than once. Also routes uncaught
     * exceptions through the log so they show up in the console with a trace.
     */
    public static synchronized void install() {
        if (appLogger != null) return;
        appLogger = Logger.getLogger("com.cowatch");
        appLogger.setLevel(Level.ALL);
        appLogger.addHandler(INSTANCE);

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Logger crashLog = Logger.getLogger("com.cowatch.client.Uncaught");
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            crashLog.log(Level.SEVERE, "Uncaught exception on thread " + t.getName(), e);
            if (previous != null) previous.uncaughtException(t, e);
            else e.printStackTrace();
        });
    }

    @Override
    public void publish(LogRecord r) {
        if (r == null) return;
        String message = r.getMessage();
        if (message == null) message = "";
        if (r.getParameters() != null && r.getParameters().length > 0) {
            try {
                message = java.text.MessageFormat.format(message, r.getParameters());
            } catch (IllegalArgumentException ignored) {
                // Leave the pattern as is.
            }
        }
        if (r.getThrown() != null) {
            StringWriter sw = new StringWriter();
            r.getThrown().printStackTrace(new PrintWriter(sw));
            message = message + System.lineSeparator() + sw.toString().stripTrailing();
        }
        String logger = r.getLoggerName() == null ? "" : r.getLoggerName();
        String thread = Thread.currentThread().getName();
        Source source = classify(logger, message);

        synchronized (entries) {
            if (entries.size() == CAPACITY) entries.removeFirst();
            entries.addLast(new Entry(nextSeq++, r.getMillis(), r.getLevel(), source,
                    logger, thread, message));
        }
    }

    /** Entries with a sequence number above {@code afterSeq}, oldest first. */
    public List<Entry> since(long afterSeq) {
        synchronized (entries) {
            List<Entry> out = new ArrayList<>();
            var it = entries.descendingIterator();
            while (it.hasNext()) {
                Entry e = it.next();
                if (e.seq() <= afterSeq) break;
                out.add(e);
            }
            java.util.Collections.reverse(out);
            return out;
        }
    }

    private static Source classify(String logger, String message) {
        if (logger.endsWith(".MPVController")) {
            return (message.startsWith("MPV ←") || message.startsWith("sendRaw"))
                    ? Source.MPV_IPC : Source.MPV;
        }
        if (logger.endsWith(".ServerConnection")) return Source.SERVER;
        if (logger.endsWith(".SyncEngine")) {
            return message.startsWith("Server →") ? Source.SERVER : Source.SYNC;
        }
        if (logger.contains(".seanime.")) return Source.SEANIME;
        return Source.APP;
    }

    @Override public void flush() {}
    @Override public void close() {}
}
