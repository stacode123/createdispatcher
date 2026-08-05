package net.Dispatcher.web.preset;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.Dispatcher.content.trains.schedule.presets.Preset;
import net.Dispatcher.content.trains.schedule.presets.PresetStore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.List;
import java.util.Map;

/**
 * Web view of the preset library: structured JSON out (never raw NBT) and the
 * value-edit whitelist — exactly the keys {@code ScheduleCompiler} reads, so
 * a browser edit can only touch values the simulator (and Create) understand.
 */
public final class PresetWeb {
    private PresetWeb() {}

    /** 400 with this message key when an edit falls outside the whitelist. */
    public static final class EditException extends Exception {
        public EditException(String key) {
            super(key);
        }
    }

    public record Field(String key, boolean string, int min, int max) {}

    private static Field intField(String key, int min, int max) {
        return new Field(key, false, min, max);
    }

    /** Editable keys per instruction/condition id — the ScheduleCompiler-matching whitelist. */
    private static final Map<String, List<Field>> EDITABLE = Map.of(
            "create:throttle", List.of(intField("Value", 5, 100)),
            "tramways:set_primary_limit", List.of(intField("Value", 5, 100)),
            "create:delay", List.of(intField("Value", 0, 10000), intField("TimeUnit", 0, 2)),
            "createrailwaysnavigator:dynamic_delay",
            List.of(intField("Value", 0, 10000), intField("Min", 0, 10000), intField("TimeUnit", 0, 2)),
            "create:time_of_day",
            List.of(intField("Hour", 0, 23), intField("Minute", 0, 59), intField("Rotation", 0, 9)),
            "realism:time_of_day_realistic",
            List.of(intField("Hour", 0, 23), intField("Minute", 0, 59)),
            "createrailwaysnavigator:train_separation",
            List.of(intField("Ticks", 0, 72000), intField("TrainFilter", 0, 3),
                    new Field("StationFilter", true, 0, 128)));

    /** Read-only display keys surfaced alongside the editable ones. */
    private static final List<String> DISPLAY_KEYS = List.of("Text", "Filters");

    public static JsonObject summaryJson(Preset preset) {
        JsonObject json = new JsonObject();
        json.addProperty("id", preset.id().toString());
        json.addProperty("name", preset.name());
        json.addProperty("folder", preset.folder());
        json.addProperty("source", preset.source());
        json.addProperty("createdMs", preset.createdMs());
        json.addProperty("updatedMs", preset.updatedMs());
        json.addProperty("entries", preset.entries());
        return json;
    }

    /** Structured schedule detail; {@code scheduleTag} is the parsed SNBT (never emitted raw). */
    public static JsonObject detailJson(Preset preset, CompoundTag scheduleTag) {
        JsonObject json = summaryJson(preset);
        json.addProperty("cyclic", scheduleTag.getBoolean("Cyclic"));
        JsonArray entries = new JsonArray();
        ListTag entryList = scheduleTag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < entryList.size(); i++) {
            CompoundTag entryTag = entryList.getCompound(i);
            JsonObject entry = new JsonObject();
            entry.add("instruction", nodeJson(entryTag.getCompound("Instruction")));
            JsonArray columns = new JsonArray();
            ListTag columnList = entryTag.getList("Conditions", Tag.TAG_LIST);
            for (int c = 0; c < columnList.size(); c++) {
                JsonArray column = new JsonArray();
                ListTag conditionList = (ListTag) columnList.get(c);
                for (int r = 0; r < conditionList.size(); r++)
                    column.add(nodeJson(conditionList.getCompound(r)));
                columns.add(column);
            }
            entry.add("conditions", columns);
            entries.add(entry);
        }
        json.add("schedule", entries);
        return json;
    }

    private static JsonObject nodeJson(CompoundTag node) {
        String id = node.getString("Id");
        CompoundTag data = node.getCompound("Data");
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        JsonObject fields = new JsonObject();
        for (String key : DISPLAY_KEYS) {
            if (!data.contains(key))
                continue;
            if (data.getTagType(key) == Tag.TAG_LIST) {
                StringBuilder joined = new StringBuilder();
                data.getList(key, Tag.TAG_STRING).forEach(tag -> {
                    if (joined.length() > 0)
                        joined.append(", ");
                    joined.append(tag.getAsString());
                });
                fields.addProperty(key, joined.toString());
            } else {
                fields.addProperty(key, data.getString(key));
            }
        }
        JsonArray editable = new JsonArray();
        for (Field field : EDITABLE.getOrDefault(id, List.of())) {
            JsonObject spec = new JsonObject();
            spec.addProperty("key", field.key());
            spec.addProperty("type", field.string() ? "string" : "int");
            if (!field.string()) {
                spec.addProperty("min", field.min());
                spec.addProperty("max", field.max());
            }
            editable.add(spec);
            if (field.string())
                fields.addProperty(field.key(), data.getString(field.key()));
            else
                fields.addProperty(field.key(), data.getInt(field.key()));
        }
        json.add("fields", fields);
        json.add("editable", editable);
        return json;
    }

    /**
     * Applies one whitelisted value edit in place. {@code target} is
     * {@code "instruction"} or {@code "condition"} (with column/row).
     */
    public static void applyEdit(CompoundTag scheduleTag, int entryIndex, String target,
                                 int column, int row, String key, String stringValue,
                                 Integer intValue) throws EditException {
        ListTag entryList = scheduleTag.getList("Entries", Tag.TAG_COMPOUND);
        if (entryIndex < 0 || entryIndex >= entryList.size())
            throw new EditException("bad_entry");
        CompoundTag entryTag = entryList.getCompound(entryIndex);
        CompoundTag node;
        if ("instruction".equals(target)) {
            node = entryTag.getCompound("Instruction");
        } else if ("condition".equals(target)) {
            ListTag columnList = entryTag.getList("Conditions", Tag.TAG_LIST);
            if (column < 0 || column >= columnList.size())
                throw new EditException("bad_condition");
            ListTag conditionList = (ListTag) columnList.get(column);
            if (row < 0 || row >= conditionList.size())
                throw new EditException("bad_condition");
            node = conditionList.getCompound(row);
        } else {
            throw new EditException("bad_target");
        }

        String id = node.getString("Id");
        Field field = EDITABLE.getOrDefault(id, List.of()).stream()
                .filter(candidate -> candidate.key().equals(key)).findFirst().orElse(null);
        if (field == null)
            throw new EditException("key_not_editable");

        CompoundTag data = node.getCompound("Data");
        if (field.string()) {
            if (stringValue == null || stringValue.length() > field.max())
                throw new EditException("bad_value");
            data.putString(key, stringValue);
        } else {
            if (intValue == null || intValue < field.min() || intValue > field.max())
                throw new EditException("bad_value");
            data.putInt(key, intValue);
        }
        node.put("Data", data);
    }

    public static int statusFor(PresetStore.PresetException e) {
        return switch (e.key) {
            case "not_found" -> 404;
            case "preset_full" -> 409;
            default -> 400;
        };
    }
}
