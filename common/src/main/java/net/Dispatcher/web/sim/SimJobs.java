package net.Dispatcher.web.sim;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackGraph;
import net.Dispatcher.DispatcherMod;
import net.Dispatcher.config.DispatcherConfig;
import net.Dispatcher.content.simulator.HeadlessSimService;
import net.Dispatcher.content.simulator.core.SimDiagram;
import net.Dispatcher.content.simulator.core.SimGraph;
import net.Dispatcher.content.simulator.core.SimResult;
import net.Dispatcher.content.simulator.core.SimTrainSpec;
import net.Dispatcher.web.corridor.CorridorService;
import net.Dispatcher.web.graph.WebGraphStore;
import net.Dispatcher.web.sse.SseHub;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.zip.GZIPOutputStream;

import static net.Dispatcher.web.corridor.CorridorService.quote;
import static net.Dispatcher.web.corridor.CorridorService.round1;

/**
 * The planner's simulation jobs (W5): a browser posts an assignment list
 * (preset schedules onto live trains, removals), the job snapshots the
 * network on the server thread with those overrides applied and runs the
 * deterministic engine on the shared "Dispatcher-WebSim" thread. Results are
 * gzipped JSON in a byte-bounded LRU; state transitions and progress go out
 * over the {@code sim} SSE event. Wall cap 0 (the default) keeps identical
 * requests byte-identical.
 */
public final class SimJobs {

    private static final int SAMPLE_STRIDE = 100;
    /** Terminal jobs kept at most, on top of the byte budget. */
    private static final int MAX_JOBS = 40;
    private static final long PROGRESS_BROADCAST_MS = 2000;

    public enum State { QUEUED, PREPARING, RUNNING, DONE, FAILED, CANCELLED }

    /** One materialized assignment: the train and the (edited) schedule tag to install. */
    public record ScheduleOverride(UUID trainId, CompoundTag scheduleTag) {}

    public static final class Job {
        public final String id = UUID.randomUUID().toString();
        public final String owner;
        public final UUID graphId;
        public final long createdMs = System.currentTimeMillis();
        final List<ScheduleOverride> overrides;
        final Set<UUID> removals;
        final Set<UUID> keeps;
        final boolean removeScheduled;
        final Long startDayTime;
        final int horizonHours;
        final int headwaySeconds;

        public volatile State state = State.QUEUED;
        public volatile int graphVersion = -1;
        public volatile long progressTicks;
        public volatile long horizonTicks;
        public volatile String error = "";
        volatile boolean cancelRequested;
        volatile long lastProgressBroadcastMs;

        volatile HeadlessSimService.Prepared prepared;
        final List<HeadlessSimService.OverrideIssue> issues = new ArrayList<>();
        public volatile SimResult result;
        volatile List<SimTrainSpec> specs;
        volatile SimGraph simGraph;
        volatile long startDayTimeUsed;
        volatile double dayTimeRate;
        public volatile byte[] gzResult;
        volatile long finishedMs;

        Job(String owner, UUID graphId, List<ScheduleOverride> overrides, Set<UUID> removals,
            Set<UUID> keeps, boolean removeScheduled, Long startDayTime, int horizonHours,
            int headwaySeconds) {
            this.owner = owner;
            this.graphId = graphId;
            this.overrides = overrides;
            this.removals = removals;
            this.keeps = keeps;
            this.removeScheduled = removeScheduled;
            this.startDayTime = startDayTime;
            this.horizonHours = horizonHours;
            this.headwaySeconds = headwaySeconds;
        }

        boolean active() {
            return state == State.QUEUED || state == State.PREPARING || state == State.RUNNING;
        }
    }

    /** Insertion-ordered so queue positions and eviction order fall out naturally. */
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, Long> lastSubmitMs = new ConcurrentHashMap<>();
    private long resultBytes;

    public record Submitted(int status, String errorKey, Job job) {}

