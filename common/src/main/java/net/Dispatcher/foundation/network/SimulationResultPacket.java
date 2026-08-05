package net.Dispatcher.foundation.network;

import net.Dispatcher.content.gui.SimulationResultOpener;
import net.Dispatcher.content.simulator.SimulationPayload;
import net.Dispatcher.foundation.util.S2CPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;

/** Delivers a finished simulation (or the refusal reasons) to the requester. */
public record SimulationResultPacket(SimulationPayload payload) implements S2CPacket {

    public static SimulationResultPacket read(FriendlyByteBuf buf) {
        return new SimulationResultPacket(SimulationPayload.read(buf));
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        payload.write(buf);
    }

    @Override
    public void handle(Minecraft mc) {
        // Indirection keeps client-only Screen classes out of this class's
        // verification; touching them here crashes dedicated servers.
        SimulationResultOpener.open(payload);
    }
}
