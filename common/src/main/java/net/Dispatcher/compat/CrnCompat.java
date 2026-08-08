package net.Dispatcher.compat;

import de.mrjulsen.crn.data.StationTag;
import de.mrjulsen.crn.data.storage.GlobalSettings;
import net.Dispatcher.DispatcherExpectPlatform;
import net.Dispatcher.DispatcherMod;

import java.util.*;

/**
 * Create Railways Navigator integration, guarded like {@link TramwaysCompat}:
 * CRN classes are compile-only, everything is reached through methods that
 * check the mod is loaded and swallow linkage errors.
 */
public class CrnCompat {

    private static final boolean crnLoaded =
            DispatcherExpectPlatform.isModLoaded("createrailwaysnavigator");

    /**
     * Station name → tag display name from CRN's server-side station tags —
     * the player-curated grouping of platforms ("Radom 1", "Radom 2") under
     * one station ("Radom"). Stations in several tags keep the first tag in
     * name order. Empty when CRN is absent or has no data yet.
     */
    public static Map<String, String> stationTagGroups() {
        if (!crnLoaded)
            return Map.of();
        try {
            return stationTagGroupsImpl();
        } catch (Throwable t) {
            DispatcherMod.LOGGER.error("Failed to read CRN station tags", t);
            return Map.of();
        }
    }

    /**
     * Raw station names on CRN's station blacklist (exact-match, the same
     * semantics as CRN's own {@code isStationBlacklisted}). Empty when CRN
     * is absent or has no data yet.
     */
    public static Set<String> blacklistedStations() {
        if (!crnLoaded)
            return Set.of();
        try {
            return blacklistedStationsImpl();
        } catch (Throwable t) {
            DispatcherMod.LOGGER.error("Failed to read CRN station blacklist", t);
            return Set.of();
        }
    }

    private static Set<String> blacklistedStationsImpl() {
        if (!GlobalSettings.hasInstance())
            return Set.of();
        return new HashSet<>(GlobalSettings.getInstance().getAllBlacklistedStations());
    }

    /** Train category id → display name, from CRN's server-side settings. */
    public static Map<String, String> trainCategoryNames() {
        if (!crnLoaded)
            return Map.of();
        try {
            return trainCategoryNamesImpl();
        } catch (Throwable t) {
            DispatcherMod.LOGGER.error("Failed to read CRN train categories", t);
            return Map.of();
        }
    }

    private static Map<String, String> trainCategoryNamesImpl() {
        if (!GlobalSettings.hasInstance())
            return Map.of();
        Map<String, String> names = new HashMap<>();
        for (de.mrjulsen.crn.data.TrainCategory category
                : GlobalSettings.getInstance().getAllTrainCategories())
            names.put(category.getId().toString(), category.getCategoryName());
        return names;
    }

    /** Train line id → display name, from CRN's server-side settings. */
    public static Map<String, String> trainLineNames() {
        if (!crnLoaded)
            return Map.of();
        try {
            return trainLineNamesImpl();
        } catch (Throwable t) {
            DispatcherMod.LOGGER.error("Failed to read CRN train lines", t);
            return Map.of();
        }
    }

    private static Map<String, String> trainLineNamesImpl() {
        if (!GlobalSettings.hasInstance())
            return Map.of();
        Map<String, String> names = new HashMap<>();
        for (de.mrjulsen.crn.data.TrainLine line
                : GlobalSettings.getInstance().getAllTrainLines())
            names.put(line.getId().toString(), line.getLineName());
        return names;
    }

    /**
     * One station's CRN departure history, absolute overworld game ticks
     * (0 = never). Line/category keys are their UUIDs as strings — the same
     * tokens {@code ScheduleCompiler} derives from travel-section
     * instructions, so the sim's separation gates compare them directly.
     */
    public record DepartureSeed(String station, long lastAny, Map<String, Long> byLine,
                                Map<String, Long> byCategory, Map<String, Long> byName) {}

    /**
     * SERVER THREAD: CRN's live per-station departure history. Without this,
     * a simulation starts with empty history and every train_separation gate
     * passes instantly — collapsing the multi-minute headway dwells real
     * schedules are built around. Empty when CRN is absent.
     */
    public static List<DepartureSeed> departureSeeds() {
        if (!crnLoaded)
            return List.of();
        try {
            return departureSeedsImpl();
        } catch (Throwable t) {
            DispatcherMod.LOGGER.error("Failed to read CRN departure history", t);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<DepartureSeed> departureSeedsImpl() throws Exception {
        java.lang.reflect.Field field = de.mrjulsen.crn.data.train.DepartureHistory.class
                .getDeclaredField("departuresByStation");
        field.setAccessible(true);
        Map<String, de.mrjulsen.crn.data.train.DepartureHistory.Data> byStation =
                (Map<String, de.mrjulsen.crn.data.train.DepartureHistory.Data>) field.get(null);
        List<DepartureSeed> seeds = new ArrayList<>();
        for (Map.Entry<String, de.mrjulsen.crn.data.train.DepartureHistory.Data> entry
                : new HashMap<>(byStation).entrySet()) {
            de.mrjulsen.crn.data.train.DepartureHistory.Data data = entry.getValue();
            if (data == null)
                continue;
            Map<String, Long> byLine = new HashMap<>();
            data.getLastDeparturesByLine().forEach((line, tick) -> {
                if (line != null && tick != null)
                    byLine.put(line.getId().toString(), tick);
            });
            Map<String, Long> byCategory = new HashMap<>();
            data.getLastDeparturesByCategory().forEach((category, tick) -> {
                if (category != null && tick != null)
                    byCategory.put(category.getId().toString(), tick);
            });
            Map<String, Long> byName = new HashMap<>();
            data.getLastDeparturesByTrainName().forEach((name, tick) -> {
                if (name != null && tick != null)
                    byName.put(name, tick);
            });
            seeds.add(new DepartureSeed(entry.getKey(), data.getLastDepartureTime(),
                    byLine, byCategory, byName));
        }
        return seeds;
    }

    private static Map<String, String> stationTagGroupsImpl() {
        if (!GlobalSettings.hasInstance())
            return Map.of();
        List<StationTag> tags = new ArrayList<>(GlobalSettings.getInstance().getAllStationTags());
        tags.sort(Comparator.comparing(tag -> tag.getTagName().get()));
        Map<String, String> groups = new HashMap<>();
        for (StationTag tag : tags) {
            String name = tag.getTagName().get();
            if (name == null || name.isBlank())
                continue;
            for (String station : tag.getAllStationNames())
                groups.putIfAbsent(station, name);
        }
        return groups;
    }
}
