package net.Dispatcher.web.sse;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One connected event-stream client: a bounded queue drained by a dedicated daemon writer thread
 * (never the HTTP pool — SSE pins its connection). Backpressure drops the oldest event; a silent
 * 15 s window emits a comment heartbeat (reverse-proxy keep-alive); any write failure disconnects.
 */
final class SseClient {
    private static final long HEARTBEAT_SECONDS = 15;

    private final HttpExchange exchange;
    private final OutputStream out;
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(256);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<SseClient> onClose;

    SseClient(HttpExchange exchange, String preamble, int index, Consumer<SseClient> onClose) throws IOException {
        this.exchange = exchange;
        this.onClose = onClose;
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        headers.set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        out = exchange.getResponseBody();
        out.write(preamble.getBytes(StandardCharsets.UTF_8));
        out.flush();
        Thread writer = new Thread(this::run, "Dispatcher-Web-SSE-" + index);
        writer.setDaemon(true);
        writer.start();
    }

    void offer(String formattedEvent) {
        if (closed.get()) return;
        if (!queue.offer(formattedEvent)) {
            queue.poll();
            queue.offer(formattedEvent);
        }
    }

    private void run() {
        try {
            while (!closed.get()) {
                String next = queue.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                out.write((next == null ? ": ping\n\n" : next).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            // client went away or shutdown — fall through to close
        } finally {
            close();
        }
    }

    void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            exchange.close();
        } catch (Exception ignored) {
        }
        onClose.accept(this);
    }
}
