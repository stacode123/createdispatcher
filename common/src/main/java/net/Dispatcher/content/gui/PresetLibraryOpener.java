package net.Dispatcher.content.gui;

import de.mrjulsen.mcdragonlib.client.gui.widgets.base.DLWindow;
import net.Dispatcher.DNetworking;
import net.Dispatcher.content.trains.schedule.AdvancedScheduleScreen;
import net.Dispatcher.foundation.network.PresetListPacket;
import net.Dispatcher.foundation.network.PresetListRequestPacket;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Client-only holder for the preset library window. Packet classes must not
 * touch Screen types themselves — the same dedicated-server rule as
 * {@link SimulationResultOpener}.
 */
public class PresetLibraryOpener {

    private static WeakReference<PresetLibraryWindow> current = new WeakReference<>(null);

    /** The Presets button: opens the window in its loading state and asks for the list. */
    public static void request(AdvancedScheduleScreen host) {
        PresetLibraryWindow window =
                DLWindow.openWindow(manager -> new PresetLibraryWindow(manager, host));
        current = new WeakReference<>(window);
        DNetworking.sendToServer(new PresetListRequestPacket());
    }

    /**
     * A {@link PresetListPacket} arrived — refresh the open window, if any.
     * No screen-type check: while a DragonLib window is open the current
     * screen is its {@code DLScreen} wrapper, not the schedule screen
     * (updating a stale detached window is harmless).
     */
    public static void deliver(List<PresetListPacket.Row> rows) {
        PresetLibraryWindow window = current.get();
        if (window != null)
            window.setRows(rows);
    }
}
