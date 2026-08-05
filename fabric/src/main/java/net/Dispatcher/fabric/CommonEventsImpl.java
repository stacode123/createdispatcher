package net.Dispatcher.fabric;

import net.Dispatcher.CommonEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public class CommonEventsImpl {
    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) ->
                CommonEvents.onPlayerJoin(listener.player)
        );
    }
}
