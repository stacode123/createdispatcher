package net.Dispatcher.foundation.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

public class DispatcherCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        net.Dispatcher.web.WebCommands.register(dispatcher);
    }
}
