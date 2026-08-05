package net.Dispatcher.content.trains.schedule.presets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.UUID;

/** Disk envelope for one preset: a flat JSON object, schedule as an SNBT string. */
final class PresetJson {
    private PresetJson() {}

    static String toJson(Preset preset) {
        JsonObject json = new JsonObject();
        json.addProperty("id", preset.id().toString());
        json.addProperty("name", preset.name());
        json.addProperty("folder", preset.folder());
        json.addProperty("source", preset.source());
        json.addProperty("createdMs", preset.createdMs());
        json.addProperty("updatedMs", preset.updatedMs());
        json.addProperty("entries", preset.entries());
        json.addProperty("schedule", preset.scheduleSnbt());
        return json.toString();
    }

    /** Throws on malformed input — the caller logs and skips the file. */
    static Preset fromJson(String text) {
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        return new Preset(
                UUID.fromString(json.get("id").getAsString()),
                json.get("name").getAsString(),
                // added after the first release — files written before it have no folder
                json.has("folder") ? json.get("folder").getAsString() : "",
                json.get("source").getAsString(),
                json.get("createdMs").getAsLong(),
                json.get("updatedMs").getAsLong(),
                json.get("entries").getAsInt(),
                json.get("schedule").getAsString());
    }
}
