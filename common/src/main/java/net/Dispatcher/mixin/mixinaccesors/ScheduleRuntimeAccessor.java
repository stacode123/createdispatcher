package net.Dispatcher.mixin.mixinaccesors;

import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ScheduleRuntime.class, remap = false)
public interface ScheduleRuntimeAccessor {
    /** Remaining failed-navigation retry cooldown — seeds the simulator so a mid-cooldown snapshot doesn't retry up to 40 ticks early. */
    @Accessor("cooldown")
    int getCooldown();
}
