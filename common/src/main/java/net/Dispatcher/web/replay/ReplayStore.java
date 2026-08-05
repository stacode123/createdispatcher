package net.Dispatcher.web.replay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import net.Dispatcher.DispatcherMod;
import net.Dispatcher.config.DispatcherConfig;
import net.Dispatcher.web.graph.WebGraphStore;
import net.Dispatcher.web.live.LiveTrainSampler;
import net.Dispatcher.web.monitor.WebNotification;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

/**
 * Auto-captured replays of notification moments: on raise, the recent frame window (lead-up)
 * plus a fixed aftermath of live frames is recorded, filtered to the involved trains and any
 * train near the event. Recordings are small (dozens of trains × a few minutes at 1 Hz) and
 * kept in a bounded ring. All mutation is synchronized — raises arrive from the server AND
 * analyzer threads; frame appends from the server thread.
 */
public final class ReplayStore {
    private static final class Pending {
        final String id;
        final WebNotification event;
        final long startTick;
        final long eventTick;
        final long endTick;
        final int graphVersion;
        final Set<UUID> involved;
        final Map<UUID, List<double[]>> samples = new LinkedHashMap<>();

        Pending(String id, WebNotification event, long startTick, long eventTick, long endTick,
                int graphVersion) {
            this.id = id;
            this.event = event;
            this.startTick = startTick;
            this.eventTick = eventTick;
            this.endTick = endTick;
            this.graphVersion = graphVersion;
            this.involved = new LinkedHashSet<>();
            for (WebNotification.TrainRef ref : event.trains()) involved.add(ref.id());
        }
    }

    public record Finalized(String id, String notificationId, String kind, String message,
                            long createdMs, UUID graphId, int graphVersion, String dim,
                            double x, double z, long startTick, long endTick, int cadenceTicks,
                            int trainCount, byte[] gzJson) {}

    private final List<Pending> pending = new ArrayList<>();
    private final ArrayDeque<Finalized> finalized = new ArrayDeque<>();
    private int counter;
    /** Fired with the finalized replay (any thread). */
    private final Consumer<Finalized> onFinalized;

    public ReplayStore(Consumer<Finalized> onFinalized) {
        this.onFinalized = onFinalized;
    }

    /** Any thread (called via the analyzer executor). Seeds the lead-up from the frame history. */
    public synchronized void beginCapture(WebNotification event, List<LiveTrainSampler.Frame> leadUp,
                                          WebGraphStore store) {
        if (DispatcherConfig.COMMON.WebReplayKept.get() <= 0 || event.graphId() == null) return;
        for (Pending open : pending)
            if (open.event.id().equals(event.id())) return; // escalation of an already-recording event
        WebGraphStore.Entry entry = store.get(event.graphId());
        if (entry == null || entry.tooLarge()) return;
        long raisedTick = event.updatedTick();
        long leadTicks = DispatcherConfig.COMMON.WebReplayLeadSeconds.get() * 20L;
        long tailTicks = DispatcherConfig.COMMON.WebReplayTailSeconds.get() * 20L;
        Pending capture = new Pending("r" + (++counter) + "-" + Long.toHexString(raisedTick),
                event, Math.max(0, raisedTick - leadTicks), raisedTick, raisedTick + tailTicks,
                entry.version());
        for (LiveTrainSampler.Frame frame : leadUp)
            if (frame.gameTick >= capture.startTick) appendFrame(capture, frame, store);
        pending.add(capture);
    }

    /** SERVER THREAD, once per sampler frame. */
    public synchronized void onFrame(LiveTrainSampler.Frame frame, WebGraphStore store,
                                     LiveTrainSampler sampler) {
        if (pending.isEmpty()) return;
        for (int i = pending.size() - 1; i >= 0; i--) {
            Pending capture = pending.get(i);
            appendFrame(capture, frame, store);
            if (frame.gameTick >= capture.endTick) {
                pending.remove(i);
                Map<UUID, String> namesById = new LinkedHashMap<>();
                for (LiveTrainSampler.RosterEntry entry : sampler.roster())
                    namesById.put(entry.id(), entry.name());
                finish(capture, namesById);
            }
        }
    }

