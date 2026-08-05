package net.Dispatcher.content.trains.schedule.presets;

import java.util.UUID;

/**
 * One stored schedule preset. The schedule itself is kept as SNBT — the
 * sanitized output of a {@code Schedule.fromTag → write()} round trip, so a
 * stored preset is always loadable by Create. Immutable; every mutation in
 * {@link PresetStore} replaces the record.
 *
 * @param folder  organizing path, {@code ""} for unfiled. Slash-separated
 *                ({@code "Intercity/High speed"}); purely a display grouping,
 *                normalized by {@link PresetStore#normalizeFolder}.
 * @param source who created it: {@code item:<player>}, {@code train:<name>}
 *               or {@code web:<user>} — display-only, never parsed.
 * @param entries schedule entry count, cached for list displays.
 */
public record Preset(UUID id, String name, String folder, String source, long createdMs,
                     long updatedMs, int entries, String scheduleSnbt) {

    /** Path shown to users: {@code folder/name}, or just the name when unfiled. */
    public String displayName() {
        return folder.isEmpty() ? name : folder + "/" + name;
    }
}
