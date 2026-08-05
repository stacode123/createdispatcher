package net.Dispatcher.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Server-side configuration for Create Dispatcher.
 *
 * <p>There is deliberately no CLIENT spec: nothing in the simulator, the graph translator or the
 * web layer reads a client-side value. Secrets and the user allowlist never live here — they are
 * server-only JSON under {@code config/createdispatcher/} (see {@code web.WebPaths}), because
 * COMMON tomls ship in client installs and SERVER-type configs sync to clients.
 */
public class DispatcherConfig {
    // Common Config
    public static class Common {
        public final ForgeConfigSpec.IntValue GraphNodeCap;
        public final ForgeConfigSpec.IntValue SimMaxHorizonHours;
        public final ForgeConfigSpec.IntValue SimCooldownSeconds;
        public final ForgeConfigSpec.IntValue SimMaxConcurrent;
        public final ForgeConfigSpec.IntValue SimMaxWallSeconds;
        public final ForgeConfigSpec.IntValue SimHeadwaySeconds;
        public final ForgeConfigSpec.IntValue SimWaitConflictSeconds;
        public final ForgeConfigSpec.BooleanValue SimDebugExport;
        public final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> SimDiagramHiddenCategories;
        public final ForgeConfigSpec.BooleanValue WebEnabled;
        public final ForgeConfigSpec.ConfigValue<String> WebBindAddress;
        public final ForgeConfigSpec.IntValue WebPort;
        public final ForgeConfigSpec.ConfigValue<String> WebPublicUrl;
        public final ForgeConfigSpec.ConfigValue<String> WebDefaultTier;
        public final ForgeConfigSpec.IntValue WebHttpThreads;
        public final ForgeConfigSpec.IntValue WebMaxSseClients;
        public final ForgeConfigSpec.IntValue WebLiveSampleTicks;
        public final ForgeConfigSpec.IntValue WebHistorySampleSeconds;
        public final ForgeConfigSpec.IntValue WebHistoryHours;
        public final ForgeConfigSpec.IntValue WebGraphNodeCap;
        public final ForgeConfigSpec.IntValue WebGraphMinRebuildSeconds;
        public final ForgeConfigSpec.IntValue WebGraphMaxAgeSeconds;
        public final ForgeConfigSpec.IntValue WebSignalWaitAlertSeconds;
        public final ForgeConfigSpec.IntValue WebDeadlockConfirmSeconds;
        public final ForgeConfigSpec.DoubleValue WebDetourRatio;
        public final ForgeConfigSpec.IntValue WebDetourMinBlocks;
        public final ForgeConfigSpec.IntValue WebSimMaxHorizonHours;
        public final ForgeConfigSpec.IntValue WebSimMaxQueued;
        public final ForgeConfigSpec.IntValue WebSimCooldownSeconds;
        public final ForgeConfigSpec.IntValue WebSimWallCapSeconds;
        public final ForgeConfigSpec.IntValue WebSimCacheMB;
        public final ForgeConfigSpec.IntValue WebProjectionStaleSeconds;
        public final ForgeConfigSpec.BooleanValue WebBackgroundProjections;
        public final ForgeConfigSpec.IntValue WebSessionHours;
        public final ForgeConfigSpec.IntValue WebPresetMaxCount;
        public final ForgeConfigSpec.IntValue WebPlanMaxCount;
        public final ForgeConfigSpec.IntValue WebReplayKept;
        public final ForgeConfigSpec.IntValue WebReplayBufferSeconds;
        public final ForgeConfigSpec.IntValue WebReplayLeadSeconds;
        public final ForgeConfigSpec.IntValue WebReplayTailSeconds;
        public final ForgeConfigSpec.IntValue WebReplayRadius;