    private void appendFrame(Pending capture, LiveTrainSampler.Frame frame, WebGraphStore store) {
        UUID graphId = capture.event.graphId();
        WebGraphStore.Entry entry = store.get(graphId);
        double radius = DispatcherConfig.COMMON.WebReplayRadius.get();
        double radiusSq = radius * radius;
        boolean canFilterByPosition = entry != null && !entry.tooLarge()
                && entry.version() == capture.graphVersion
                && capture.graphVersion == frame.graphVersions.getOrDefault(graphId.toString(), -1);
        for (LiveTrainSampler.TrainPos pos : frame.positions) {
            if (!graphId.equals(pos.graphId())) continue;
            if (!capture.involved.contains(pos.id())) {
                if (!canFilterByPosition) continue;
                var edges = entry.graph().edges;
                if (pos.edgeId() < 0 || pos.edgeId() >= edges.size()) continue;
                var nodePos = entry.graph().nodes.get(edges.get(pos.edgeId()).from).position;
                double dx = nodePos.x - capture.event.x();
                double dz = nodePos.z - capture.event.z();
                if (dx * dx + dz * dz > radiusSq) continue;
            }
            capture.samples.computeIfAbsent(pos.id(), k -> new ArrayList<>())
                    .add(new double[] {frame.gameTick, pos.edgeId(), pos.offset(), pos.speed()});
        }
    }

    private void finish(Pending capture, Map<UUID, String> namesById) {
        try {
            WebNotification event = capture.event;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
            try (JsonWriter json = new JsonWriter(
                    new OutputStreamWriter(new GZIPOutputStream(buffer), StandardCharsets.UTF_8))) {
                json.beginObject();
                json.name("id").value(capture.id);
                json.name("notificationId").value(event.id());
                json.name("kind").value(event.kind().name());
                json.name("message").value(event.message());
                json.name("graphId").value(event.graphId().toString());
                json.name("graphVersion").value(capture.graphVersion);
                json.name("dim").value(event.dim());
                json.name("x").value(event.x());
                json.name("z").value(event.z());
                json.name("startTick").value(capture.startTick);
                json.name("eventTick").value(capture.eventTick);
                json.name("endTick").value(capture.endTick);
                json.name("cadenceTicks").value(DispatcherConfig.COMMON.WebLiveSampleTicks.get());
                json.name("involved").beginArray();
                for (UUID id : capture.involved) json.value(id.toString());
                json.endArray();
                if (event.data() != null && event.data().size() > 0) {
                    json.name("eventData");
                    new com.google.gson.Gson().toJson(event.data(), json);
                }
                json.name("trains").beginArray();
                for (Map.Entry<UUID, List<double[]>> train : capture.samples.entrySet()) {
                    json.beginObject();
                    json.name("id").value(train.getKey().toString());
                    String name = namesById.get(train.getKey());
                    if (name == null)
                        name = event.trains().stream().filter(r -> r.id().equals(train.getKey()))
                                .map(WebNotification.TrainRef::name).findFirst()
                                .orElse(train.getKey().toString().substring(0, 8));
                    json.name("name").value(name);
                    json.name("s").beginArray();
                    for (double[] sample : train.getValue()) {
                        json.beginArray();
                        json.value((long) sample[0]);
                        json.value((int) sample[1]);
                        json.value(Math.round(sample[2] * 10) / 10.0);
                        json.value(Math.round(sample[3] * 1000) / 1000.0);
                        json.endArray();
                    }
                    json.endArray();
                    json.endObject();
                }
                json.endArray();
                json.endObject();
            }
            Finalized done = new Finalized(capture.id, event.id(), event.kind().name(), event.message(),
                    System.currentTimeMillis(), event.graphId(), capture.graphVersion, event.dim(),
                    event.x(), event.z(), capture.startTick, capture.endTick,
                    DispatcherConfig.COMMON.WebLiveSampleTicks.get(), capture.samples.size(),
                    buffer.toByteArray());
            finalized.addFirst(done);
            int kept = DispatcherConfig.COMMON.WebReplayKept.get();
            while (finalized.size() > Math.max(1, kept)) finalized.removeLast();
            onFinalized.accept(done);
        } catch (IOException e) {
            DispatcherMod.LOGGER.error("Dispatcher web: replay finalize failed", e);
        }
    }

    public synchronized Finalized get(String id) {
        for (Finalized replay : finalized)
            if (replay.id().equals(id)) return replay;
        return null;
    }

    public synchronized JsonObject index() {
        JsonArray replays = new JsonArray();
        for (Finalized replay : finalized) {
            JsonObject item = new JsonObject();
            item.addProperty("id", replay.id());
            item.addProperty("notificationId", replay.notificationId());
            item.addProperty("kind", replay.kind());
            item.addProperty("message", replay.message());
            item.addProperty("createdMs", replay.createdMs());
            item.addProperty("graphId", replay.graphId().toString());
            item.addProperty("graphVersion", replay.graphVersion());
            item.addProperty("dim", replay.dim());
            item.addProperty("durationTicks", replay.endTick() - replay.startTick());
            item.addProperty("trains", replay.trainCount());
            replays.add(item);
        }
        JsonObject body = new JsonObject();
        body.add("replays", replays);
        return body;
    }
}
