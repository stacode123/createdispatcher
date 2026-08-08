package net.Dispatcher.content.simulator.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Create Realism's {@code time_of_day_realistic}: every stop books an
 * absolute departure slot, slots chain forward off the last one used, and a
 * slot already in the past releases the train at once.
 *
 * <p>Departures land one tick after the slot: Create notices a completed
 * condition at the top of the next {@code tickConditions}, not on the tick
 * that completed it.
 */
class TimeOfDayRealisticTest {

    /** 07:00 start, 08:00 departure: one hour of day time is 1000 ticks. */
    @Test
    void departsAtTheFirstOccurrenceAfterArrival() {
        SimResult result = run(1000, LineFixture.program(false,
                LineFixture.destination("A", new SimCondition.TimeOfDayRealistic(8, 0)),
                LineFixture.destination("C", new SimCondition.Delay(20))), null);

        assertEquals(1001, result.trains.get(0).visits.get(0).departureTick());
    }

    /**
     * The second stop asks for the same time of day the train just left on.
     * Slots strictly increase, so it books tomorrow rather than reading its
     * own departure as "already past 08:00" and leaving on the spot.
     */
    @Test
    void sameTimeOfDayAgainBooksTheNextDay() {
        SimResult result = run(2000, LineFixture.program(false,
                LineFixture.destination("A", new SimCondition.TimeOfDayRealistic(8, 0)),
                LineFixture.destination("C", new SimCondition.TimeOfDayRealistic(8, 0))), null);

        List<SimResult.StationVisit> visits = result.trains.get(0).visits;
        assertEquals(1, visits.get(0).departureTick(), "08:00 exactly at tick 0");
        assertEquals("C", visits.get(1).stationName());
        assertTrue(visits.get(1).arrivalTick() < 24000,
                () -> "should reach C the same day, arrived " + visits.get(1).arrivalTick());
        assertEquals(24001, visits.get(1).departureTick(), "next day's 08:00");
    }

    /**
     * A train snapshotted mid-dwell keeps the slot Realism already booked for
     * it — here one that has come and gone, so it leaves immediately instead
     * of waiting out a whole day for the next 08:00.
     */
    @Test
    void slotBookedBeforeTheSnapshotStillReleasesTheTrain() {
        SimProgram program = LineFixture.program(false,
                LineFixture.destination("A", new SimCondition.TimeOfDayRealistic(8, 0)),
                LineFixture.destination("C", new SimCondition.Delay(20)));

        // 12:00 start: the next 08:00 is 20 hours out, but the booked slot
        // (in day time, like the live NBT seed) fell 500 ticks before the
        // snapshot's day time of 6000.
        SimResult unseeded = run(6000, program, null);
        SimResult seeded = run(6000, program, new long[] { 5500 });

        assertEquals(20001, unseeded.trains.get(0).visits.get(0).departureTick());
        assertEquals(1, seeded.trains.get(0).visits.get(0).departureTick());
    }

    /**
     * A→C along a straight line, the train starting mid-dwell at A.
     * {@code startDayTime} sets the clock; {@code departAt} seeds the booked
     * slots the way {@code NetworkSnapshotter} does from a live train.
     */
    private static SimResult run(long startDayTime, SimProgram program, long[] departAt) {
        LineFixture line = new LineFixture().nodes(0, 1000);
        line.station("A", 10);
        line.station("C", 900);
        SimGraph graph = line.build();

        SimTrainSpec train = LineFixture.train("t1", line, 10, program);
        train.startWaiting = true;
        if (departAt != null) {
            train.startColumnProgress = new int[] { 0 };
            train.startColumnElapsed = new int[] { 0 };
            train.startColumnDepartAt = departAt;
        }
        return new SimEngine(graph, List.of(train), new SimClock(startDayTime, 1), 30000, 20, 0)
                .run();
    }
}
