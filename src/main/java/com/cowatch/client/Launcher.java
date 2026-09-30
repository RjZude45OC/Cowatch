package com.cowatch.client;

import com.cowatch.client.ui.CoWatchApp;

/**
 * Plain (non-{@code Application}) main class.
 *
 * <p>Launching JavaFX from a class that itself extends {@code Application}
 * fails on a classpath (non-modular) run with "JavaFX runtime components are
 * missing". Going through this indirection keeps the shaded fat JAR runnable
 * with a bare {@code java -jar}.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        CoWatchApp.main(args);
    }
}
