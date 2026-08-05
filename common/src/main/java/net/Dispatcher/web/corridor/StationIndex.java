package net.Dispatcher.web.corridor;

import net.Dispatcher.content.simulator.core.SimDiagram;
import net.Dispatcher.content.simulator.core.SimEdge;
import net.Dispatcher.content.simulator.core.SimGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Logical stations of one graph: platforms grouped by CRN station tags when available,
 * otherwise by stripping the trailing platform number — the same grouping the diagrams use,
 * so a corridor picked "A → B" and its axis labels always agree.
 */
public final class StationIndex {

    public record Platform(UUID id, String name, int edgeId, double offset, boolean approachable) {}

    private final Map<String, List<Platform>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private StationIndex() {}

    public static StationIndex of(SimGraph graph, Map<String, String> tagGroups) {
        StationIndex index = new StationIndex();
        for (SimEdge edge : graph.edges)
            for (SimEdge.Station station : edge.stations) {
                String tagged = tagGroups.get(station.name());
                String group = tagged != null ? tagged : SimDiagram.baseName(station.name());
                index.groups.computeIfAbsent(group, key -> new ArrayList<>()).add(new Platform(
                        station.id(), station.name(), edge.id, station.offset(),
                        station.approachable()));
            }
        for (List<Platform> platforms : index.groups.values())
            platforms.sort(Comparator.comparing(Platform::name)
                    .thenComparing(platform -> platform.id().toString()));
        return index;
    }

    /** Group names, sorted case-insensitively. */
    public List<String> groupNames() {
        return List.copyOf(groups.keySet());
    }

    /** Platforms of a group (deterministic order), empty when unknown. */
    public List<Platform> platforms(String group) {
        List<Platform> platforms = groups.get(group);
        return platforms == null ? List.of() : platforms;
    }
}
