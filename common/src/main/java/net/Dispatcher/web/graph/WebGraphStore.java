package net.Dispatcher.web.graph;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import net.Dispatcher.DispatcherMod;
import net.Dispatcher.config.DispatcherConfig;
import net.Dispatcher.content.graph.v2.RailGraph;
import net.Dispatcher.content.graph.v2.RailGraphJson;
import net.Dispatcher.content.graph.v2.RailGraphTranslator;
import net.Dispatcher.content.simulator.SimGraphBuilder;
import net.Dispatcher.content.simulator.SimTopology;
import net.Dispatcher.content.simulator.core.SimGraph;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/**
 * Web-side graph snapshots, one per Create TrackGraph. Deliberately NOT {@code RailGraphCache}
 * (its 10 s TTL would re-translate huge networks on a poll cadence): rebuilds happen only when
 * {@code TrackGraph.getChecksum()} changes or the entry exceeds max age (catching station/signal
 * edits the checksum misses), floored by a min-interval.
 *
 * <p>Translation runs on the server thread (translator requirement); JSON serialization and
 * SimGraph derivation run on the analyzer thread over the immutable result. Published entries
 * are immutable; node/edge ids are only valid within one version.
 */
public final class WebGraphStore {
    public record Entry(UUID id, int version, RailGraph graph, SimTopology topology, SimGraph simGraph,
                        byte[] gzJson, double[][] bbox, int stationCount, int microNodes, boolean tooLarge,
                        int checksum, long builtAtMs) {}

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastAttemptMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> versions = new ConcurrentHashMap<>();
    private boolean readyLogged;
    private final ExecutorService analyzer;
    /** (graphId, version) after a full entry is published. */
    private final BiConsumer<UUID, Integer> onGraphBuilt;
    /** Networks appeared/vanished/refused — clients refetch the index. */
    private final Runnable onIndexChanged;

    public WebGraphStore(ExecutorService analyzer, BiConsumer<UUID, Integer> onGraphBuilt, Runnable onIndexChanged) {
        this.analyzer = analyzer;
        this.onGraphBuilt = onGraphBuilt;
        this.onIndexChanged = onIndexChanged;
    }

    public Entry get(UUID graphId) {
        return entries.get(graphId);
    }

    public Collection<Entry> all() {
        return entries.values();
    }

    public Map<String, Integer> versionMap() {
        Map<String, Integer> map = new java.util.HashMap<>();
        for (Entry entry : entries.values())
            if (!entry.tooLarge()) map.put(entry.id().toString(), entry.version());
        return map;
    }

    /** Forces the next poll to rebuild (all graphs, or one). Server thread. */
    public void forceRefresh(UUID graphId) {
        if (graphId == null) {
            lastAttemptMs.clear();
            for (Entry entry : entries.values())
                entries.computeIfPresent(entry.id(), (k, e) -> withChecksum(e, e.checksum() ^ 0x5f5f5f5f));
        } else {
            lastAttemptMs.remove(graphId);
            entries.computeIfPresent(graphId, (k, e) -> withChecksum(e, e.checksum() ^ 0x5f5f5f5f));
        }
    }

    private static Entry withChecksum(Entry e, int checksum) {
        return new Entry(e.id(), e.version(), e.graph(), e.topology(), e.simGraph(), e.gzJson(), e.bbox(),
                e.stationCount(), e.microNodes(), e.tooLarge(), checksum, e.builtAtMs());
    }

    /**
     * Node checksum + signal/station point counts. Create's own checksum only tracks nodes, so
     * signal/station edits would otherwise go unseen until the max-age backstop.
     */
    private static int extendedChecksum(TrackGraph graph) {
        return graph.getChecksum()
                ^ (graph.getPoints(EdgePointType.SIGNAL).size() * 0x9E3779B9)
                ^ (graph.getPoints(EdgePointType.STATION).size() * 0x85EBCA6B);
    }

