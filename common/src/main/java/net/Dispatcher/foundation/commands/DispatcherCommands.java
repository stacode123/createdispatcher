package net.Dispatcher.foundation.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

public class DispatcherCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // WebCommands.register(dispatcher) is wired here once the web layer lands (D2).
    }
}
