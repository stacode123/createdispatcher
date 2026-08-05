package net.Dispatcher.web.monitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.UUID;

/** One live operational issue (raised → updated → resolved), shipped verbatim to web clients. */
public record WebNotification(String id, Kind kind, Severity severity, String message,
                              List<TrainRef> trains, UUID graphId, double x, double z, String dim,
                              long sinceTick, long updatedTick, Long resolvedTick, JsonObject data) {

    public enum Kind { SIGNAL_WAIT, DEADLOCK, DETOUR }

    public enum Severity { WARN, CRITICAL }

    public record TrainRef(UUID id, String name) {}

    public WebNotification withResolved(long tick) {
        return new WebNotification(id, kind, severity, message, trains, graphId, x, z, dim,
                sinceTick, tick, tick, data);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("kind", kind.name());
        json.addProperty("severity", severity.name());
        json.addProperty("state", resolvedTick == null ? "ACTIVE" : "RESOLVED");
        json.addProperty("message", message);
        JsonArray trainsJson = new JsonArray();
        for (TrainRef train : trains) {
            JsonObject ref = new JsonObject();
            ref.addProperty("id", train.id().toString());
            ref.addProperty("name", train.name());
            trainsJson.add(ref);
        }
        json.add("trains", trainsJson);
        json.addProperty("graphId", graphId == null ? null : graphId.toString());
        json.addProperty("x", Math.round(x * 10) / 10.0);
        json.addProperty("z", Math.round(z * 10) / 10.0);
        json.addProperty("dim", dim);
        json.addProperty("sinceTick", sinceTick);
        json.addProperty("updatedTick", updatedTick);
        if (resolvedTick != null) json.addProperty("resolvedTick", resolvedTick);
        json.add("data", data == null ? new JsonObject() : data);
        return json;
    }

    /** "2m 30s" from game ticks. */
    public static String formatTicks(long ticks) {
        long seconds = ticks / 20;
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }
}
