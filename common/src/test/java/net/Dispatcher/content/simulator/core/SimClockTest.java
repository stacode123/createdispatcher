package net.Dispatcher.content.simulator.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The zoned form of {@link SimClock}, used for mods (e.g. Better Days) whose
 * day-time rate varies by time of day rather than staying constant.
 */
class SimClockTest {

    /** A day/night split like Better Days' RATIO mode: half speed after 12500, back to full at 23500. */
    private static final List<SimClock.RateZone> DAY_NIGHT = List.of(
            new SimClock.RateZone(0, 12500, 1.0),
            new SimClock.RateZone(12500, 23500, 0.5),
            new SimClock.RateZone(23500, 24000, 1.0));

    @Test
    void aSingleFullDayZoneAtRateOneMatchesTheFlatRateExactly() {
        SimClock zoned = new SimClock(500, 1, List.of(new SimClock.RateZone(0, 24000, 1.0)));
        SimClock flat = new SimClock(500, 1);

        assertEquals(flat.dayTimeAt(1000), zoned.dayTimeAt(1000));
    }

    @Test
    void slowsDownInsideANightZone() {
        SimClock clock = new SimClock(12500, 1, DAY_NIGHT);

        // 1000 real ticks at half speed is 500 day-time ticks.
        assertEquals(13000, clock.dayTimeAt(1000));
    }

    @Test
    void landsExactlyOnTheZoneBoundary() {
        SimClock clock = new SimClock(12500, 1, DAY_NIGHT);

        // The night zone is 11000 day-time ticks wide at half speed: 22000 real ticks to cross it.
        assertEquals(23500, clock.dayTimeAt(22000));
    }

    @Test
    void aFullLapCostsMoreRealTicksThanADayWhenNightIsSlower() {
        SimClock clock = new SimClock(0, 1, DAY_NIGHT);

        // day 12500 + night 22000 + day 500 = 35000 real ticks for one day-time lap.
        assertEquals(24000, clock.dayTimeAt(35000));
        assertEquals(48000, clock.dayTimeAt(70000));
        assertEquals(24500, clock.dayTimeAt(35500), "500 real ticks left over after the lap, back in a day zone");
    }

    @Test
    void aFrozenZoneStopsDayTimeButNotRealTime() {
        List<SimClock.RateZone> dayThenFrozenNight = List.of(
                new SimClock.RateZone(0, 12500, 1.0),
                new SimClock.RateZone(12500, 24000, 0.0));
        SimClock clock = new SimClock(6000, 1, dayThenFrozenNight);

        assertEquals(12500, clock.dayTimeAt(10_000));
        assertEquals(12500, clock.dayTimeAt(10_000_000), "no amount of real time escapes a frozen zone");
    }

    @Test
    void rateAtReportsTheZoneInEffectAtTheProjectedTick() {
        SimClock clock = new SimClock(12500, 1, DAY_NIGHT);

        assertEquals(0.5, clock.rateAt(0));
        assertEquals(1.0, clock.rateAt(22000), "exactly at the boundary, already in the next zone");
    }

    @Test
    void rejectsZonesWithAGap() {
        assertThrows(IllegalArgumentException.class, () -> new SimClock(0, 1,
                List.of(new SimClock.RateZone(0, 12000, 1.0), new SimClock.RateZone(13000, 24000, 1.0))));
    }

    @Test
    void rejectsOverlappingZones() {
        assertThrows(IllegalArgumentException.class, () -> new SimClock(0, 1,
                List.of(new SimClock.RateZone(0, 15000, 1.0), new SimClock.RateZone(10000, 24000, 1.0))));
    }
}
