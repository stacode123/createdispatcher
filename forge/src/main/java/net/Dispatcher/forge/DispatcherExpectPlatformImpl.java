package net.Dispatcher.forge;

import net.minecraftforge.fml.ModList;

public class DispatcherExpectPlatformImpl {
	public static String platformName() {
		return "Forge";
	}

	public static boolean isForge() {
		return true;
	}

	public static boolean isModLoaded(String id) {
		return ModList.get().isLoaded(id);
	}
}
