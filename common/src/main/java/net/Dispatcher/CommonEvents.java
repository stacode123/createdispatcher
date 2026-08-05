package net.Dispatcher;

import net.minecraft.server.level.ServerPlayer;

public class CommonEvents {
    public static void onPlayerJoin(ServerPlayer player) {
        DNetworking.onPlayerJoin(player);
    }
}
