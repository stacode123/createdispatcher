package net.Dispatcher.fabric.config;

import fuzs.forgeconfigapiport.api.config.v2.ForgeConfigRegistry;
import net.Dispatcher.DispatcherMod;
import net.Dispatcher.config.DispatcherConfig;
import net.minecraftforge.fml.config.ModConfig;

public class FabricConfigRegistration {
    public static void register() {
        DispatcherMod.LOGGER.info("Registering configs with ForgeConfigAPIPort");
        ForgeConfigRegistry.INSTANCE.register(
                DispatcherMod.MOD_ID, ModConfig.Type.COMMON, DispatcherConfig.COMMON_SPEC);
    }
}