    /**
     * SERVER THREAD, called from the tick loop every few seconds. Real edits (checksum change)
     * and new networks rebuild first, smallest first so the map fills fast; pure max-age
     * refreshes ride the same queue. The whole poll is time-budgeted (~50 ms) so a server with
     * hundreds of networks — or one 80k-node monster — never stalls a tick with a translate storm.
     */
    public void maybeRebuild(MinecraftServer server) {
        DispatcherConfig.Common config = DispatcherConfig.COMMON;
        long now = System.currentTimeMillis();
        long minIntervalMs = config.WebGraphMinRebuildSeconds.get() * 1000L;
        long maxAgeMs = config.WebGraphMaxAgeSeconds.get() * 1000L;
        Map<UUID, TrackGraph> networks = Create.RAILWAYS.sided(server.overworld()).trackNetworks;

        // prune vanished networks
        Set<UUID> live = new HashSet<>(networks.keySet());
        boolean pruned = entries.keySet().removeIf(id -> !live.contains(id));
        if (pruned) onIndexChanged.run();

        List<TrackGraph> priority = new ArrayList<>();
        List<TrackGraph> aged = new ArrayList<>();
        for (TrackGraph trackGraph : networks.values()) {
            Entry entry = entries.get(trackGraph.id);
            int checksum = extendedChecksum(trackGraph);
            Long attempted = lastAttemptMs.get(trackGraph.id);
            if (attempted != null && now - attempted < minIntervalMs) continue;
            if (entry == null || entry.checksum() != checksum) priority.add(trackGraph);
            else if (now - entry.builtAtMs() > maxAgeMs) aged.add(trackGraph);
        }

        if (priority.isEmpty() && aged.isEmpty()) {
            if (!readyLogged && !networks.isEmpty()) {
                readyLogged = true;
                DispatcherMod.LOGGER.info("Dispatcher web: graph snapshots ready ({} network(s))", entries.size());
            }
            return;
        }

        Comparator<TrackGraph> smallestFirst = Comparator.comparingInt(g -> g.getNodes().size());
        priority.sort(smallestFirst);
        aged.sort(smallestFirst);
        List<TrackGraph> queue = new ArrayList<>(priority);
        queue.addAll(aged);

        long budgetNanos = 50_000_000L;
        long start = System.nanoTime();
        int rebuilt = 0;
        for (TrackGraph trackGraph : queue) {
            if (rebuilt >= 1 && System.nanoTime() - start > budgetNanos) break;
            lastAttemptMs.put(trackGraph.id, now);
            rebuild(trackGraph, extendedChecksum(trackGraph));
            rebuilt++;
        }
    }

    /** SERVER THREAD: translate, then hand assembly to the analyzer thread. */
    private void rebuild(TrackGraph trackGraph, int checksum) {
        int nodeCap = DispatcherConfig.COMMON.WebGraphNodeCap.get();
        long start = System.nanoTime();
        RailGraphTranslator.Result result = RailGraphTranslator.translate(trackGraph, nodeCap);
        long millis = (System.nanoTime() - start) / 1_000_000;
        UUID id = trackGraph.id;
        if (millis > 100)
            DispatcherMod.LOGGER.info("Dispatcher web: translated graph {} in {} ms ({} micro nodes{})",
                    shortId(id), millis, result.microNodeCount(),
                    result.graph() == null ? ", OVER CAP — refused" : "");

        if (result.graph() == null) {
            Entry previous = entries.get(id);
            if (previous != null && previous.tooLarge()) {
                // still refused — just refresh staleness bookkeeping, no version bump, no events
                entries.put(id, new Entry(id, previous.version(), null, null, null, null,
                        new double[0][], 0, result.microNodeCount(), true, checksum,
                        System.currentTimeMillis()));
                return;
            }
            int version = versions.merge(id, 1, Integer::sum);
            entries.put(id, new Entry(id, version, null, null, null, null, new double[0][], 0,
                    result.microNodeCount(), true, checksum, System.currentTimeMillis()));
            onIndexChanged.run();
            return;
        }
        analyzer.execute(() -> {
            try {
                RailGraphJson.Built built = RailGraphJson.build(result.graph());
                Entry previous = entries.get(id);
                // Identical content (max-age refresh with no real change): keep the version so no
                // client refetches, no events, no log — just clear the staleness clock.
                if (previous != null && !previous.tooLarge() && previous.gzJson() != null
                        && java.util.Arrays.equals(previous.gzJson(), built.gzJson())) {
                    entries.put(id, new Entry(id, previous.version(), previous.graph(),
                            previous.topology(), previous.simGraph(), previous.gzJson(),
                            previous.bbox(), previous.stationCount(), previous.microNodes(),
                            false, checksum, System.currentTimeMillis()));
                    return;
                }
                SimGraph simGraph = SimGraphBuilder.build(result.graph(), result.topology());
                int version = versions.merge(id, 1, Integer::sum);
                Entry fresh = new Entry(id, version, result.graph(), result.topology(), simGraph,
                        built.gzJson(), built.bbox(), built.stationCount(), result.microNodeCount(),
                        false, checksum, System.currentTimeMillis());
                Entry current = entries.get(id);
                if (current == null || current.version() <= version) {
                    boolean wasAbsent = current == null || current.tooLarge();
                    entries.put(id, fresh);
                    onGraphBuilt.accept(id, version);
                    if (wasAbsent) onIndexChanged.run();
                    else DispatcherMod.LOGGER.info("Dispatcher web: graph {} updated to v{} ({} edges)",
                            shortId(id), version, result.graph().edges.size());
                }
            } catch (Throwable t) {
                DispatcherMod.LOGGER.error("Dispatcher web: graph {} assembly failed", shortId(id), t);
            }
        });
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    public static List<String> describe(Collection<Entry> entries) {
        List<String> lines = new ArrayList<>();
        for (Entry entry : entries)
            lines.add(shortId(entry.id()) + " v" + entry.version()
                    + (entry.tooLarge() ? " TOO LARGE (" + entry.microNodes() + " micro nodes)"
                    : " edges=" + entry.graph().edges.size() + " stations=" + entry.stationCount())
                    + " age=" + (System.currentTimeMillis() - entry.builtAtMs()) / 1000 + "s");
        return lines;
    }
}
