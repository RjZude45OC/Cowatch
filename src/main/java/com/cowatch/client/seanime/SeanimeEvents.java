package com.cowatch.client.seanime;

import com.cowatch.client.util.Async;
import org.json.JSONObject;

import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Listens to Seanime's live event stream ({@code /events}) for what is playing.
 *
 * <p>This is how cowatch learns the AniList id and episode of whatever Seanime
 * opened, without depending on the file being in the scanned library. Seanime
 * broadcasts the same playback events for local files, torrent streams and
 * debrid streams, so this covers the cases a path lookup cannot: a stream URL
 * means nothing to {@code /library/local-files}.
 *
 * <p>The connection retries with a growing delay until {@link #close()}, so a
 * Seanime restart does not need a manual reconnect.
 */
public class SeanimeEvents implements WebSocket.Listener {

    private static final Logger log = Logger.getLogger(SeanimeEvents.class.getName());

    private static final String TRACKING_STARTED = "playback-manager-progress-tracking-started";
    private static final String PLAYBACK_STATE   = "playback-manager-progress-playback-state";
    private static final String TRACKING_STOPPED = "playback-manager-progress-tracking-stopped";

    private static final long MIN_RETRY_MS = 2_000;
    private static final long MAX_RETRY_MS = 30_000;

    /**
     * What Seanime says is playing.
     *
     * @param anilistId AniList media id
     * @param episode   the AniList progress number Seanime tracks it as
     * @param title     the show's title as Seanime displays it
     * @param filename  the file or stream name, for display
     */
    public record Playback(int anilistId, int episode, String title, String filename) {}

    private final SeanimeClient client;
    private final Consumer<Playback> onPlayback;
    private final Runnable onStopped;

    private final ScheduledExecutorService retry =
            Executors.newSingleThreadScheduledExecutor(Async.namedDaemonFactory("seanime-events"));
    private final StringBuilder partial = new StringBuilder();

    private volatile WebSocket socket;
    private volatile boolean closed = false;
    private volatile long retryDelayMs = MIN_RETRY_MS;
    private volatile String lastKey = "";

    /**
     * @param onPlayback called on a background thread with each new episode;
     *                   repeats of the one already reported are dropped
     * @param onStopped  called on a background thread when Seanime stops tracking
     */
    public SeanimeEvents(SeanimeClient client, Consumer<Playback> onPlayback, Runnable onStopped) {
        this.client     = client;
        this.onPlayback = onPlayback;
        this.onStopped  = onStopped;
    }

    public void start() {
        retry.execute(this::open);
    }

    public void close() {
        closed = true;
        retry.shutdownNow();
        WebSocket ws = socket;
        if (ws != null) ws.abort();
    }

    private void open() {
        if (closed) return;
        client.httpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(client.eventsUri(), this)
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        log.fine("Seanime events connect failed: " + err.getMessage());
                        scheduleReopen();
                    }
                });
    }

    private void scheduleReopen() {
        if (closed) return;
        long delay = retryDelayMs;
        retryDelayMs = Math.min(MAX_RETRY_MS, retryDelayMs * 2);
        try {
            retry.schedule(this::open, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Closed while the failure was in flight.
        }
    }

    // --- WebSocket.Listener -----------------------------------------------

    @Override
    public void onOpen(WebSocket ws) {
        socket = ws;
        retryDelayMs = MIN_RETRY_MS;
        log.info("Seanime events connected");
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String message = partial.toString();
            partial.setLength(0);
            handle(message);
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        log.info("Seanime events closed: " + statusCode + " " + reason);
        socket = null;
        scheduleReopen();
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        log.fine("Seanime events error: " + error.getMessage());
        socket = null;
        partial.setLength(0);
        scheduleReopen();
    }

    // --- Parsing -----------------------------------------------------------

    private void handle(String message) {
        try {
            JSONObject event = new JSONObject(message);
            switch (event.optString("type")) {
                case TRACKING_STARTED, PLAYBACK_STATE -> {
                    JSONObject p = event.optJSONObject("payload");
                    if (p == null) return;
                    int mediaId = p.optInt("mediaId", 0);
                    int episode = p.optInt("episodeNumber", 0);
                    if (mediaId <= 0 || episode <= 0) return;

                    // Playback-state events repeat every few seconds; only a
                    // change of episode is news.
                    String key = mediaId + ":" + episode;
                    if (key.equals(lastKey)) return;
                    lastKey = key;

                    onPlayback.accept(new Playback(mediaId, episode,
                            p.optString("mediaTitle", ""), p.optString("filename", "")));
                }
                case TRACKING_STOPPED -> {
                    lastKey = "";
                    onStopped.run();
                }
                default -> { /* Seanime sends many events cowatch has no use for. */ }
            }
        } catch (Exception e) {
            log.finest("Unreadable Seanime event: " + e.getMessage());
        }
    }
}
