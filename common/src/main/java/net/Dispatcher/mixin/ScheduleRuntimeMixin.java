package net.Dispatcher.mixin;

import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import net.Dispatcher.Interfaces.IAdvancedScheduleRuntime;
import net.Dispatcher.foundation.ServerOnly;
import net.Dispatcher.foundation.util.AllDispatcherItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Remembers that a train's schedule came from an Advanced Schedule item, so the conductor hands
 * that item back instead of Create's vanilla one.
 *
 * <p>Deliberately minimal: Create Realism mixes into the same class for its own departure/arrival
 * bookkeeping, so this keeps a distinct {@code dispatcher$} field name and touches only
 * {@code setSchedule}, {@code returnSchedule} and the NBT round-trip. Deploy ordering depends on
 * the {@code setSchedule} TAIL reset — see {@code web.deploy.DeployService}.
 */
@Mixin(value = ScheduleRuntime.class, remap = false)
public abstract class ScheduleRuntimeMixin implements IAdvancedScheduleRuntime {

    @Unique
    private boolean dispatcher$advancedSchedule = false;

    @Override
    public boolean isAdvancedSchedule() {
        return dispatcher$advancedSchedule;
    }

    @Override
    public void setAdvancedSchedule(boolean advancedSchedule) {
        this.dispatcher$advancedSchedule = advancedSchedule;
    }

    @Inject(method = "setSchedule", at = @At("TAIL"))
    public void dispatcher$clearMarkerOnNewSchedule(Schedule schedule, boolean auto, CallbackInfo ci) {
        dispatcher$advancedSchedule = false;
    }

    @Inject(method = "returnSchedule", at = @At("RETURN"), cancellable = true)
    public void dispatcher$returnAdvancedSchedule(CallbackInfoReturnable<ItemStack> cir) {
        if (!dispatcher$advancedSchedule)
            return;
        dispatcher$advancedSchedule = false;
        if (ServerOnly.enabled())
            // No item was registered, so hand back Create's. The marker can still be set here:
            // DeployService sets it directly, without an item ever existing.
            return;
        ItemStack vanilla = cir.getReturnValue();
        if (vanilla.isEmpty())
            return;
        ItemStack advanced = AllDispatcherItems.ADVANCED_SCHEDULE.asStack();
        advanced.setTag(vanilla.getTag());
        cir.setReturnValue(advanced);
    }

    @Inject(method = "write", at = @At("RETURN"), cancellable = true)
    public void dispatcher$write(CallbackInfoReturnable<CompoundTag> cir) {
        CompoundTag tag = cir.getReturnValue();
        tag.putBoolean("dispatcherAdvancedSchedule", dispatcher$advancedSchedule);
        cir.setReturnValue(tag);
    }

    @Inject(method = "read", at = @At("TAIL"))
    public void dispatcher$read(CompoundTag tag, CallbackInfo ci) {
        // "advancedSchedule" is the key Create Realism's fork wrote before the split — read it as a
        // fallback so worlds migrating from that build keep their conductor round-trip.
        dispatcher$advancedSchedule = tag.getBoolean("dispatcherAdvancedSchedule")
                || tag.getBoolean("advancedSchedule");
    }
}
