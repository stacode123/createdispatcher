package net.Dispatcher.web.corridor;

import net.Dispatcher.DispatcherMod;
import net.Dispatcher.content.simulator.core.SimEdge;
import net.Dispatcher.content.simulator.core.SimGraph;
import net.Dispatcher.content.simulator.core.SimResult;
import net.Dispatcher.content.simulator.core.SimTrainSpec;
import net.Dispatcher.web.live.HistoryRing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-calibrating drift correction for the plan overlay. Every time a
 * projection is replaced, the outgoing one is scored against what actually
 * happened (station arrivals predicted vs realized from the history ring),
 * yielding per-sample drift RATES (error ticks per horizon tick). A robust
 * per-graph EMA of the median rate and its spread ships to the client, which
 * stretches plan time by the rate and draws an uncertainty band that widens
 * with horizon. The residual drift is chaos (route micro-choices amplified
 * by carried sign zones, contention phasing) — not correctable in the model,
 * but measurable and therefore honest to display.
 *
 * <p>Drift varies a lot by route, so each train additionally accumulates its
 * OWN estimate ({@link #forTrain}): a quiet-branch train earns a tight band
 * and small stretch while a congested-core train earns wide ones. Per-train
 * evidence arrives at only a few samples per projection generation, so the
 * client falls back to the graph estimate until a train has proven itself.
 */
public final class PlanCalibration {

    /** Shipped to the client: {@code stretch} = drift rate (real ≈ plan × (1+stretch)),
     *  {@code sigmaPerMinuteTicks} = 1-sigma band growth per minute of horizon. */
    public record Cal(double stretch, double sigmaPerMinuteTicks, int samples) {}

    /** Below this much evidence the client gets no calibration at all. */
    private static final double MIN_WEIGHT = 8;
    /** Sane clamp: −5 % (plan pessimistic) … +50 % (plan wildly optimistic). */
    private static final double RATE_MIN = -0.05, RATE_MAX = 0.5;
    /** Per-train memory cap — smaller than the graph's 200: data is ~20× sparser. */
    private static final double TRAIN_WEIGHT_CAP = 60;
    /** Per-train entries untouched this long are dropped (train renamed/removed). */
    private static final long TRAIN_STATE_TTL_MS = 6 * 60 * 60 * 1000L;
    /** Mean |deviation| → sigma for a normal distribution. */
    private static final double MEAN_ABS_DEV_TO_SIGMA = 1.2533;

    private static final class State {
        double rate;
        double spread;
        double weight;
        long updatedMs;
    }

    private final Map<UUID, State> byGraph = new ConcurrentHashMap<>();
    private final Map<String, State> byTrain = new ConcurrentHashMap<>();
    /** Persistence target; null until {@link #bind} (estimates then stay in-memory only). */
    private volatile java.nio.file.Path file;

    /**
     * Attaches the on-disk store and restores previously learned estimates,
     * so a server restart doesn't reset the calibration warm-up. Call once,
     * before scoring starts (first server tick).
     */
    public void bind(java.nio.file.Path file) {
        this.file = file;
        try {
            if (!java.nio.file.Files.exists(file)) return;
            com.google.gson.JsonObject root = com.google.gson.JsonParser
                    .parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
            long now = System.currentTimeMillis();
            loadStates(root.getAsJsonObject("graphs"),
                    key -> byGraph.computeIfAbsent(UUID.fromString(key), k -> new State()), 0);
            loadStates(root.getAsJsonObject("trains"),
                    key -> byTrain.computeIfAbsent(key, k -> new State()), now - TRAIN_STATE_TTL_MS);
            DispatcherMod.LOGGER.info("Restored drift calibration for {} graph(s), {} train(s)",
                    byGraph.size(), byTrain.size());
        } catch (Exception e) {
            DispatcherMod.LOGGER.error("Could not load drift calibration from {}", file, e);
        }
    }

    private static void loadStates(com.google.gson.JsonObject states,
                                   java.util.function.Function<String, State> stateFor,
                                   long minUpdatedMs) {
        if (states == null) return;
        for (String key : states.keySet()) {
            try {
                com.google.gson.JsonObject entry = states.getAsJsonObject(key);
                long updatedMs = entry.get("u").getAsLong();
                if (updatedMs < minUpdatedMs) continue;
                State state = stateFor.apply(key);
                state.rate = entry.get("r").getAsDouble();
                state.spread = entry.get("s").getAsDouble();
                state.weight = entry.get("w").getAsDouble();
                state.updatedMs = updatedMs;
            } catch (Exception e) {
                DispatcherMod.LOGGER.error("Skipping unreadable calibration entry {}", key, e);
            }
        }
    }

    /** Writes the current estimates (called after each scoring pass, sim thread). */
    private void persist() {
        java.nio.file.Path target = file;
        if (target == null) return;
        try {
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            root.add("graphs", statesJson(byGraph, UUID::toString));
            root.add("trains", statesJson(byTrain, key -> key));
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            java.nio.file.Files.writeString(tmp, root.toString());
            try {
                java.nio.file.Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException e) {
                java.nio.file.Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            DispatcherMod.LOGGER.error("Could not persist drift calibration to {}", target, e);
        }
    }

    private static <K> com.google.gson.JsonObject statesJson(
            Map<K, State> states, java.util.function.Function<K, String> keyOf) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        for (Map.Entry<K, State> entry : states.entrySet()) {
            State state = entry.getValue();
            com.google.gson.JsonObject value = new com.google.gson.JsonObject();
            synchronized (state) {
                value.addProperty("r", Math.round(state.rate * 1e6) / 1e6);
                value.addProperty("s", Math.round(state.spread * 1e6) / 1e6);
                value.addProperty("w", Math.round(state.weight * 100) / 100.0);
                value.addProperty("u", state.updatedMs);
            }
            json.add(keyOf.apply(entry.getKey()), value);
        }
        return json;
    }

    /** Current calibration for a graph, or null while evidence is thin. */
    public Cal get(UUID graphId) {
        return read(byGraph.get(graphId));
    }

    /** A train's own calibration, or null until it has enough history of its own. */
    public Cal forTrain(String trainId) {
        return read(byTrain.get(trainId));
    }

    private static Cal read(State state) {
        if (state == null) return null;
        synchronized (state) {
            if (state.weight < MIN_WEIGHT) return null;
            return new Cal(Math.round(state.rate * 1000) / 1000.0,
                    Math.round(state.spread * 1200 * 10) / 10.0, (int) state.weight);
        }
    }

    /**
     * Score a replaced projection against realized history. {@code nowTick}
     * is the replacement's snapshot tick — only predictions that reality has
     * had time to answer are used. Any thread (snapshots are immutable).
     */
    public void score(ProjectionSims.Projection old, List<HistoryRing.Snapshot> history,
                      long nowTick) {
        try {
            scoreImpl(old, history, nowTick);
        } catch (Throwable t) {
            DispatcherMod.LOGGER.error("Dispatcher web: plan calibration scoring failed", t);
        }
    }

    private void scoreImpl(ProjectionSims.Projection old, List<HistoryRing.Snapshot> history,
                           long nowTick) {
        Map<String, HistoryRing.Snapshot> byId = new HashMap<>();
        for (HistoryRing.Snapshot snapshot : history)
            byId.putIfAbsent(snapshot.trainId().toString(), snapshot);

        SimGraph graph = old.graph();
        List<Double> rates = new ArrayList<>();
        Map<String, List<Double>> ratesByTrain = new HashMap<>();
        for (int i = 0; i < old.specs().size(); i++) {
            SimTrainSpec spec = old.specs().get(i);
            HistoryRing.Snapshot snapshot = byId.get(spec.id);
            if (snapshot == null) continue;
            for (SimResult.StationVisit visit : old.result().trains.get(i).visits) {
                long horizon = visit.arrivalTick();
                // Near-anchor arrivals carry no drift signal; unplayed-out
                // predictions have no realized counterpart yet.
                if (horizon < 1200) continue;
                long predicted = old.startGameTick() + horizon;
                if (predicted > nowTick - 600) continue;
                SimGraph.StationTarget target = graph.findStation(visit.stationId());
                if (target == null) continue;
                long realized = realizedArrival(graph, snapshot, target, predicted);
                if (realized == Long.MIN_VALUE) continue;
                double rate = (realized - predicted) / (double) horizon;
                rates.add(rate);
                ratesByTrain.computeIfAbsent(spec.id, k -> new ArrayList<>()).add(rate);
            }
        }
        if (rates.size() < 3) return;

        double[] sorted = rates.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double rawMedian = sorted[sorted.length / 2];
        double[] deviations = Arrays.stream(sorted)
                .map(r -> Math.abs(r - rawMedian)).sorted().toArray();
        double mad = deviations[deviations.length / 2] * 1.4826;
        double median = Math.max(RATE_MIN, Math.min(RATE_MAX, rawMedian));

        long now = System.currentTimeMillis();
        State state = byGraph.computeIfAbsent(old.graphId(), k -> new State());
        synchronized (state) {
            double batch = Math.min(sorted.length, 20);
            double total = state.weight + batch;
            state.rate = (state.rate * state.weight + median * batch) / total;
            state.spread = (state.spread * state.weight + mad * batch) / total;
            // Cap the memory so the coefficient keeps adapting to the network.
            state.weight = Math.min(total, 200);
            state.updatedMs = now;
        }

        // Per-train estimates: batches are tiny (1–5 visits), so spread is
        // learned as an EMA of |rate − running mean| — the train's own
        // consistency over time — rather than a within-batch MAD.
        for (Map.Entry<String, List<Double>> entry : ratesByTrain.entrySet()) {
            double[] trainRates = entry.getValue().stream()
                    .mapToDouble(Double::doubleValue).sorted().toArray();
            double trainMedian = Math.max(RATE_MIN, Math.min(RATE_MAX,
                    trainRates[trainRates.length / 2]));
            State trainState = byTrain.computeIfAbsent(entry.getKey(), k -> new State());
            synchronized (trainState) {
                double deviation = trainState.weight > 0
                        ? Math.abs(trainMedian - trainState.rate) * MEAN_ABS_DEV_TO_SIGMA
                        : mad; // first sight: borrow the network's spread
                double batch = Math.min(trainRates.length, 5);
                double total = trainState.weight + batch;
                trainState.rate = (trainState.rate * trainState.weight + trainMedian * batch) / total;
                trainState.spread = (trainState.spread * trainState.weight + deviation * batch) / total;
                trainState.weight = Math.min(total, TRAIN_WEIGHT_CAP);
                trainState.updatedMs = now;
            }
        }
        byTrain.values().removeIf(trainState -> now - trainState.updatedMs > TRAIN_STATE_TTL_MS);
        persist();
    }

    /**
     * The realized tick nearest to {@code predicted} at which the train's
     * ring shows it within 30 blocks of the platform (either track
     * direction), searched ±5 min — or {@code Long.MIN_VALUE}.
     */
    private static long realizedArrival(SimGraph graph, HistoryRing.Snapshot snapshot,
                                        SimGraph.StationTarget target, long predicted) {
        SimEdge edge = graph.edge(target.edgeId());
        int twin = edge.oppositeId;
        double mirrored = edge.length - target.offset();
        long best = Long.MIN_VALUE;
        long[] ticks = snapshot.ticks();
        int[] edges = snapshot.edges();
        float[] offsets = snapshot.offsets();
        for (int i = 0; i < ticks.length; i++) {
            long tick = ticks[i];
            if (tick < predicted - 6000 || tick > predicted + 6000) continue;
            boolean here = (edges[i] == target.edgeId()
                            && Math.abs(offsets[i] - target.offset()) <= 30)
                    || (twin >= 0 && edges[i] == twin
                            && Math.abs(offsets[i] - mirrored) <= 30);
            if (here && (best == Long.MIN_VALUE
                    || Math.abs(tick - predicted) < Math.abs(best - predicted)))
                best = tick;
        }
        return best;
    }
}