        Common(ForgeConfigSpec.Builder builder) {
            builder.push("general");
            builder.push("Advanced Schedule");
            GraphNodeCap = builder.comment("Maximum track node count a rail network may have to be translated for the map viewer/simulator")
                    .defineInRange("Graph Node Cap", 4000, 100, 100000);
            SimMaxHorizonHours = builder.comment("Longest timetable simulation a player may request, in in-game hours (1000 ticks each)")
                    .defineInRange("Sim Max Horizon Hours", 48, 1, 336);
            SimCooldownSeconds = builder.comment("Seconds a player must wait between simulation requests")
                    .defineInRange("Sim Cooldown Seconds", 10, 0, 3600);
            SimMaxConcurrent = builder.comment("Maximum simulations running at the same time across all players")
                    .defineInRange("Sim Max Concurrent", 2, 1, 8);
            SimMaxWallSeconds = builder.comment("Real-time seconds a single simulation may compute before its results are cut off")
                    .defineInRange("Sim Max Wall Seconds", 10, 1, 120);
            SimHeadwaySeconds = builder.comment("Default minimum gap in seconds between consecutive trains through a track section before a headway conflict is reported; players can override per run. 0 disables the flat threshold (CRN separation conditions still apply)")
                    .defineInRange("Sim Headway Seconds", 10, 0, 600);
            SimWaitConflictSeconds = builder.comment("Seconds a simulated train may wait at a red signal before a section conflict is reported; 0 disables wait conflicts")
                    .defineInRange("Sim Wait Conflict Seconds", 30, 0, 600);
            SimDebugExport = builder.comment("Write a self-contained HTML playback viewer (dispatcher-sim-debug.html in the server/save directory) after every simulation — a debugging tool")
                    .define("Sim Debug Export", false);
            SimDiagramHiddenCategories = builder.comment("Trains whose CRN train category name contains any of these words (case-insensitive) are hidden from the time-distance diagram, e.g. [\"bus\"]")
                    .defineListAllowEmpty(java.util.List.of("Sim Diagram Hidden Categories"),
                            java.util.List::of, element -> element instanceof String);
            builder.pop();
            builder.push("Web Interface");
            WebEnabled = builder.comment("Enable the embedded web interface (server-side). Secrets and the user allowlist live in config/createdispatcher/")
                    .define("Web Enabled", false);
            WebBindAddress = builder.comment("Address the web server binds to. 127.0.0.1 = local only (put a reverse proxy in front); 0.0.0.0 exposes it on every interface")
                    .define("Web Bind Address", "127.0.0.1");
            WebPort = builder.comment("TCP port for the web server")
                    .defineInRange("Web Port", 8455, 1024, 65535);
            WebPublicUrl = builder.comment("Public base URL the site is reached at, e.g. https://trains.example.com — required for Discord login (OAuth redirect) and used for cookie/Origin security. Leave empty to use only /dispatcher web session login URLs")
                    .define("Web Public Url", "");
            WebDefaultTier = builder.comment("Tier granted to a Discord user who logs in for the first time and is not on the allowlist yet: none (default, every user must be added by hand), viewer, planner, or deployer. Auto-enrolled users are written to allowlist.json like any other, so they can be changed or removed afterwards. Never leave this above viewer on a public server")
                    // validated, so a typo falls back to "none" at load instead of silently
                    // disabling auto-enrolment the way an unparsable tier would
                    .define("Web Default Tier", "none", value -> value instanceof String text
                            && java.util.List.of("none", "viewer", "planner", "deployer")
                                    .contains(text.toLowerCase(java.util.Locale.ROOT)));
            WebHttpThreads = builder.comment("HTTP worker threads")
                    .defineInRange("Web Http Threads", 4, 2, 16);
            WebMaxSseClients = builder.comment("Maximum simultaneously connected live-update (SSE) clients")
                    .defineInRange("Web Max Sse Clients", 20, 1, 100);
            WebLiveSampleTicks = builder.comment("Game ticks between live train position samples pushed to web clients (20 = once per second)")
                    .defineInRange("Web Live Sample Ticks", 20, 5, 200);
            WebHistorySampleSeconds = builder.comment("Seconds between train position history samples (feeds the actual-vs-plan diagrams)")
                    .defineInRange("Web History Sample Seconds", 5, 1, 60);
            WebHistoryHours = builder.comment("Hours of train position history kept in memory; 0 disables history (and actual-movement diagrams)")
                    .defineInRange("Web History Hours", 2, 0, 12);
            WebGraphNodeCap = builder.comment("Maximum track node count a rail network may have to be served to the web map (separate from Graph Node Cap)")
                    .defineInRange("Web Graph Node Cap", 100000, 100, 1000000);
            WebGraphMinRebuildSeconds = builder.comment("Minimum seconds between web graph rebuilds of the same network, even when tracks changed")
                    .defineInRange("Web Graph Min Rebuild Seconds", 60, 5, 3600);
            WebGraphMaxAgeSeconds = builder.comment("Backstop: web graph snapshots older than this re-verify even without a detected change (node/signal/station edits are detected instantly; unchanged content never bumps versions or clients)")
                    .defineInRange("Web Graph Max Age Seconds", 1800, 30, 86400);
            WebSignalWaitAlertSeconds = builder.comment("Seconds a train may wait at a red signal before a SIGNAL_WAIT notification is raised (4x = critical)")
                    .defineInRange("Web Signal Wait Alert Seconds", 120, 10, 3600);
            WebDeadlockConfirmSeconds = builder.comment("Seconds a wait-for cycle must persist before a DEADLOCK notification is raised")
                    .defineInRange("Web Deadlock Confirm Seconds", 30, 5, 600);
            WebDetourRatio = builder.comment("A DETOUR notification is raised when a train's remaining route is this many times longer than the shortest path")
                    .defineInRange("Web Detour Ratio", 1.75, 1.1, 10.0);
            WebDetourMinBlocks = builder.comment("Minimum remaining route length in blocks before detour detection applies")
                    .defineInRange("Web Detour Min Blocks", 500, 0, 100000);
            WebSimMaxHorizonHours = builder.comment("Longest web planner simulation, in in-game hours (1000 ticks each)")
                    .defineInRange("Web Sim Max Horizon Hours", 48, 1, 336);
            WebSimMaxQueued = builder.comment("Maximum queued web planner simulations")
                    .defineInRange("Web Sim Max Queued", 4, 1, 32);
            WebSimCooldownSeconds = builder.comment("Seconds a web user must wait between planner simulation requests")
                    .defineInRange("Web Sim Cooldown Seconds", 15, 0, 3600);
            WebSimWallCapSeconds = builder.comment("Real-time safety cap per web simulation; 0 = uncapped (fully deterministic results). Nonzero caps mark results as truncated")
                    .defineInRange("Web Sim Wall Cap Seconds", 0, 0, 600);
            WebSimCacheMB = builder.comment("Memory budget for cached web simulation results")
                    .defineInRange("Web Sim Cache MB", 128, 16, 1024);
            WebProjectionStaleSeconds = builder.comment("Age after which the live plan-overlay projection is recomputed")
                    .defineInRange("Web Projection Stale Seconds", 300, 10, 3600);
            WebBackgroundProjections = builder.comment("Keep plan projections fresh even with no browser open, so drift calibration keeps learning and the overlay is instantly useful")
                    .define("Web Background Projections", true);
            WebSessionHours = builder.comment("Web login session lifetime in hours")
                    .defineInRange("Web Session Hours", 72, 1, 720);
            WebPresetMaxCount = builder.comment("Maximum stored schedule presets")
                    .defineInRange("Web Preset Max Count", 500, 1, 10000);
            WebPlanMaxCount = builder.comment("Maximum stored planned timetables (saved planner assignments)")
                    .defineInRange("Web Plan Max Count", 200, 1, 5000);
            WebReplayKept = builder.comment("How many notification replays (detours, deadlocks, critical waits) are kept in memory; 0 disables replay capture")
                    .defineInRange("Web Replay Kept", 20, 0, 100);
            WebReplayBufferSeconds = builder.comment("Rolling window of live train frames kept for replay lead-ups, in seconds")
                    .defineInRange("Web Replay Buffer Seconds", 300, 30, 1800);
            WebReplayLeadSeconds = builder.comment("How much lead-up before the detected event a replay includes (capped by Web Replay Buffer Seconds)")
                    .defineInRange("Web Replay Lead Seconds", 120, 10, 1800);
            WebReplayTailSeconds = builder.comment("How much aftermath after the detected event a replay keeps recording")
                    .defineInRange("Web Replay Tail Seconds", 60, 10, 600);
            WebReplayRadius = builder.comment("Trains within this many blocks of an event are included in its replay (involved trains always are)")
                    .defineInRange("Web Replay Radius", 1200, 100, 10000);
            builder.pop();
            builder.pop();
        }
    }

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final Common COMMON;

    static {
        ForgeConfigSpec.Builder commonBuilder = new ForgeConfigSpec.Builder();
        COMMON = new Common(commonBuilder);
        COMMON_SPEC = commonBuilder.build();
    }
}
