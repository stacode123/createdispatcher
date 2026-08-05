package net.Dispatcher.web.monitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.Dispatcher.web.sse.SseHub;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Active notifications + a ring of recently resolved ones. Raise/update/resolve fan out over the
 * SSE stream; while a notification stays active its updates are rebroadcast only on severity
 * change or every ~30 s (clients keep their own copy current from the snapshot + deltas).
 * Thread-safe: analyzers call in from the server thread and the analyzer thread.
 */
public final class NotificationHub {
    private static final int RESOLVED_KEPT = 100;
    private static final long UPDATE_REBROADCAST_TICKS = 600;

    private final ConcurrentHashMap<String, WebNotification> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastBroadcastTick = new ConcurrentHashMap<>();
    private final ArrayDeque<WebNotification> resolved = new ArrayDeque<>();
    private final SseHub sse;
    /** (previous-or-null, current) on new raises and severity changes — feeds replay capture. */
    private volatile java.util.function.BiConsumer<WebNotification, WebNotification> onTransition;

    public NotificationHub(SseHub sse) {
        this.sse = sse;
    }

    public void setTransitionListener(java.util.function.BiConsumer<WebNotification, WebNotification> listener) {
        this.onTransition = listener;
    }

    public void raiseOrUpdate(WebNotification notification) {
        WebNotification previous = active.put(notification.id(), notification);
        var listener = onTransition;
        if (listener != null && (previous == null || previous.severity() != notification.severity()))
            listener.accept(previous, notification);
        boolean broadcast = previous == null
                || previous.severity() != notification.severity()
                || notification.updatedTick() - lastBroadcastTick.getOrDefault(notification.id(), Long.MIN_VALUE)
                        >= UPDATE_REBROADCAST_TICKS;
        if (broadcast) {
            lastBroadcastTick.put(notification.id(), notification.updatedTick());
            sse.broadcast("notify", notification.toJson().toString());
        }
    }

    public void resolve(String id, long tick) {
        WebNotification notification = active.remove(id);
        if (notification == null) return;
        lastBroadcastTick.remove(id);
        WebNotification done = notification.withResolved(tick);
        synchronized (resolved) {
            resolved.addFirst(done);
            while (resolved.size() > RESOLVED_KEPT) resolved.removeLast();
        }
        sse.broadcast("notify", done.toJson().toString());
    }

    public boolean isActive(String id) {
        return active.containsKey(id);
    }

    public Collection<WebNotification> active() {
        return active.values();
    }

    public int activeCount() {
        return active.size();
    }

    public JsonObject snapshot() {
        JsonArray activeJson = new JsonArray();
        List<WebNotification> sorted = new ArrayList<>(active.values());
        sorted.sort(Comparator.comparing((WebNotification n) -> n.severity() != WebNotification.Severity.CRITICAL)
                .thenComparing(WebNotification::sinceTick));
        for (WebNotification notification : sorted) activeJson.add(notification.toJson());
        JsonArray resolvedJson = new JsonArray();
        synchronized (resolved) {
            int i = 0;
            for (WebNotification notification : resolved) {
                if (i++ >= 50) break;
                resolvedJson.add(notification.toJson());
            }
        }
        JsonObject snapshot = new JsonObject();
        snapshot.add("active", activeJson);
        snapshot.add("resolved", resolvedJson);
        return snapshot;
    }
}
