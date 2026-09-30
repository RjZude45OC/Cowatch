package com.cowatch.client.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One shared pool for the short background jobs the UI kicks off — network
 * calls to Jellyfin, AniList and AniZip, and mpv launches.
 *
 * <p>Every worker is a daemon, so a job in flight never keeps the JVM alive
 * after the window closes. Uncaught exceptions are logged instead of being
 * swallowed by a dead thread, which is what made failures in the old
 * {@code new Thread(...).start()} call sites invisible.
 */
public final class Async {

    private static final Logger log = Logger.getLogger(Async.class.getName());

    private static final ExecutorService POOL =
            Executors.newCachedThreadPool(namedDaemonFactory("cowatch-async"));

    private Async() {}

    /** Runs a job off the JavaFX application thread. */
    public static void run(Runnable task) {
        run("job", task);
    }

    /**
     * Runs a job off the JavaFX application thread, naming the worker while it
     * runs so thread dumps and log lines say what the app was doing.
     */
    public static void run(String name, Runnable task) {
        POOL.execute(() -> {
            Thread self = Thread.currentThread();
            String previous = self.getName();
            self.setName("cowatch-" + name);
            try {
                task.run();
            } catch (RuntimeException | Error e) {
                log.log(Level.SEVERE, "Background job '" + name + "' failed", e);
            } finally {
                self.setName(previous);
            }
        });
    }

    /** Stops accepting work and interrupts anything still running. */
    public static void shutdown() {
        POOL.shutdownNow();
    }

    /** Daemon thread factory with readable, numbered names. */
    public static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
