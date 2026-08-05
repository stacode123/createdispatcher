package net.Dispatcher.foundation;

import dev.architectury.platform.Platform;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether this installation runs in <em>server-only</em> mode: the Advanced Schedule item and its
 * menu type are never registered, so a client without Create Dispatcher can join the server.
 *
 * <p>The reason this exists at all: a client that is missing a registry entry the server has is
 * disconnected during login — Forge fails registry-snapshot injection ("Failed to synchronize
 * registry data from server"), Fabric throws a remap exception. Our one item is the only thing
 * standing between a mod-less client and a clean join, and the web interface — the whole point of
 * running this on a server — never touches it.
 *
 * <p>Resolved once, in this order:
 * <ol>
 *   <li>the {@code createdispatcher.serverOnly} system property — wins either way, for the dev loop;</li>
 *   <li>a {@code /createdispatcher.server-only} classpath resource — what the {@code -server} jar bakes in;</li>
 *   <li>{@code config/createdispatcher/server-only.marker} on disk — lets an admin flip the normal jar.</li>
 * </ol>
 *
 * <p>Deliberately not a config value: {@code DispatcherConfig} is registered <em>after</em>
 * {@code commonSetup()} on Forge, so a spec could not gate registration in time.
 */
public final class ServerOnly {

    public static final String PROPERTY = "createdispatcher.serverOnly";
    public static final String MARKER_FILE = "server-only.marker";
    private static final String CLASSPATH_MARKER = "/createdispatcher.server-only";

    private static final boolean ENABLED;
    private static final String SOURCE;

    static {
        boolean enabled = false;
        String source = "off";

        String property = System.getProperty(PROPERTY);
        if (property != null) {
            enabled = Boolean.parseBoolean(property);
            source = PROPERTY + "=" + property;
        } else if (classpathMarkerPresent()) {
            enabled = true;
            source = "server jar variant";
        } else if (markerFilePresent()) {
            enabled = true;
            source = "config/createdispatcher/" + MARKER_FILE;
        }

        ENABLED = enabled;
        SOURCE = source;
    }

    private ServerOnly() {}

    /**
     * The marker sits at the jar root on purpose: a resource outside any package is readable through
     * a module layer without the package having to be open, which is how both loaders load mods.
     * Both lookups, because the class's module and its class loader do not always agree.
     */
    private static boolean classpathMarkerPresent() {
        if (ServerOnly.class.getResource(CLASSPATH_MARKER) != null)
            return true;
        ClassLoader loader = ServerOnly.class.getClassLoader();
        return loader != null && loader.getResource(CLASSPATH_MARKER.substring(1)) != null;
    }

    /** Plain existence check — no directory creation, no migration, nothing that needs a world. */
    private static boolean markerFilePresent() {
        try {
            Path marker = Platform.getConfigFolder().resolve("createdispatcher").resolve(MARKER_FILE);
            return Files.exists(marker);
        } catch (Throwable ignored) {
            // Config folder unavailable this early on some setups; the other two sources still apply.
            return false;
        }
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /** Which of the three sources turned it on, for the startup log line. */
    public static String source() {
        return SOURCE;
    }
}
