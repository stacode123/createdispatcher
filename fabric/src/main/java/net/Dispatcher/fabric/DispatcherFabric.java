package net.Dispatcher.fabric;

import net.Dispatcher.DispatcherMod;
import net.Dispatcher.fabric.config.FabricConfigRegistration;
import net.Dispatcher.foundation.commands.DispatcherCommands;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import static net.Dispatcher.DispatcherMod.commonSetup;
import static net.Dispatcher.fabric.DNetworkingImpl.clientInit;
import static net.Dispatcher.fabric.DNetworkingImpl.serverInit;

public class DispatcherFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        DispatcherMod.init();
        commonSetup();
        DispatcherMod.REGISTRATE.register();
        FabricConfigRegistration.register();
        serverInit();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                DispatcherCommands.register(dispatcher));
        CommonEventsImpl.register();
        com.tterrag.registrate.fabric.EnvExecutor.runWhenOn(EnvType.CLIENT, () -> () -> {
            clientInit();
        });
    }
}
