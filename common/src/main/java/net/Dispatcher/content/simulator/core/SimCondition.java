package net.Dispatcher.content.simulator.core;

/**
 * A deterministic wait condition. Each variant mirrors the exact tick logic
 * of its in-game counterpart (see the source class named on each record) so
 * simulated departure times match real ones.
 */
public sealed interface SimCondition {

    /**
     * One condition-slot tick, mirroring {@code ScheduleWaitCondition
     * .tickCompletion}. {@code elapsed} is the ticks this condition has been
     * the active one in its column (Create's context {@code Time}), and
     * {@code column} identifies that column — conditions race per column and
     * each keeps its own context tag, cleared on arrival and on completion.
     *
     * @return true when complete
     */
    boolean tick(SimClock clock, long tick, int column, int elapsed, TrainState train);

    /** Create's {@code ScheduledDelay}: complete after {@code ticks}. */
    record Delay(int ticks) implements SimCondition {
        @Override
        public boolean tick(SimClock clock, long tick, int column, int elapsed, TrainState train) {
            return elapsed >= ticks;
        }
    }

    /**
     * Create's {@code TimeOfDayCondition}: completes while
     * {@code dayTime % rotation} sits in the 40-tick window at the target.
     */
    record TimeOfDay(int hour, int minute, int rotation) implements SimCondition {
        @Override
        public boolean tick(SimClock clock, long tick, int column, int elapsed, TrainState train) {
            int dayTime = (int) (clock.dayTimeAt(tick) % rotation);
            int targetTicks = (int) ((((hour + 18) % 24) * 1000 + Math.ceil(minute / 60f * 1000)) % rotation);
            int diff = dayTime - targetTicks;
            return diff >= 0 && diff <= 40;
        }
    }

    /**
     * Realism's {@code TimeOfDayRealistic}: a timetable departure. Each stop
     * books one absolute departure tick — its slot — and the train leaves the
     * moment it is both ready and past that slot, so a train that pulls in
     * behind schedule finds its slot already gone and departs at once, and
     * keeps doing so at every following stop until the timetable catches up.
     *
     * <p>Slots are strictly increasing: the next one chains off the slot just
     * used, not off the arrival. That is what stops a loop calling twice at
     * the same station from departing on the spot — the second call books
     * tomorrow — while a genuinely late train still runs ahead.
     */
    record TimeOfDayRealistic(int hour, int minute) implements SimCondition {

        private static final long DAY = 24000L;
        /** Day time 0 is 06:00, so this shifts it to ticks since midnight. */
        private static final long MIDNIGHT_OFFSET = 6000L;
        /**
         * How far behind the timetable a train may be and still count as
         * merely late. Past this Realism re-timetables it onto the first slot
         * after its arrival rather than sprinting through cycles of backlog.
         */
        private static final long MAX_RECOVERY = 7L * DAY;

        @Override
        public boolean tick(SimClock clock, long tick, int column, int elapsed, TrainState train) {
            long slot = bookedSlot(clock, tick, column, train);
            if (tick < slot)
                return false;
            train.lastDepartureSlot = slot;
            return true;
        }

        /**
         * This stop's departure tick. Resolved once and kept in the column's
         * context (cleared on arrival and on completion), so every stop books
         * its own slot.
         */
        private long bookedSlot(SimClock clock, long tick, int column, TrainState train) {
            long stored = train.columnDepartAt[column];
            // A slot is never more than a day ahead; anything else is a
            // leftover from an edited schedule or a changed world time.
            if (stored != TrainState.UNBOOKED && stored - tick <= DAY)
                return stored;
            long slot = bookSlot(clock, tick, train);
            train.columnDepartAt[column] = slot;
            return slot;
        }

        private long bookSlot(SimClock clock, long tick, TrainState train) {
            long arrival = arrival(train, tick);
            long previous = train.lastDepartureSlot;
            // Chain onto the previous slot rather than onto the arrival, so
            // that being late only moves the train, never the timetable.
            if (previous != TrainState.UNBOOKED && previous <= arrival
                    && arrival - previous <= MAX_RECOVERY)
                return occurrenceFrom(clock, tick, previous, false);
            return occurrenceFrom(clock, tick, arrival, true);
        }

        /**
         * The tick the train started waiting here, falling back to now when
         * that is unknown — tick 0 is a real arrival here, so only the -1
         * "never arrived" marker counts as missing.
         */
        private long arrival(TrainState train, long tick) {
            long arrival = train.arrivalTick;
            if (arrival < 0 || arrival > tick || tick - arrival > MAX_RECOVERY)
                return tick;
            return arrival;
        }

        /**
         * First tick matching the configured time of day counted from
         * {@code from} — inclusive of {@code from} itself, or a full day
         * later when {@code inclusive} is false.
         */
        private long occurrenceFrom(SimClock clock, long tick, long from, boolean inclusive) {
            // Rewinding day time by the elapsed ticks is Realism's own way of
            // recovering the time of day back then, so a stopped or scaled
            // daylight cycle lands the projection where the real train lands.
            long timeOfDay = Math.floorMod(clock.dayTimeAt(tick) - (tick - from) + MIDNIGHT_OFFSET, DAY);
            long untilTarget = Math.floorMod(targetTimeOfDay() - timeOfDay, DAY);
            if (untilTarget == 0 && !inclusive)
                untilTarget = DAY;
            return from + untilTarget;
        }

        /** Configured departure as ticks since midnight, within a single day. */
        private long targetTimeOfDay() {
            long clampedHour = Math.max(0, Math.min(23, hour));
            long clampedMinute = Math.max(0, Math.min(59, minute));
            return clampedHour * 1000L + Math.round(clampedMinute * 1000d / 60d);
        }
    }

    /**
     * CRN's {@code DynamicDelayCondition}. Its in-game dwell shrinks by the
     * live delay CRN measured against its own predictions; a static
     * projection has no such baseline, so the deterministic model is the full
     * scheduled dwell (CRN's own behavior when its data is uninitialized).
     */
    record DynamicDelay(int ticks, int minTicks) implements SimCondition {
        @Override
        public boolean tick(SimClock clock, long tick, int column, int elapsed, TrainState train) {
            return elapsed >= ticks;
        }
    }

    /**
     * CRN's {@code TrainSeparationCondition}: completes its column instantly
     * but registers a departure gate — the train may only leave once the last
     * matching departure is at least {@code ticks} old (see
     * {@link SimEngine} departure handling).
     */
    record Separation(int ticks, TrainFilter filter, String stationFilter)
            implements SimCondition {
        @Override
        public boolean tick(SimClock clock, long tick, int column, int elapsed, TrainState train) {
            train.departureGates.add(this);
            return true;
        }
    }

    /**
     * CRN departure-history filters. Line and category identity come from
     * the schedule's travel-section instructions (compiled into each entry
     * as opaque tokens), matching CRN's {@code ScheduleSection} resolution.
     */
    enum TrainFilter {
        ANY, SAME_NAME, SAME_LINE, SAME_CATEGORY
    }
}