    /**
     * HTTP thread: validate caps, enqueue, kick prepare on the server thread.
     * Overrides are already materialized (preset tag + value edits applied).
     */
    public synchronized Submitted submit(MinecraftServer server, WebGraphStore store,
                                         ExecutorService simExecutor,
                                         net.Dispatcher.web.live.LiveTrainSampler sampler,
                                         SseHub hub, String owner, UUID graphId,
                                         List<ScheduleOverride> overrides, Set<UUID> removals,
                                         Set<UUID> keeps, boolean removeScheduled,
                                         Long startDayTime, int horizonHours, int headwaySeconds) {
        long cooldownMs = DispatcherConfig.COMMON.WebSimCooldownSeconds.get() * 1000L;
        Long last = lastSubmitMs.get(owner);
        if (last != null && System.currentTimeMillis() - last < cooldownMs)
            return new Submitted(429, "cooldown", null);
        int active = 0;
        for (Job existing : jobs.values())
            if (existing.active()) active++;
        if (active >= DispatcherConfig.COMMON.WebSimMaxQueued.get())
            return new Submitted(429, "queue_full", null);

        int horizon = Math.max(1, Math.min(horizonHours,
                DispatcherConfig.COMMON.WebSimMaxHorizonHours.get()));
        Job job = new Job(owner, graphId, overrides, removals, keeps, removeScheduled,
                startDayTime, horizon, headwaySeconds);
        job.horizonTicks = (long) horizon * HeadlessSimService.TICKS_PER_HOUR;
        jobs.put(job.id, job);
        lastSubmitMs.put(owner, System.currentTimeMillis());
        evictLocked();
        broadcast(hub, job);
        server.execute(() -> prepare(server, store, simExecutor, sampler, hub, job));
        return new Submitted(202, null, job);
    }

    /** SERVER THREAD: snapshot with overrides, then hand off to the sim executor. */
    private void prepare(MinecraftServer server, WebGraphStore store,
                         ExecutorService simExecutor,
                         net.Dispatcher.web.live.LiveTrainSampler sampler, SseHub hub, Job job) {
        try {
            if (job.cancelRequested) {
                finish(hub, job, State.CANCELLED, "");
                return;
            }
            transition(hub, job, State.PREPARING);
            WebGraphStore.Entry entry = store.get(job.graphId);
            if (entry == null || entry.tooLarge() || entry.simGraph() == null) {
                finish(hub, job, State.FAILED, "graph_unavailable");
                return;
            }
            TrackGraph trackGraph =
                    Create.RAILWAYS.sided(server.overworld()).trackNetworks.get(job.graphId);
            if (trackGraph == null) {
                finish(hub, job, State.FAILED, "graph_unavailable");
                return;
            }
            Map<UUID, CompoundTag> scheduleOverrides = new java.util.TreeMap<>();
            for (ScheduleOverride override : job.overrides)
                scheduleOverrides.put(override.trainId(), override.scheduleTag());
            job.prepared = HeadlessSimService.prepare(server.overworld(), trackGraph,
                    entry.topology(), entry.simGraph(), job.horizonHours, job.startDayTime,
                    sampler.speedFloors(),
                    new HeadlessSimService.PlannerInput(scheduleOverrides, job.removals,
                            job.keeps, job.removeScheduled),
                    job.issues);
            job.graphVersion = entry.version();
            job.simGraph = entry.simGraph();
            job.startDayTimeUsed = job.prepared.clock().startDayTime();
            job.dayTimeRate = job.prepared.clock().dayTimeRate();
            simExecutor.execute(() -> run(hub, job));
        } catch (Throwable t) {
            DispatcherMod.LOGGER.error("Dispatcher web: sim job {} prepare failed", job.id, t);
            finish(hub, job, State.FAILED, "prepare_failed");
        }
    }

