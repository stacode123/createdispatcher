package net.Dispatcher.web.http;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed-window request counter per key (client ip). */
public final class RateLimiter {
    private record Window(long start, AtomicInteger count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public boolean allow(String key, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        Window window = windows.compute(key, (k, existing) ->
                existing == null || now - existing.start() > windowMs
                        ? new Window(now, new AtomicInteger())
                        : existing);
        return window.count().incrementAndGet() <= limit;
    }

    public void cleanup(long maxAgeMs) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext())
            if (now - it.next().getValue().start() > maxAgeMs) it.remove();
    }
}
