package net.Dispatcher.web.sim;

import net.Dispatcher.content.simulator.HeadlessSimService;
import net.Dispatcher.content.simulator.NetworkSnapshotter;
import net.Dispatcher.content.simulator.core.SimConflict;
import net.Dispatcher.content.simulator.core.SimProgram;
import net.Dispatcher.content.simulator.core.SimResult;
import net.Dispatcher.content.simulator.core.SimTrainSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static net.Dispatcher.web.corridor.CorridorService.quote;
import static net.Dispatcher.web.corridor.CorridorService.round1;

/**
 * Serializes a finished planner sim for the browser. The playback-consumed
 * parts ({@code meta}, {@code trains[].s/path/v}) keep SimDebugExporter's
 * shape so the frontend's fixture playback code consumes real results
 * unchanged; graph geometry is omitted (the client already holds the
 * versioned graph — {@code meta.graphVersion} pins the edge-id space) and
 * conflicts/root causes ride along as additive keys.
 */
final class SimResultJson {

    private SimResultJson() {}

    static String build(UUID graphId, int graphVersion, long baseTick, SimResult result,
                        List<SimTrainSpec> specs, long startDayTime, double dayTimeRate,
                        int sampleStride, List<NetworkSnapshotter.Excluded> excluded,
                        List<HeadlessSimService.OverrideIssue> overrideIssues,
                        Set<String> overriddenTrainIds, List<String> removedTrains) {
        StringBuilder json = new StringBuilder(1 << 18);
        json.append("{\"meta\":{\"graphId\":\"").append(graphId)
                .append("\",\"graphVersion\":").append(graphVersion)
                .append(",\"baseTick\":").append(baseTick)
                .append(",\"start\":").append(startDayTime)
                .append(",\"rate\":").append(dayTimeRate)
                .append(",\"ticks\":").append(result.ticksSimulated)
                .append(",\"stride\":").append(sampleStride)
                .append(",\"truncated\":").append(result.truncated)
                .append("},\"removed\":[");
        for (int i = 0; i < removedTrains.size(); i++) {
            if (i > 0) json.append(',');
            json.append(quote(removedTrains.get(i)));
        }
        json.append("],\"trains\":[");

        for (int i = 0; i < result.trains.size(); i++) {
            SimResult.TrainResult train = result.trains.get(i);
            SimTrainSpec spec = specs.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(quote(train.id))
                    .append(",\"n\":").append(quote(train.name))
                    .append(",\"o\":").append(train.obstacle ? 1 : 0)
                    .append(",\"a\":").append(overriddenTrainIds.contains(train.id) ? 1 : 0)
                    .append(",\"len\":").append(round1(spec.length))
                    .append(",\"ts\":").append(Math.round(spec.topSpeed * 1000) / 1000.0)
                    .append(",\"end\":").append(quote(train.endState))
                    .append(",\"s\":[");
            for (int k = 0; k < train.samples.size(); k++) {
                SimResult.Sample sample = train.samples.get(k);
                if (k > 0) json.append(',');
                json.append('[').append(sample.tick()).append(',').append(sample.edgeId())
                        .append(',').append(round1(sample.offset()))
                        .append(',').append(Math.round(sample.speed() * 1000) / 1000.0).append(']');
            }
            json.append("],\"path\":[");
            for (int k = 0; k < train.path.size(); k++) {
                if (k > 0) json.append(',');
                json.append(train.path.get(k));
            }
            json.append("],\"v\":[");
            for (int k = 0; k < train.visits.size(); k++) {
                SimResult.StationVisit visit = train.visits.get(k);
                if (k > 0) json.append(',');
                json.append('[').append(visit.arrivalTick()).append(',')
                        .append(visit.departureTick()).append(',')
                        .append(quote(visit.stationName())).append(']');
            }
            json.append("],\"fail\":[");
            appendFailedFilters(json, result, spec, i);
            json.append("]}");
        }

        json.append("],\"excluded\":[");
        for (int i = 0; i < excluded.size(); i++) {
            NetworkSnapshotter.Excluded line = excluded.get(i);
            if (i > 0) json.append(',');
            json.append('[').append(quote(line.trainName())).append(',')
                    .append(quote(line.translationKey())).append(',')
                    .append(quote(line.detail())).append(']');
        }

        json.append("],\"overrideIssues\":[");
        for (int i = 0; i < overrideIssues.size(); i++) {
            HeadlessSimService.OverrideIssue issue = overrideIssues.get(i);
            if (i > 0) json.append(',');
            json.append('[').append(quote(issue.trainName())).append(',')
                    .append(quote(issue.translationKey())).append(',')
                    .append(quote(issue.detail())).append(']');
        }

        // [typeOrdinal, start, end, count, x, z, dim, resource, [trainIdx...], nonDet]
        json.append("],\"conflicts\":[");
        for (int i = 0; i < result.conflicts.size(); i++) {
            SimConflict conflict = result.conflicts.get(i);
            if (i > 0) json.append(',');
            json.append('[').append(conflict.type().ordinal()).append(',')
                    .append(conflict.startTick()).append(',').append(conflict.endTick())
                    .append(',').append(conflict.count())
                    .append(',').append(round1(conflict.position().x()))
                    .append(',').append(round1(conflict.position().z()))
                    .append(',').append(conflict.dimension())
                    .append(',').append(quote(conflict.resourceName())).append(",[");
            for (int k = 0; k < conflict.trains().size(); k++) {
                if (k > 0) json.append(',');
                json.append(conflict.trains().get(k));
            }
            json.append("],").append(conflict.nonDeterministic() ? 1 : 0).append(']');
        }

        // [rootIdx, kind, detail, [strandedIdx...], sinceTick, x, z, dim]
        json.append("],\"rootCauses\":[");
        for (int i = 0; i < result.rootCauses.size(); i++) {
            SimResult.RootCause cause = result.rootCauses.get(i);
            if (i > 0) json.append(',');
            json.append('[').append(cause.rootTrain()).append(',')
                    .append(quote(cause.kind().name())).append(',')
                    .append(quote(cause.detail())).append(",[");
            for (int k = 0; k < cause.stranded().size(); k++) {
                if (k > 0) json.append(',');
                json.append(cause.stranded().get(k));
            }
            json.append("],").append(cause.sinceTick())
                    .append(',').append(round1(cause.position().x()))
                    .append(',').append(round1(cause.position().z()))
                    .append(',').append(cause.dimension()).append(']');
        }
        return json.append("]}").toString();
    }

    /** Destination filters this train's navigation could not route to (deduped). */
    private static void appendFailedFilters(StringBuilder json, SimResult result,
                                            SimTrainSpec spec, int trainIndex) {
        SimProgram program = spec.program;
        if (program == null) return;
        Set<Integer> seen = new HashSet<>();
        boolean first = true;
        for (SimResult.SimEvent event : result.events) {
            if (event.trainIndex() != trainIndex
                    || event.type() != SimResult.EventType.PATH_FAILED
                    || !seen.add((int) event.data())
                    || event.data() >= program.entries.size())
                continue;
            if (!first) json.append(',');
            first = false;
            json.append(quote(program.entries.get((int) event.data()).filterText));
        }
    }
}
