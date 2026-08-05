package net.Dispatcher.web.monitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import net.Dispatcher.config.DispatcherConfig;
import net.Dispatcher.web.graph.WebGraphStore;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Live deadlock detection: builds the wait-for digraph (waiting train → trains holding or
 * reserving its wanted signal group, including diamond-crossing intersections — mirroring
 * {@code SignalEdgeGroup.isOccupiedUnless}), finds strongly connected components, and raises
 * CRITICAL once a cycle's membership has persisted for the confirm window (filters transient
 * crossing waits). Server thread, every ~5 s; ~1k nodes is microseconds.
 */
public final class DeadlockAnalyzer {
    /** member-set key → wall ms first observed. */
    private final Map<String, Long> candidateSinceMs = new HashMap<>();

    public void analyze(MinecraftServer server, WebGraphStore store, NotificationHub hub, long gameTick) {
        var railways = Create.RAILWAYS.sided(server.overworld());
        Map<UUID, Train> trains = railways.trains;

        // reservers: chained-signal claims are wait-for edges too
        Map<UUID, List<Train>> reservers = new HashMap<>();
        for (Train train : trains.values())
            for (UUID group : train.reservedSignalBlocks)
                reservers.computeIfAbsent(group, k -> new ArrayList<>()).add(train);

        Map<UUID, List<UUID>> waitFor = new HashMap<>();
        Map<UUID, Train> waiting = new HashMap<>();
        for (Train train : trains.values()) {
            if (train.graph == null || train.navigation == null || train.navigation.waitingForSignal == null)
                continue;
            SignalBoundary boundary =
                    train.graph.getPoint(EdgePointType.SIGNAL, train.navigation.waitingForSignal.getFirst());
            if (boundary == null || boundary.groups == null) continue;
            UUID groupId = boundary.groups.get(train.navigation.waitingForSignal.getSecond());
            if (groupId == null) continue;

            Set<UUID> blockers = new LinkedHashSet<>();
            SignalEdgeGroup group = railways.signalEdgeGroups.get(groupId);
            if (group != null) {
                addTrains(blockers, group.trains, train);
                for (SignalEdgeGroup crossing : group.intersectingResolved)
                    addTrains(blockers, crossing.trains, train);
            }
            for (Train reserver : reservers.getOrDefault(groupId, List.of()))
                if (reserver != train) blockers.add(reserver.id);
            if (blockers.isEmpty()) continue;
            waiting.put(train.id, train);
            waitFor.put(train.id, new ArrayList<>(blockers));
        }

        // Tarjan SCC over the waiting trains (non-waiting blockers are sinks and never cycle).
        List<List<UUID>> cycles = TarjanScc.cyclesOf(waitFor);

        long confirmMs = DispatcherConfig.COMMON.WebDeadlockConfirmSeconds.get() * 1000L;
        long now = System.currentTimeMillis();
        Set<String> currentKeys = new HashSet<>();

        for (List<UUID> members : cycles) {
            members.sort(Comparator.comparing(UUID::toString));
            String key = Integer.toHexString(members.toString().hashCode());
            currentKeys.add(key);
            long since = candidateSinceMs.computeIfAbsent(key, k -> now);
            if (now - since < confirmMs) continue;

            List<WebNotification.TrainRef> refs = new ArrayList<>();
            StringBuilder names = new StringBuilder();
            for (UUID member : members) {
                Train train = waiting.get(member);
                if (train == null) continue;
                String name = train.name.getString();
                refs.add(new WebNotification.TrainRef(member, name));
                if (names.length() > 0) names.append(" ⇄ ");
                names.append(name);
            }
            Train anchor = waiting.get(members.get(0));
            MonitorPositions.Pos pos = anchor != null ? MonitorPositions.locate(store, anchor) : null;
            if (pos == null) continue;
            JsonObject data = new JsonObject();
            JsonArray cycle = new JsonArray();
            for (WebNotification.TrainRef ref : refs) cycle.add(ref.name());
            data.add("cycle", cycle);
            hub.raiseOrUpdate(new WebNotification("deadlock:" + key, WebNotification.Kind.DEADLOCK,
                    WebNotification.Severity.CRITICAL,
                    "Deadlock: " + names + " (" + refs.size() + " trains locked)",
                    refs, pos.graphId(), pos.x(), pos.z(), pos.dim(),
                    gameTick, gameTick, null, data));
        }

        candidateSinceMs.keySet().retainAll(currentKeys);
        for (WebNotification notification : hub.active())
            if (notification.kind() == WebNotification.Kind.DEADLOCK
                    && !currentKeys.contains(notification.id().substring("deadlock:".length())))
                hub.resolve(notification.id(), gameTick);
    }

    private static void addTrains(Set<UUID> blockers, Set<Train> occupants, Train self) {
        if (occupants == null) return;
        for (Train occupant : occupants)
            if (occupant != null && occupant != self) blockers.add(occupant.id);
    }
}
