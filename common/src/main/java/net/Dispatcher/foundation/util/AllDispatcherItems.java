package net.Dispatcher.foundation.util;

import com.tterrag.registrate.util.entry.ItemEntry;
import net.Dispatcher.DispatcherMod;
import net.Dispatcher.content.trains.schedule.AdvancedScheduleItem;

public class AllDispatcherItems {

    public static final ItemEntry<AdvancedScheduleItem> ADVANCED_SCHEDULE = DispatcherMod.REGISTRATE.item("advanced_schedule", AdvancedScheduleItem::new).register();

    public static void register() {
        DispatcherMod.LOGGER.debug("Registering Items for " + DispatcherMod.NAME);
    }
}
