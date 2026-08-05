package net.Dispatcher.fabric;

import net.fabricmc.loader.api.FabricLoader;

public class DispatcherExpectPlatformImpl {
	public static String platformName() {
		return "Fabric";
	}

	public static boolean isForge() {
		return false;
	}

	public static boolean isModLoaded(String id) {
		return FabricLoader.getInstance().isModLoaded(id);
	}
}
