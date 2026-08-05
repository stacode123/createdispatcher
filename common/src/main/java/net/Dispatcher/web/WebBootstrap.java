package net.Dispatcher.web;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import net.Dispatcher.DispatcherMod;
import net.Dispatcher.config.DispatcherConfig;
import net.minecraft.server.MinecraftServer;

/**
 * The single hook point the core mod calls ({@code WebBootstrap.init()} in
 * {@link net.Dispatcher.DispatcherMod#commonSetup()}). Starts/stops the web server with the
 * Minecraft server via Architectury lifecycle events; the tick handler only drives
 * cheap 30-second housekeeping.
 */
public final class WebBootstrap {
    private static boolean registered;
    private static volatile MinecraftServer minecraft;
    private static volatile WebServer web;
    private static int tickCounter;

    private WebBootstrap() {}

    public static void init() {
        if (registered) return;
        registered = true;
        LifecycleEvent.SERVER_STARTED.register(server -> {
            minecraft = server;
            // Before the web check: presets and plans are used in-game too.
            LegacyMigration.migrateWorldDir(server);
            if (!DispatcherConfig.COMMON.WebEnabled.get()) return;
            try {
                web = WebServer.start(server);
            } catch (Throwable t) {
                DispatcherMod.LOGGER.error("Dispatcher web interface failed to start", t);
            }
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            WebServer current = web;
            web = null;
            minecraft = null;
            if (current != null) current.stop();
        });
        TickEvent.SERVER_POST.register(server -> {
            WebServer current = web;
            if (current != null) current.tick(server);
        });
    }

    /** The running web server, or null when disabled/stopped/failed. */
    public static WebServer server() {
        return web;
    }

    public static MinecraftServer minecraftServer() {
        return minecraft;
    }
}