    /** SIM THREAD: the deterministic engine run + result serialization. */
    private void run(SseHub hub, Job job) {
        try {
            if (job.cancelRequested) {
                finish(hub, job, State.CANCELLED, "");
                return;
            }
            transition(hub, job, State.RUNNING);
            long wallCap = DispatcherConfig.COMMON.WebSimWallCapSeconds.get() * 1000L;
            long waitConflictTicks = DispatcherConfig.COMMON.SimWaitConflictSeconds.get() * 20L;
            long headwayTicks = (job.headwaySeconds >= 0 ? job.headwaySeconds
                    : DispatcherConfig.COMMON.SimHeadwaySeconds.get()) * 20L;
            HeadlessSimService.Prepared prepared = job.prepared;
            SimResult result = HeadlessSimService.run(prepared, SAMPLE_STRIDE, wallCap,
                    waitConflictTicks, headwayTicks, tick -> {
                        job.progressTicks = tick;
                        long now = System.currentTimeMillis();
                        if (now - job.lastProgressBroadcastMs >= PROGRESS_BROADCAST_MS) {
                            job.lastProgressBroadcastMs = now;
                            broadcast(hub, job);
                        }
                        return !job.cancelRequested;
                    });
            if (job.cancelRequested) {
                finish(hub, job, State.CANCELLED, "");
                return;
            }
            Set<String> overridden = new java.util.HashSet<>();
            for (ScheduleOverride override : job.overrides) overridden.add(override.trainId().toString());
            String json = SimResultJson.build(job.graphId, job.graphVersion,
                    prepared.startGameTick(), result, prepared.specs(),
                    prepared.clock().startDayTime(), prepared.clock().dayTimeRate(),
                    SAMPLE_STRIDE, prepared.excluded(), job.issues, overridden,
                    prepared.removedTrains());
            byte[] gz = gzip(json.getBytes(StandardCharsets.UTF_8));
            synchronized (this) {
                job.result = result;
                job.specs = prepared.specs();
                job.gzResult = gz;
                job.progressTicks = result.ticksSimulated;
                resultBytes += gz.length;
                evictLocked();
            }
            job.prepared = null;
            finish(hub, job, State.DONE, "");
        } catch (Throwable t) {
            DispatcherMod.LOGGER.error("Dispatcher web: sim job {} failed", job.id, t);
            job.prepared = null;
            finish(hub, job, State.FAILED, "sim_failed");
        }
    }

    private void finish(SseHub hub, Job job, State state, String error) {
        job.state = state;
        job.error = error;
        job.finishedMs = System.currentTimeMillis();
        if (state != State.DONE) job.prepared = null;
        broadcast(hub, job);
    }

    private void transition(SseHub hub, Job job, State state) {
        job.state = state;
        broadcast(hub, job);
    }

    /** Oldest terminal jobs go first — never a running one. Callers hold the lock. */
    private void evictLocked() {
        long budget = DispatcherConfig.COMMON.WebSimCacheMB.get() * 1024L * 1024L;
        var iterator = jobs.values().iterator();
        while (iterator.hasNext() && (resultBytes > budget || jobs.size() > MAX_JOBS)) {
            Job job = iterator.next();
            if (job.active()) continue;
            if (job.gzResult != null) resultBytes -= job.gzResult.length;
            iterator.remove();
        }
    }

    public synchronized Job get(String id) {
        return jobs.get(id);
    }

    public synchronized List<Job> all() {
        return new ArrayList<>(jobs.values());
    }

    /** Cancel: queued/preparing jobs die before the engine; running ones abort at the next poll. */
    public void cancel(SseHub hub, Job job) {
        job.cancelRequested = true;
        if (job.state == State.QUEUED) finish(hub, job, State.CANCELLED, "");
    }

    private synchronized int queuePosition(Job job) {
        if (!job.active()) return 0;
        int position = 0;
        for (Job other : jobs.values()) {
            if (other == job) break;
            if (other.active()) position++;
        }
        return position;
    }

