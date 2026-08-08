package net.Dispatcher.content.simulator;

import de.mrjulsen.mcdragonlib.util.time.DLTime;
import de.mrjulsen.mcdragonlib.util.time.ITimeSystem;
import net.Dispatcher.DispatcherMod;
import net.Dispatcher.content.simulator.core.SimClock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link SimClock} from a level's day time. {@code doDaylightCycle}
 * off freezes the clock outright, same as before. Otherwise the rate comes
 * from DragonLib's resolved {@link ITimeSystem} — vanilla (flat rate 1)
 * unless a datapack or registered compat describes a variable-speed day, e.g.
 * a mod like Better Days configured with day/night speeds. DragonLib has no
 * notion of the daylight gamerule itself, so that check always comes first.
 */
public final class DayTimeClocks {

    private DayTimeClocks() {}

    public static SimClock resolve(ServerLevel level, long startDayTime) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT))
            return new SimClock(startDayTime, 0);

        try {
            List<SimClock.RateZone> zones = toZones(DLTime.defaultTimeSystem());
            return zones.size() == 1 && zones.get(0).dayTimeRate() == 1
                    ? new SimClock(startDayTime, 1)
                    : new SimClock(startDayTime, 1, zones);
        } catch (RuntimeException e) {
            DispatcherMod.LOGGER.warn("DragonLib time system rejected, falling back to vanilla rate", e);
            return new SimClock(startDayTime, 1);
        }
    }

    /** The rate in effect right now — for the live view's clock label, not simulation. */
    public static double currentRate(ServerLevel level) {
        return resolve(level, level.getDayTime()).rateAt(0);
    }

    private static List<SimClock.RateZone> toZones(ITimeSystem system) {
        List<SimClock.RateZone> zones = new ArrayList<>();
        for (var zone : system.getTimeZones())
            zones.add(new SimClock.RateZone(zone.startTick(), zone.endTick(), zone.tps() / 20.0));
        return zones;
    }
}
