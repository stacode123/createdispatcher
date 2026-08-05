package net.Dispatcher.foundation.network;

import net.Dispatcher.content.trains.schedule.AdvancedScheduleItem;
import net.Dispatcher.content.trains.schedule.presets.PresetStore;
import net.Dispatcher.foundation.util.C2SPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Saves the held Advanced Schedule item's schedule into the preset library. */
public class PresetUploadPacket implements C2SPacket {

    private final String name;

    public PresetUploadPacket(String name) {
        this.name = name;
    }

    public static PresetUploadPacket read(FriendlyByteBuf buf) {
        return new PresetUploadPacket(buf.readUtf(64));
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(name, 64);
    }

    @Override
    public void handle(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof AdvancedScheduleItem))
            return;
        if (held.getTag() == null || !held.getTag().contains("Schedule")) {
            player.displayClientMessage(Component.translatable("dispatcher.preset.no_schedule"), false);
            return;
        }
        // In-game saves have no folder picker, so they file themselves: every player
        // gets a folder named after them. Player names are 1-16 characters with no
        // control characters, so they always survive PresetStore.normalizeFolder.
        String owner = player.getGameProfile().getName();
        try {
            var preset = PresetStore.of(player.server).create(name, owner, "item:" + owner,
                    held.getTag().getCompound("Schedule"));
            player.displayClientMessage(
                    Component.translatable("dispatcher.preset.saved", preset.displayName()), false);
        } catch (PresetStore.PresetException e) {
            player.displayClientMessage(
                    Component.translatable("dispatcher.preset.error." + e.key), false);
            return;
        }
        // Refresh the open library window with the new entry.
        PresetListRequestPacket.sendList(player);
    }
}
