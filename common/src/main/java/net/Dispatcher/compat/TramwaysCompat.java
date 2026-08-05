package net.Dispatcher.compat;

import com.simibubi.create.content.trains.entity.Train;
import net.Dispatcher.Interfaces.ITramSignPoint;
import net.Dispatcher.DispatcherExpectPlatform;
import net.Dispatcher.DispatcherMod;
import net.Dispatcher.mixin.mixinaccesors.TramSignDataAccessor;
import net.createmod.catnip.data.Couple;
import net.minecraft.nbt.CompoundTag;
import purplecreate.tramways.content.signs.TramSignPoint;
import purplecreate.tramways.content.signs.demands.SignDemand;
import purplecreate.tramways.content.signs.demands.SpeedSignDemand;
import purplecreate.tramways.content.signs.demands.TemporaryEndSignDemand;
import purplecreate.tramways.content.signs.demands.TemporarySpeedSignDemand;

import java.util.*;

public class TramwaysCompat {
    private static final boolean tramwaysLoaded = DispatcherExpectPlatform.isModLoaded("tramways");


    public static boolean isLoaded() {
        return tramwaysLoaded;
    }

    public static boolean isTramSignPoint(Object obj) {
        if (!tramwaysLoaded) return false;
        try {
            return TramwaysCompatImpl.isTramSignPoint(obj);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isPrimary(Object tramSign, Object node) {
        if (!tramwaysLoaded) return false;
        try {
            return TramwaysCompatImpl.isPrimary(tramSign, node);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Speed limit in km/h a tram sign imposes on travel toward {@code towardNode},
     * or 0 when it carries none (or Tramways is absent).
     */
    public static double getSignCapKmh(Object tramSign, Object towardNode, double maxSpeedKmh) {
        if (!tramwaysLoaded) return 0;
        try {
            return TramwaysCompatImpl.getSignCapKmh(tramSign, towardNode, maxSpeedKmh);
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * Zone-aware sign classification for the graph translator: Tramways speed
     * signs SET a persistent per-train limit that lasts until another sign
     * changes or releases it — not just over the sign's own track segment.
     * @return -1 when the sign is speed-irrelevant in this direction (whistle,
     *         arrows...), 0 when it releases the zone (temporary-end sign),
     *         else the zone's limit in km/h.
     */
    public static double getSignZoneKmh(Object tramSign, Object towardNode, double maxSpeedKmh) {
        if (!tramwaysLoaded) return -1;
        try {
            return TramwaysCompatImpl.getSignZoneKmh(tramSign, towardNode, maxSpeedKmh);
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * Speed-relevant sign content for travel toward {@code towardNode}, as
     * {@code {temporaryThrottle, release, permanentThrottle}}: throttles are
     * fractions of the train's max speed (-1 when absent), release is 1 when
     * a temporary-end demand is present. Null when the sign carries nothing
     * speed-relevant in this direction (or Tramways is absent). The engine
     * replays these as per-train throttle mutations, so a zone survives
     * junctions exactly like Tramways' own persistent per-train limit.
     */
    public static double[] getSignEvents(Object tramSign, Object towardNode) {
        if (!tramwaysLoaded) return null;
        try {
            return TramwaysCompatImpl.getSignEvents(tramSign, towardNode);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Tramways' per-train line-speed clamp on sign throttles (1 when absent). */
    public static double getPrimaryLimit(Train train) {
        if (!tramwaysLoaded) return 1;
        try {
            return TramwaysCompatImpl.getPrimaryLimit(train);
        } catch (Throwable e) {
            return 1;
        }
    }

    /**
     * The pre-zone throttle Tramways stashed because the train is inside an
     * active temporary sign zone, or null (none / Tramways absent).
     */
    public static Double getStoredPermanent(Train train) {
        if (!tramwaysLoaded) return null;
        try {
            return TramwaysCompatImpl.getStoredPermanent(train);
        } catch (Throwable e) {
            return null;
        }
    }

    // Only loaded if Tramways exists
    private static class TramwaysCompatImpl {
        static boolean isTramSignPoint(Object obj) {
            return obj instanceof purplecreate.tramways.content.signs.TramSignPoint;
        }

        static boolean isPrimary(Object tramSign, Object node) {
            return ((purplecreate.tramways.content.signs.TramSignPoint)tramSign).isPrimary((com.simibubi.create.content.trains.graph.TrackNode)node);
        }

        @SuppressWarnings("unchecked")
        static double getSignCapKmh(Object tramSign, Object towardNode, double maxSpeedKmh) throws Exception {
            TramSignPoint sign = (TramSignPoint) tramSign;
            boolean primary = sign.isPrimary((com.simibubi.create.content.trains.graph.TrackNode) towardNode);
            Couple<Set<TramSignPoint.SignData>> sides;
            if (sign instanceof ITramSignPoint iSign) {
                sides = iSign.getSides();
            } else {
                java.lang.reflect.Field sidesField = TramSignPoint.class.getDeclaredField("sides");
                sidesField.setAccessible(true);
                sides = (Couple<Set<TramSignPoint.SignData>>) sidesField.get(sign);
            }
            if (sides == null || sides.get(primary) == null) return 0;
            double best = 0;
            for (TramSignPoint.SignData data : new HashSet<>(sides.get(primary))) {
                if (data == null) continue;
                CompoundTag tag;
                if (data instanceof TramSignDataAccessor accessor) {
                    tag = accessor.getDemandExtra();
                } else {
                    java.lang.reflect.Field extraField = TramSignPoint.SignData.class.getDeclaredField("demandExtra");
                    extraField.setAccessible(true);
                    tag = (CompoundTag) extraField.get(data);
                }
                if (tag == null || !tag.contains("Throttle")) continue;
                double kmh = tag.getInt("Throttle") / 100.0 * maxSpeedKmh;
                if (kmh > 0) best = best == 0 ? kmh : Math.min(best, kmh);
            }
            return best;
        }

        @SuppressWarnings("unchecked")
        static double getSignZoneKmh(Object tramSign, Object towardNode, double maxSpeedKmh) throws Exception {
            TramSignPoint sign = (TramSignPoint) tramSign;
            boolean primary = sign.isPrimary((com.simibubi.create.content.trains.graph.TrackNode) towardNode);
            Couple<Set<TramSignPoint.SignData>> sides;
            if (sign instanceof ITramSignPoint iSign) {
                sides = iSign.getSides();
            } else {
                java.lang.reflect.Field sidesField = TramSignPoint.class.getDeclaredField("sides");
                sidesField.setAccessible(true);
                sides = (Couple<Set<TramSignPoint.SignData>>) sidesField.get(sign);
            }
            if (sides == null || sides.get(primary) == null) return -1;
            double best = -1;
            boolean release = false;
            for (TramSignPoint.SignData data : new HashSet<>(sides.get(primary))) {
                if (data == null) continue;
                SignDemand demand;
                CompoundTag tag;
                if (data instanceof TramSignDataAccessor accessor) {
                    demand = accessor.getDemand();
                    tag = accessor.getDemandExtra();
                } else {
                    java.lang.reflect.Field demandField = TramSignPoint.SignData.class.getDeclaredField("demand");
                    demandField.setAccessible(true);
                    demand = (SignDemand) demandField.get(data);
                    java.lang.reflect.Field extraField = TramSignPoint.SignData.class.getDeclaredField("demandExtra");
                    extraField.setAccessible(true);
                    tag = (CompoundTag) extraField.get(data);
                }
                if (demand instanceof TemporaryEndSignDemand) {
                    release = true;
                    continue;
                }
                if (tag == null || !tag.contains("Throttle")) continue;
                double kmh = tag.getInt("Throttle") / 100.0 * maxSpeedKmh;
                if (kmh > 0) best = best < 0 ? kmh : Math.min(best, kmh);
            }
            if (best > 0) return best;
            return release ? 0 : -1;
        }

        @SuppressWarnings("unchecked")
        static double[] getSignEvents(Object tramSign, Object towardNode) throws Exception {
            TramSignPoint sign = (TramSignPoint) tramSign;
            boolean primary = sign.isPrimary((com.simibubi.create.content.trains.graph.TrackNode) towardNode);
            Couple<Set<TramSignPoint.SignData>> sides;
            if (sign instanceof ITramSignPoint iSign) {
                sides = iSign.getSides();
            } else {
                java.lang.reflect.Field sidesField = TramSignPoint.class.getDeclaredField("sides");
                sidesField.setAccessible(true);
                sides = (Couple<Set<TramSignPoint.SignData>>) sidesField.get(sign);
            }
            if (sides == null || sides.get(primary) == null) return null;
            double temporary = -1, permanent = -1;
            boolean release = false;
            for (TramSignPoint.SignData data : new HashSet<>(sides.get(primary))) {
                if (data == null) continue;
                SignDemand demand;
                CompoundTag tag;
                if (data instanceof TramSignDataAccessor accessor) {
                    demand = accessor.getDemand();
                    tag = accessor.getDemandExtra();
                } else {
                    java.lang.reflect.Field demandField = TramSignPoint.SignData.class.getDeclaredField("demand");
                    demandField.setAccessible(true);
                    demand = (SignDemand) demandField.get(data);
                    java.lang.reflect.Field extraField = TramSignPoint.SignData.class.getDeclaredField("demandExtra");
                    extraField.setAccessible(true);
                    tag = (CompoundTag) extraField.get(data);
                }
                if (demand instanceof TemporaryEndSignDemand) {
                    release = true;
                    continue;
                }
                if (!(demand instanceof SpeedSignDemand)) continue;
                if (tag == null || !tag.contains("Throttle")) continue;
                double fraction = tag.getInt("Throttle") / 100.0;
                if (fraction <= 0) continue;
                // TemporarySpeedSignDemand extends SpeedSignDemand — test it first.
                if (demand instanceof TemporarySpeedSignDemand)
                    temporary = temporary < 0 ? fraction : Math.min(temporary, fraction);
                else
                    permanent = permanent < 0 ? fraction : Math.min(permanent, fraction);
            }
            if (temporary < 0 && permanent < 0 && !release) return null;
            return new double[] { temporary, release ? 1 : 0, permanent };
        }

        static double getPrimaryLimit(Train train) {
            return train instanceof purplecreate.tramways.mixinInterfaces.ITram tram
                    ? tram.tramways$getPrimaryLimit() : 1;
        }

        private static java.lang.reflect.Field storedPermanentField;

        static Double getStoredPermanent(Train train) throws Exception {
            if (storedPermanentField == null) {
                java.lang.reflect.Field field = train.getClass().getDeclaredField("tramways$storedPermanent");
                field.setAccessible(true);
                storedPermanentField = field;
            }
            return (Double) storedPermanentField.get(train);
        }
    }
}