    private void broadcast(SseHub hub, Job job) {
        hub.broadcast("sim", statusJson(job));
    }

    public String statusJson(Job job) {
        StringBuilder json = new StringBuilder(160);
        json.append("{\"simId\":\"").append(job.id)
                .append("\",\"state\":\"").append(job.state.name().toLowerCase())
                .append("\",\"graphId\":\"").append(job.graphId)
                .append("\",\"graphVersion\":").append(job.graphVersion)
                .append(",\"progressTicks\":").append(job.progressTicks)
                .append(",\"horizonTicks\":").append(job.horizonTicks)
                .append(",\"queuePos\":").append(queuePosition(job))
                .append(",\"createdMs\":").append(job.createdMs);
        if (!job.error.isEmpty())
            json.append(",\"error\":").append(quote(job.error));
        if (job.state == State.DONE) {
            json.append(",\"issues\":[");
            for (int i = 0; i < job.issues.size(); i++) {
                HeadlessSimService.OverrideIssue issue = job.issues.get(i);
                if (i > 0) json.append(',');
                json.append('[').append(quote(issue.trainName())).append(',')
                        .append(quote(issue.translationKey())).append(',')
                        .append(quote(issue.detail())).append(']');
            }
            json.append(']');
        }
        return json.append('}').toString();
    }

    /**
     * HTTP thread: the finished run projected onto an A→B corridor — the
     * planner's diagram tab. The corridor must come from the same graph
     * version the sim ran on (caller checks); the shared corridor diagram is
     * never mutated, each call lays lines on a fresh copy of its axis.
     */
    public String diagramJson(Job job, CorridorService.Corridor corridor,
                              Map<String, String> stationGroups, Set<String> hiddenStations) {
        SimDiagram diagram = SimDiagram.fromPath(job.simGraph, corridor.path(),
                stationGroups, hiddenStations);
        diagram.pinUnmappedStationEdges();
        diagram.populateLines(job.result, job.specs, -1, 60, 300, Set.of());

        StringBuilder json = new StringBuilder(8192);
        json.append("{\"simId\":\"").append(job.id)
                .append("\",\"graphId\":\"").append(job.graphId)
                .append("\",\"graphVersion\":").append(job.graphVersion)
                .append(",\"from\":").append(quote(corridor.from()))
                .append(",\"to\":").append(quote(corridor.to()))
                .append(",\"length\":").append(round1(diagram.corridorLength))
                .append(",\"start\":").append(job.startDayTimeUsed)
                .append(",\"rate\":").append(job.dayTimeRate)
                .append(",\"ticks\":").append(job.result.ticksSimulated)
                .append(",\"stations\":[");
        boolean first = true;
        for (SimDiagram.StationMark mark : diagram.stations) {
            if (!first) json.append(',');
            first = false;
            json.append('[').append(quote(mark.name())).append(',')
                    .append(round1(mark.pos())).append(']');
        }
        json.append("],\"lines\":[");
        first = true;
        for (SimDiagram.Line line : diagram.lines) {
            SimTrainSpec spec = job.specs.get(line.train());
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":").append(quote(spec.id))
                    .append(",\"name\":").append(quote(spec.name))
                    .append(",\"segs\":[");
            for (int s = 0; s < line.segments().size(); s++) {
                if (s > 0) json.append(',');
                json.append('[');
                List<SimDiagram.Point> segment = line.segments().get(s);
                for (int p = 0; p < segment.size(); p++) {
                    if (p > 0) json.append(',');
                    json.append('[').append(segment.get(p).tick()).append(',')
                            .append(round1(segment.get(p).pos())).append(']');
                }
                json.append(']');
            }
            json.append("]}");
        }
        json.append("],\"dropped\":").append(diagram.linesDropped).append('}');
        return json.toString();
    }

    static byte[] gzip(byte[] data) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 6));
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(data);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("in-memory gzip failed", e);
        }
    }
}
