package net.Dispatcher.foundation.network;

import net.Dispatcher.DNetworking;
import net.Dispatcher.content.trains.schedule.presets.Preset;
import net.Dispatcher.content.trains.schedule.presets.PresetStore;
import net.Dispatcher.foundation.util.C2SPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/** Asks the server for the preset library listing; answered with a {@link PresetListPacket}. */
public class PresetListRequestPacket implements C2SPacket {

    /** In-game list cap — the web UI has no such limit. */
    private static final int MAX_ROWS = 200;

    public PresetListRequestPacket() {}

    public static PresetListRequestPacket read(FriendlyByteBuf buf) {
        return new PresetListRequestPacket();
    }

    @Override
    public void write(FriendlyByteBuf buf) {}

    @Override
    public void handle(ServerPlayer player) {
        sendList(player);
    }

    /** Builds and sends the current listing; also used to refresh after uploads. */
    public static void sendList(ServerPlayer player) {
        List<PresetListPacket.Row> rows = new ArrayList<>();
        for (Preset preset : PresetStore.of(player.server).list()) {
            if (rows.size() >= MAX_ROWS)
                break;
            // Folders ride along inside the display name — the in-game list is flat, but
            // two presets named alike in different folders must not look identical. The
            // wire field caps at 64 chars, so a deep path loses its head, not its name.
            String label = preset.displayName();
            if (label.length() > 64)
                label = "…" + label.substring(label.length() - 63);
            rows.add(new PresetListPacket.Row(preset.id(), label, preset.source(),
                    preset.updatedMs(), preset.entries()));
        }
        DNetworking.sendToPlayer(new PresetListPacket(rows), player);
    }
}
