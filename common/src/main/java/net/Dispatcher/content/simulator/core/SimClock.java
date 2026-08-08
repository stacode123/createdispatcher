package net.Dispatcher.content.simulator.core;

import java.util.List;

/**
 * Maps engine ticks to world day time. {@code dayTimeRate} is how many
 * day-time ticks pass per real server tick — 1 in vanilla, 0 with
 * doDaylightCycle off, other values under time-speed mods. Time-of-day
 * conditions read day time; movement always runs in real ticks.
 *
 * <p>{@code zones} is the general form for mods whose rate varies by time of
 * day (e.g. a slower night): non-overlapping {@link RateZone} windows that
 * must exactly cover {@code [0, DAY)}. Empty means "use {@code dayTimeRate}
 * everywhere", which is also what an empty-zones clock reduces to, so the
 * flat-rate constructor is exact, not an approximation of the zoned one.
 */
public record SimClock(long startDayTime, double dayTimeRate, List<RateZone> zones) {

    private static final long DAY = 24000L;

    public SimClock {
        zones = List.copyOf(zones);
        if (!zones.isEmpty())
            validateCoverage(zones);
    }

    public SimClock(long startDayTime, double dayTimeRate) {
        this(startDayTime, dayTimeRate, List.of());
    }

    /** A {@code [startTick, endTick)} window of day time with its own rate. */
    public record RateZone(long startTick, long endTick, double dayTimeRate) {}

    public long dayTimeAt(long tick) {
        if (zones.isEmpty() || tick <= 0)
            return startDayTime + Math.round(tick * dayTimeRate);
        return startDayTime + advance(Math.floorMod(startDayTime, DAY), tick);
    }

    /** The rate in effect {@code tick} real ticks from now — for display, not simulation. */
    public double rateAt(long tick) {
        return zones.isEmpty() ? dayTimeRate : zoneAt(Math.floorMod(dayTimeAt(tick), DAY)).dayTimeRate();
    }

    /**
     * Day-time ticks elapsed after {@code realTicks} starting at time-of-day
     * {@code dayTick}, integrating through the zone rates in effect along the
     * way. A zone with rate 0 freezes progress the moment it is entered — real
     * time keeps passing but day time does not, mirroring a mod that stops the
     * clock overnight.
     */
    private long advance(long dayTick, long realTicks) {
        double lapRealTicks = 0;
        for (RateZone zone : zones) {
            if (zone.dayTimeRate() <= 0) {
                lapRealTicks = Double.POSITIVE_INFINITY;
                break;
            }
            lapRealTicks += (zone.endTick() - zone.startTick()) / zone.dayTimeRate();
        }

        double remaining = realTicks;
        double elapsed = 0;
        if (Double.isFinite(lapRealTicks) && lapRealTicks > 0) {
            long fullLaps = (long) (remaining / lapRealTicks);
            elapsed += fullLaps * (double) DAY;
            remaining -= fullLaps * lapRealTicks;
        }

        long position = dayTick;
        for (int step = 0; step <= zones.size() && remaining > 1e-9; step++) {
            RateZone zone = zoneAt(position);
            if (zone.dayTimeRate() <= 0)
                break; // frozen here; the rest of the budget buys no day time
            double realTicksToZoneEnd = (zone.endTick() - position) / zone.dayTimeRate();
            if (remaining < realTicksToZoneEnd) {
                elapsed += remaining * zone.dayTimeRate();
                remaining = 0;
            } else {
                elapsed += zone.endTick() - position;
                remaining -= realTicksToZoneEnd;
                position = zone.endTick() % DAY;
            }
        }
        return Math.round(elapsed);
    }

    private RateZone zoneAt(long dayTick) {
        for (RateZone zone : zones)
            if (dayTick >= zone.startTick() && dayTick < zone.endTick())
                return zone;
        throw new IllegalStateException("day time zones do not cover tick " + dayTick);
    }

    private static void validateCoverage(List<RateZone> zones) {
        List<RateZone> sorted = zones.stream().sorted(java.util.Comparator.comparingLong(RateZone::startTick)).toList();
        long cursor = 0;
        for (RateZone zone : sorted) {
            if (zone.startTick() != cursor || zone.endTick() <= zone.startTick())
                throw new IllegalArgumentException("day time zones must partition [0, " + DAY + ") without gaps or overlaps: " + zones);
            cursor = zone.endTick();
        }
        if (cursor != DAY)
            throw new IllegalArgumentException("day time zones must cover [0, " + DAY + "): " + zones);
    }
}
