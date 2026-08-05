package net.Dispatcher.web.sse;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fan-out for the single multiplexed event stream (/api/events). Events get monotonic ids; a small
 * replay ring serves Last-Event-ID reconnects, and uncoverable gaps get an explicit {@code reset}
 * event so clients refetch their snapshots. Thread-safe: broadcasts come from the server thread
 * and the analyzer thread.
 */
public final class SseHub {
    private record Replay(long id, String formatted) {}

    private static final int RING_SIZE = 256;

    private final CopyOnWriteArrayList<SseClient> clients = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger clientIndex = new AtomicInteger();
    private final ArrayDeque<Replay> ring = new ArrayDeque<>();

    public int clientCount() {
        return clients.size();
    }

    public boolean hasClients() {
        return !clients.isEmpty();
    }

    /**
     * Takes ownership of the exchange (the caller must NOT close it after success).
     * The hello event is per-connection and carries no id; replayed events keep theirs.
     */
    public void attach(HttpExchange exchange, String helloJson, long lastEventId) throws IOException {
        StringBuilder preamble = new StringBuilder("retry: 3000\n\n");
        preamble.append("event: hello\ndata: ").append(helloJson).append("\n\n");
        synchronized (ring) {
            if (lastEventId >= 0) {
                long firstBuffered = ring.isEmpty() ? sequence.get() + 1 : ring.peekFirst().id();
                if (lastEventId < firstBuffered - 1) {
                    preamble.append("event: reset\ndata: {}\n\n");
                } else {
                    for (Replay replay : ring)
                        if (replay.id() > lastEventId) preamble.append(replay.formatted());
                }
            }
        }
        clients.add(new SseClient(exchange, preamble.toString(), clientIndex.incrementAndGet(), clients::remove));
    }

    public void broadcast(String event, String dataJson) {
        long id = sequence.incrementAndGet();
        String formatted = "id: " + id + "\nevent: " + event + "\ndata: " + dataJson + "\n\n";
        synchronized (ring) {
            ring.addLast(new Replay(id, formatted));
            if (ring.size() > RING_SIZE) ring.removeFirst();
        }
        for (SseClient client : clients) client.offer(formatted);
    }

    public void closeAll() {
        for (SseClient client : clients) client.close();
        clients.clear();
    }
}
