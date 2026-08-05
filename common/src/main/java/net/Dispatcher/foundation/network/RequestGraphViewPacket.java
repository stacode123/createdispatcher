package net.Dispatcher.foundation.network;

import net.Dispatcher.content.graph.v2.GraphViewService;
import net.Dispatcher.foundation.util.C2SPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** Asks the server for the rail network map around a track position. */
public record RequestGraphViewPacket(BlockPos pos) implements C2SPacket {

    public static RequestGraphViewPacket read(FriendlyByteBuf buf) {
        return new RequestGraphViewPacket(buf.readBlockPos());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
    }

    @Override
    public void handle(ServerPlayer player) {
        GraphViewService.request(player, pos);
    }
}
