package net.Dispatcher.foundation.network;

import net.Dispatcher.content.gui.PresetLibraryOpener;
import net.Dispatcher.foundation.util.S2CPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The preset library listing (metadata only — schedules never travel here).
 * Handled through the client-only {@link PresetLibraryOpener} holder; packet
 * classes must not reference Screen types (dedicated-server rule).
 */
public class PresetListPacket implements S2CPacket {

    public record Row(UUID id, String name, String source, long updatedMs, int entries) {}

    private final List<Row> rows;

    public PresetListPacket(List<Row> rows) {
        this.rows = rows;
    }

    public static PresetListPacket read(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Row> rows = new ArrayList<>(Math.min(count, 200));
        for (int i = 0; i < count && i < 200; i++)
            rows.add(new Row(buf.readUUID(), buf.readUtf(64), buf.readUtf(80),
                    buf.readLong(), buf.readVarInt()));
        return new PresetListPacket(rows);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(rows.size());
        for (Row row : rows) {
            buf.writeUUID(row.id());
            buf.writeUtf(row.name(), 64);
            buf.writeUtf(row.source(), 80);
            buf.writeLong(row.updatedMs());
            buf.writeVarInt(row.entries());
        }
    }

    @Override
    public void handle(Minecraft mc) {
        PresetLibraryOpener.deliver(rows);
    }
}
