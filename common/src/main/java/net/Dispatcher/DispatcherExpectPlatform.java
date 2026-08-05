package net.Dispatcher;

import dev.architectury.injectables.annotations.ExpectPlatform;

public class DispatcherExpectPlatform {
    @ExpectPlatform
    public static String platformName() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static boolean isForge() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static boolean isModLoaded(String id) {
        throw new AssertionError();
    }
}
