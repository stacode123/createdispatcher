package net.Dispatcher.content.gui;

import net.Dispatcher.content.simulator.SimulationPayload;
import net.Dispatcher.content.trains.schedule.AdvancedScheduleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client-only holder for delivering simulation results. Packet classes must
 * not touch Screen types themselves — verifying such a method loads the
 * client-only class hierarchy, which crashes dedicated servers the moment
 * the packet class is registered (see {@link GraphMapOpener}).
 */
public class SimulationResultOpener {

    public static void open(SimulationPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (!payload.refused()) {
            // Remembered for the card-time overlay and the map's badges.
            SimulationClientData.lastResults = payload;
            SimulationClientData.lastScheduleHash = SimulationClientData.pendingScheduleHash;
        }
        if (mc.screen instanceof AdvancedScheduleScreen screen) {
            screen.simulationArrived(payload);
            return;
        }
        // Editor was closed in the meantime — drop a summary in chat.
        if (mc.player == null)
            return;
        if (payload.refused()) {
            SimulationPayload.Refusal refusal = payload.refusals.get(0);
            mc.player.displayClientMessage(
                    Component.translatable(refusal.translationKey(), refusal.detail()), false);
        } else {
            mc.player.displayClientMessage(
                    Component.translatable("dispatcher.sim.done_in_chat"), false);
        }
    }
}
