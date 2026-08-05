package net.Dispatcher.foundation.network;

import net.Dispatcher.content.trains.schedule.AdvancedScheduleItem;
import net.Dispatcher.content.trains.schedule.presets.Preset;
import net.Dispatcher.content.trains.schedule.presets.PresetStore;
import net.Dispatcher.foundation.util.C2SPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Writes a stored preset's schedule onto the held Advanced Schedule item. */
public class PresetDownloadPacket implements C2SPacket {

    private final UUID presetId;

    public PresetDownloadPacket(UUID presetId) {
        this.presetId = presetId;
    }

    public static PresetDownloadPacket read(FriendlyByteBuf buf) {
        return new PresetDownloadPacket(buf.readUUID());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(presetId);
    }

    @Override
    public void handle(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof AdvancedScheduleItem))
            return;
        PresetStore store = PresetStore.of(player.server);
        Preset preset = store.get(presetId);
        CompoundTag schedule = preset == null ? null : store.scheduleTag(preset);
        if (schedule == null) {
            player.displayClientMessage(
                    Component.translatable("dispatcher.preset.error.not_found"), false);
            return;
        }
        held.getOrCreateTag().put("Schedule", schedule);
        player.getCooldowns().addCooldown(held.getItem(), 5);
        player.displayClientMessage(
                Component.translatable("dispatcher.preset.loaded", preset.name()), false);
    }
}
