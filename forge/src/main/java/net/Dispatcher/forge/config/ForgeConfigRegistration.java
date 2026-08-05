package net.Dispatcher.forge.config;

import net.Dispatcher.DispatcherMod;
import net.Dispatcher.config.DispatcherConfig;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class ForgeConfigRegistration {
    public static void register() {
        DispatcherMod.LOGGER.info("Registering configs with Forge Config API");
        ModLoadingContext.get().registerConfig(
                ModConfig.Type.COMMON, DispatcherConfig.COMMON_SPEC, DispatcherMod.MOD_ID + "-common.toml");
    }
}
