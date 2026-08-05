package net.Dispatcher.web.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hand-rolled plan (de)serialization — explicit field reads, so a plan file written by
 * an older build still loads and unknown keys are ignored rather than fatal. Shared by
 * the on-disk format and the HTTP payloads (the API returns exactly what is stored).
 */
public final class PlanJson {

    private PlanJson() {}

    public static JsonObject toJson(Plan plan) {
        JsonObject json = new JsonObject();
        json.addProperty("id", plan.id().toString());
        json.addProperty("name", plan.name());
        json.addProperty("author", plan.author());
        json.addProperty("createdMs", plan.createdMs());
        json.addProperty("updatedMs", plan.updatedMs());
        json.addProperty("graphId", plan.graphId());
        json.addProperty("removeScheduled", plan.removeScheduled());
        json.addProperty("horizonHours", plan.horizonHours());
        json.addProperty("headwaySeconds", plan.headwaySeconds());
        json.addProperty("startTime", plan.startTime());
        JsonArray assignments = new JsonArray();
        for (Plan.Assignment assignment : plan.assignments()) {
            JsonObject row = new JsonObject();
            row.addProperty("trainId", assignment.trainId());
            row.addProperty("trainName", assignment.trainName());
            row.addProperty("presetId", assignment.presetId());
            row.addProperty("presetName", assignment.presetName());
            assignments.add(row);
        }
        json.add("assignments", assignments);
        json.add("keeps", refsJson(plan.keeps()));
        json.add("removals", refsJson(plan.removals()));
        return json;
    }

    /** Summary for list views: no per-train rows, just the counts. */
    public static JsonObject summaryJson(Plan plan) {
        JsonObject json = new JsonObject();
        json.addProperty("id", plan.id().toString());
        json.addProperty("name", plan.name());
        json.addProperty("author", plan.author());
        json.addProperty("createdMs", plan.createdMs());
        json.addProperty("updatedMs", plan.updatedMs());
        json.addProperty("graphId", plan.graphId());
        json.addProperty("removeScheduled", plan.removeScheduled());
        json.addProperty("assignments", plan.assignments().size());
        json.addProperty("keeps", plan.keeps().size());
        json.addProperty("removals", plan.removals().size());
        return json;
    }

    private static JsonArray refsJson(List<Plan.TrainRef> refs) {
        JsonArray array = new JsonArray();
        for (Plan.TrainRef ref : refs) {
            JsonObject row = new JsonObject();
            row.addProperty("trainId", ref.trainId());
            row.addProperty("trainName", ref.trainName());
            array.add(row);
        }
        return array;
    }

    public static Plan fromJson(String text) {
        return fromJson(JsonParser.parseString(text).getAsJsonObject());
    }

    /**
     * Reads a plan body. {@code id}, {@code author} and the timestamps come from the
     * caller for request bodies (the client must not choose them) and from the file
     * itself on load — hence {@link #withIdentity}.
     */
    public static Plan fromJson(JsonObject json) {
        List<Plan.Assignment> assignments = new ArrayList<>();
        if (json.has("assignments") && json.get("assignments").isJsonArray())
            for (JsonElement element : json.getAsJsonArray("assignments")) {
                JsonObject row = element.getAsJsonObject();
                assignments.add(new Plan.Assignment(string(row, "trainId"), string(row, "trainName"),
                        string(row, "presetId"), string(row, "presetName")));
            }
        return new Plan(uuid(json, "id"), string(json, "name"), string(json, "author"),
                number(json, "createdMs", 0), number(json, "updatedMs", 0),
                string(json, "graphId"), bool(json, "removeScheduled", true),
                (int) number(json, "horizonHours", 12), (int) number(json, "headwaySeconds", -1),
                string(json, "startTime"), assignments,
                refs(json, "keeps"), refs(json, "removals"));
    }

    /** The stored plan with server-owned identity fields applied. */
    public static Plan withIdentity(Plan plan, UUID id, String author, long createdMs, long updatedMs) {
        return new Plan(id, plan.name(), author, createdMs, updatedMs, plan.graphId(),
                plan.removeScheduled(), plan.horizonHours(), plan.headwaySeconds(),
                plan.startTime(), plan.assignments(), plan.keeps(), plan.removals());
    }

    private static List<Plan.TrainRef> refs(JsonObject json, String key) {
        List<Plan.TrainRef> refs = new ArrayList<>();
        if (json.has(key) && json.get(key).isJsonArray())
            for (JsonElement element : json.getAsJsonArray(key)) {
                JsonObject row = element.getAsJsonObject();
                refs.add(new Plan.TrainRef(string(row, "trainId"), string(row, "trainName")));
            }
        return refs;
    }

    private static String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static long number(JsonObject json, String key, long fallback) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsLong();
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
    }

    private static UUID uuid(JsonObject json, String key) {
        try {
            return UUID.fromString(string(json, key));
        } catch (Exception e) {
            return null;
        }
    }
}
