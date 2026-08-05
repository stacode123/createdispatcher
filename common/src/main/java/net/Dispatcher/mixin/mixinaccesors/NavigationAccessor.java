package net.Dispatcher.mixin.mixinaccesors;

import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.graph.TrackNode;
import net.createmod.catnip.data.Couple;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(value = Navigation.class, remap = false)
public interface NavigationAccessor {
    /** The micro-edge path Create's router actually chose — the web detour analyzer maps it onto the collapsed graph. */
    @Accessor("currentPath")
    List<Couple<TrackNode>> getCurrentPath();
}
