package net.Dispatcher.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.architectury.platform.Platform;
import net.Dispatcher.DispatcherMod;
import net.Dispatcher.config.DispatcherConfig;
import net.Dispatcher.web.auth.Allowlist;
import net.Dispatcher.web.auth.DiscordOAuth;
import net.Dispatcher.web.auth.Secrets;
import net.Dispatcher.web.auth.Tier;
import net.Dispatcher.web.auth.WebAuth;
import net.Dispatcher.web.corridor.CorridorService;
import net.Dispatcher.web.corridor.PlanCalibration;
import net.Dispatcher.web.corridor.ProjectionSims;
import net.Dispatcher.web.corridor.StationIndex;
import net.Dispatcher.web.deploy.DeployService;
import net.Dispatcher.web.graph.WebGraphStore;
import net.Dispatcher.web.http.JsonHttp;
import net.Dispatcher.web.http.RateLimiter;
import net.Dispatcher.web.http.StaticFiles;
import net.Dispatcher.web.live.FrameHistory;
import net.Dispatcher.web.live.HistoryRing;
import net.Dispatcher.web.live.LiveTrainSampler;
import net.Dispatcher.web.monitor.DeadlockAnalyzer;
import net.Dispatcher.web.monitor.DetourAnalyzer;
import net.Dispatcher.web.monitor.NotificationHub;
import net.Dispatcher.web.monitor.SignalWaitAnalyzer;
import net.Dispatcher.web.monitor.WebNotification;
import net.Dispatcher.web.replay.ReplayStore;
import net.Dispatcher.web.sim.SimJobs;
import net.Dispatcher.web.sse.SseHub;
import net.minecraft.server.MinecraftServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The embedded web server: JDK HttpServer, fixed worker pool, static frontend from jar resources,
 * Discord OAuth + one-time-token login, graph/roster/live endpoints and the multiplexed SSE stream.
 * Never references client classes — dedicated-server-safe by construction.
 */
public final class WebServer {
    private static final Pattern SAFE_RETURN_HASH = Pattern.compile("#[A-Za-z0-9/?&=._%-]*");
    private static final int AUTH_RATE_LIMIT = 20;
    private static final long AUTH_RATE_WINDOW_MS = 60_000;
    private static final int FULL_FRAME_EVERY = 10;
    /** Per-minute budgets for the endpoints that cost real work (per session, else per ip). */
    private static final long API_RATE_WINDOW_MS = 60_000;
    private static final int RATE_HEAVY = 60;
    private static final int RATE_WRITE = 60;
    private static final int RATE_DEPLOY = 10;

    private final HttpServer http;
    private final ExecutorService pool;
    private final ExecutorService analyzer;
    private final WebAuth auth;
    private final WebGraphStore store;
    private final LiveTrainSampler sampler;
    private final SseHub hub;
    private final RateLimiter authLimiter = new RateLimiter();
    private final RateLimiter apiLimiter = new RateLimiter();
    private final long startedAtMs = System.currentTimeMillis();
    private final String bindAddress;
    private final int port;

    private final NotificationHub notifications;
    private final SignalWaitAnalyzer signalWaitAnalyzer = new SignalWaitAnalyzer();
    private final DeadlockAnalyzer deadlockAnalyzer = new DeadlockAnalyzer();
    private final DetourAnalyzer detourAnalyzer = new DetourAnalyzer();
    private final FrameHistory frameHistory = new FrameHistory();
    private final ReplayStore replays;
    private final HistoryRing history = new HistoryRing();
    private final CorridorService corridors = new CorridorService();
    private final ProjectionSims projections = new ProjectionSims();
    private final PlanCalibration calibration = new PlanCalibration();
    private final SimJobs sims = new SimJobs();
    private final ExecutorService simExecutor;
    /** CRN station groupings, snapshotted on the server thread for off-thread corridor builds. */
    private volatile Map<String, String> stationGroups = Map.of();
    private volatile java.util.Set<String> hiddenStations = java.util.Set.of();
    private boolean crnSnapshotTaken;
    private MinecraftServer minecraftServer;

    private int rebuildCounter;
    private int housekeepingCounter;
    private int deadlockCounter;
    private int projectionCounter;
    private int frameCounter;
    private long lastTrainMetaTick;
    private volatile boolean indexDirty;
    private final Map<UUID, Long> lastSentPositions = new HashMap<>();

    private WebServer(HttpServer http, ExecutorService pool, ExecutorService analyzer, WebAuth auth,
                      String bindAddress, int port) {
        this.http = http;
        this.pool = pool;
        this.analyzer = analyzer;
        this.auth = auth;
        this.bindAddress = bindAddress;
        this.port = port;
        this.hub = new SseHub();
        this.notifications = new NotificationHub(this.hub);
        this.replays = new ReplayStore(done -> hub.broadcast("replay",
                "{\"id\":\"" + done.id() + "\",\"notificationId\":\"" + done.notificationId()
                        + "\",\"kind\":\"" + done.kind() + "\"}"));
        // graphIndex is coalesced through the tick loop — during boot fills or mass prunes
        // hundreds of index changes must not become hundreds of client refetch triggers
        this.store = new WebGraphStore(analyzer,
                (id, version) -> hub.broadcast("graph",
                        "{\"id\":\"" + id + "\",\"version\":" + version + "}"),
                () -> indexDirty = true);
        this.sampler = new LiveTrainSampler();
        AtomicInteger simThreadIndex = new AtomicInteger();
        this.simExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Dispatcher-WebSim-" + simThreadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        // Replay capture: new raises of DETOUR/DEADLOCK, and SIGNAL_WAIT once it turns CRITICAL.
        // Registered last — the lambda captures final fields assigned above.
        this.notifications.setTransitionListener((previous, current) -> {
            boolean trigger = switch (current.kind()) {
                case DETOUR, DEADLOCK -> previous == null;
                case SIGNAL_WAIT -> current.severity() == WebNotification.Severity.CRITICAL
                        && (previous == null || previous.severity() != current.severity());
            };
            if (!trigger) return;
            var leadUp = frameHistory.since(
                    current.updatedTick() - DispatcherConfig.COMMON.WebReplayLeadSeconds.get() * 20L);
            this.analyzer.execute(() -> replays.beginCapture(current, leadUp, this.store));
        });
    }

    public static WebServer start(MinecraftServer minecraft) throws IOException {
        DispatcherConfig.Common config = DispatcherConfig.COMMON;
        Secrets secrets = Secrets.load(WebPaths.secretsFile());
        Allowlist allowlist = new Allowlist(WebPaths.allowlistFile());
        allowlist.load();
        WebAuth auth = new WebAuth(secrets, allowlist, config.WebPublicUrl.get(), config.WebSessionHours.get());

        String bind = config.WebBindAddress.get();
        int port = config.WebPort.get();
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bind), port), 0);
        AtomicInteger threadIndex = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(config.WebHttpThreads.get(), runnable -> {
            Thread thread = new Thread(runnable, "Dispatcher-Web-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        http.setExecutor(pool);
        ExecutorService analyzer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Dispatcher-Web-Analyzer");
            thread.setDaemon(true);
            return thread;
        });

        WebServer server = new WebServer(http, pool, analyzer, auth, bind, port);
        server.routes();
        http.start();
        DispatcherMod.LOGGER.info("Dispatcher web interface listening on {}:{} ({} allowlisted user(s), Discord login {})",
                bind, port, allowlist.size(), secrets.discordConfigured() ? "configured" : "not configured");
        WebConfigCheck.log(WebConfigCheck.check(secrets, allowlist));
        return server;
    }

    public void stop() {
        hub.closeAll();
        http.stop(0);
        pool.shutdownNow();
        analyzer.shutdownNow();
        simExecutor.shutdownNow();
        DispatcherMod.LOGGER.info("Dispatcher web interface stopped");
    }

    public WebAuth auth() {
        return auth;
    }

    public WebGraphStore store() {
        return store;
    }

    public SseHub hub() {
        return hub;
    }

    /** Base URL for links we mint: the configured public URL, else the local bind. */
    public String baseUrl() {
        if (!auth.publicUrl().isBlank()) return auth.publicUrl();
        String host = "0.0.0.0".equals(bindAddress) ? "localhost" : bindAddress;
        return "http://" + host + ":" + port;
    }

    public String bindDescription() {
        return bindAddress + ":" + port;
    }

    /** SERVER THREAD, every tick. All work inside is gated/cheap. */
    public void tick(MinecraftServer server) {
        try {
            minecraftServer = server;
            if (!crnSnapshotTaken) {
                crnSnapshotTaken = true;
                snapshotCrnGroups();
                // Covers packet-driven mutations too — in-game saves show up live in browsers.
                net.Dispatcher.content.trains.schedule.presets.PresetStore.of(server)
                        .setChangeListener(() -> hub.broadcast("presets", "{}"));
                // Plans are browser-only, but a second planner's save must still show up.
                net.Dispatcher.web.plan.PlanStore.of(server)
                        .setChangeListener(() -> hub.broadcast("plans", "{}"));
                net.Dispatcher.web.folder.TrainFolders.of(server)
                        .setChangeListener(() -> hub.broadcast("trainFolders", "{}"));
                // Restore learned drift estimates so restarts skip the warm-up.
                calibration.bind(server.getWorldPath(
                                net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("createdispatcher").resolve("web-calibration.json"));
            }
            if (++rebuildCounter >= 100) {
                rebuildCounter = 0;
                store.maybeRebuild(server);
                if (indexDirty) {
                    indexDirty = false;
                    hub.broadcast("graphIndex", "{}");
                }
            }
            int rosterBefore = sampler.rosterVersion();
            LiveTrainSampler.Frame frame = sampler.sample(server, store);
            if (frame != null) {
                if (hub.hasClients()) {
                    // busy servers bump the roster version nearly every sample — cap the
                    // refetch trigger at one per 5 s (clients throttle again on their side)
                    if (sampler.rosterVersion() != rosterBefore
                            && frame.gameTick - lastTrainMetaTick >= 100) {
                        lastTrainMetaTick = frame.gameTick;
                        hub.broadcast("trainMeta", "{\"rosterVersion\":" + sampler.rosterVersion() + "}");
                    }
                    broadcastTrains(frame);
                } else {
                    lastSentPositions.clear();
                }
                // analyzers run regardless of connected clients — the snapshot must be current on load
                frameHistory.push(frame);
                history.push(frame);
                signalWaitAnalyzer.analyze(server, store, notifications, frame.gameTick);
                detourAnalyzer.capture(server, store, notifications, frame, analyzer, frame.gameTick);
                replays.onFrame(frame, store, sampler);
            }
            if (++deadlockCounter >= 100) {
                deadlockCounter = 0;
                deadlockAnalyzer.analyze(server, store, notifications,
                        server.overworld().getGameTime());
            }
            if (++projectionCounter >= 200) {
                projectionCounter = 0;
                if (DispatcherConfig.COMMON.WebBackgroundProjections.get())
                    backgroundProjections(server);
            }
            if (++housekeepingCounter >= 600) {
                housekeepingCounter = 0;
                auth.allowlist().pollReload();
                auth.cleanup();
                authLimiter.cleanup(5 * 60_000);
                apiLimiter.cleanup(5 * 60_000);
                snapshotCrnGroups();
            }
        } catch (Throwable t) {
            DispatcherMod.LOGGER.error("Dispatcher web: tick failed", t);
        }
    }

    /**
     * SERVER THREAD, every 10 s: keeps a fresh projection per graph with live
     * trains even with no browser open — the drift calibration then learns
     * continuously and the plan overlay is instantly useful on page load.
     * requestCompute's in-flight guard and the single sim thread bound the
     * cost to what one watching viewer would cause anyway.
     */
    private void backgroundProjections(MinecraftServer server) {
        LiveTrainSampler.Frame frame = sampler.latest();
        if (frame == null) return;
        for (String graphKey : frame.graphVersions.keySet()) {
            UUID graphId;
            try {
                graphId = UUID.fromString(graphKey);
            } catch (IllegalArgumentException e) {
                continue;
            }
            WebGraphStore.Entry entry = store.get(graphId);
            if (entry == null || entry.tooLarge() || entry.simGraph() == null) continue;
            ProjectionSims.Projection projection = projections.get(graphId, entry.version());
            if (projection == null || projections.isStale(projection))
                projections.requestCompute(server, store, graphId, simExecutor,
                        sampler, history, calibration);
        }
    }

    /** SERVER THREAD: CRN reads must not happen on HTTP/analyzer threads. */
    private void snapshotCrnGroups() {
        stationGroups = net.Dispatcher.compat.CrnCompat.stationTagGroups();
        hiddenStations = net.Dispatcher.compat.CrnCompat.blacklistedStations();
    }

    /** Moved-only delta; a full frame every {@value FULL_FRAME_EVERY}th broadcast. */
    private void broadcastTrains(LiveTrainSampler.Frame frame) {
        boolean full = ++frameCounter >= FULL_FRAME_EVERY || lastSentPositions.isEmpty();
        if (full) frameCounter = 0;
        hub.broadcast("trains", trainsJson(frame, full, full ? null : lastSentPositions));
        lastSentPositions.clear();
        for (LiveTrainSampler.TrainPos pos : frame.positions)
            lastSentPositions.put(pos.id(), pack(pos));
    }

    private static long pack(LiveTrainSampler.TrainPos pos) {
        return ((long) pos.edgeId() << 32) | (Math.round(pos.offset() * 10) & 0xffffffffL);
    }

    private static String trainsJson(LiveTrainSampler.Frame frame, boolean full, Map<UUID, Long> lastSent) {
        StringBuilder json = new StringBuilder(full ? 64 * frame.positions.size() + 256 : 2048);
        json.append("{\"tick\":").append(frame.gameTick)
                .append(",\"wallMs\":").append(frame.wallMs)
                .append(",\"dayTime\":").append(frame.dayTime)
                .append(",\"rate\":").append(frame.dayTimeRate)
                .append(",\"full\":").append(full)
                .append(",\"g\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> version : frame.graphVersions.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('"').append(version.getKey()).append("\":").append(version.getValue());
        }
        json.append("},\"p\":[");
        first = true;
        for (LiveTrainSampler.TrainPos pos : frame.positions) {
            if (lastSent != null) {
                Long previous = lastSent.get(pos.id());
                if (previous != null && previous == pack(pos)) continue;
            }
            if (!first) json.append(',');
            first = false;
            json.append("[\"").append(pos.id()).append("\",\"").append(pos.graphId()).append("\",")
                    .append(pos.edgeId()).append(',')
                    .append(Math.round(pos.offset() * 10) / 10.0).append(',')
                    .append(Math.round(pos.speed() * 1000) / 1000.0).append(',')
                    .append(pos.dim()).append(']');
        }
        json.append("]}");
        return json.toString();
    }

    private String helloJson(WebAuth.Session session) {
        LiveTrainSampler.Frame frame = sampler.latest();
        StringBuilder json = new StringBuilder(256);
        json.append("{\"serverTick\":").append(frame.gameTick)
                .append(",\"serverWallMs\":").append(frame.wallMs == 0 ? System.currentTimeMillis() : frame.wallMs)
                .append(",\"dayTime\":").append(frame.dayTime)
                .append(",\"dayTimeRate\":").append(frame.dayTimeRate)
                .append(",\"rosterVersion\":").append(sampler.rosterVersion())
                .append(",\"tier\":\"").append(auth.tierOf(session).name().toLowerCase())
                .append("\",\"graphs\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> version : store.versionMap().entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('"').append(version.getKey()).append("\":").append(version.getValue());
        }
        json.append("}}");
        return json.toString();
    }

    // --- routing ---

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private void ctx(String path, Handler handler) {
        http.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } catch (Throwable t) {
                DispatcherMod.LOGGER.error("Dispatcher web: error handling {}", exchange.getRequestURI(), t);
                try {
                    JsonHttp.error(exchange, 500, "internal_error", null);
                } catch (Exception ignored) {
                }
            } finally {
                exchange.close();
            }
        });
    }

    private void routes() {
        ctx("/api/me", this::handleMe);
        ctx("/api/status", this::handleStatus);
        ctx("/api/graphs", this::handleGraphs);
        ctx("/api/trains", this::handleTrains);
        ctx("/api/live/positions", this::handleLivePositions);
        ctx("/api/notifications", this::handleNotifications);
        ctx("/api/replays", this::handleReplays);
        ctx("/api/stations", this::handleStations);
        ctx("/api/corridor/actual", this::handleCorridorActual);
        ctx("/api/corridor/plan", this::handleCorridorPlan);
        ctx("/api/presets", this::handlePresets);
        ctx("/api/plans", this::handlePlans);
        ctx("/api/train-folders", this::handleTrainFolders);
        ctx("/api/sims", this::handleSims);
        ctx("/api/deploy", this::handleDeploy);
        ctx("/api/audit", this::handleAudit);
        ctx("/api/debug/simedges", this::handleDebugSimEdges);
        ctx("/api/debug/schedule", this::handleDebugSchedule);
        ctx("/api/debug/simtrain", this::handleDebugSimTrain);
        ctx("/auth/login", this::handleLogin);
        ctx("/auth/callback", this::handleCallback);
        ctx("/auth/logout", this::handleLogout);
        ctx("/auth/token", this::handleToken);
        ctx("/", StaticFiles::handle);

        // SSE: the client owns the exchange after attach — no auto-close wrapper.
        http.createContext("/api/events", exchange -> {
            boolean attached = false;
            try {
                WebAuth.Session session = auth.require(exchange, Tier.VIEWER);
                if (session == null) return;
                if (hub.clientCount() >= DispatcherConfig.COMMON.WebMaxSseClients.get()) {
                    JsonHttp.error(exchange, 503, "sse_full", "too many live-update clients");
                    return;
                }
                long lastEventId = -1;
                String header = exchange.getRequestHeaders().getFirst("Last-Event-ID");
                if (header != null) {
                    try {
                        lastEventId = Long.parseLong(header.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                hub.attach(exchange, helloJson(session), lastEventId);
                attached = true;
            } catch (Throwable t) {
                DispatcherMod.LOGGER.error("Dispatcher web: SSE attach failed", t);
            } finally {
                if (!attached) exchange.close();
            }
        });
    }

    // --- handlers ---

    private void handleMe(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.NONE);
        if (session == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("discordId", session.sub());
        body.addProperty("username", session.name());
        body.addProperty("tier", auth.tierOf(session).name().toLowerCase());
        JsonHttp.json(exchange, 200, body);
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.VIEWER);
        if (session == null) return;
        String modVersion;
        try {
            modVersion = Platform.getMod(DispatcherMod.MOD_ID).getVersion();
        } catch (Throwable t) {
            modVersion = "unknown";
        }
        DispatcherConfig.Common config = DispatcherConfig.COMMON;
        JsonObject limits = new JsonObject();
        limits.addProperty("simMaxHorizonHours", config.WebSimMaxHorizonHours.get());
        limits.addProperty("liveSampleTicks", config.WebLiveSampleTicks.get());
        limits.addProperty("historyHours", config.WebHistoryHours.get());
        JsonObject body = new JsonObject();
        body.addProperty("modVersion", modVersion);
        body.addProperty("uptimeSeconds", (System.currentTimeMillis() - startedAtMs) / 1000);
        body.addProperty("tier", auth.tierOf(session).name().toLowerCase());
        body.addProperty("trainCount", sampler.roster().size());
        body.addProperty("sseClients", hub.clientCount());
        body.addProperty("activeNotifications", notifications.activeCount());
        body.add("limits", limits);
        JsonHttp.json(exchange, 200, body);
    }

    private void handleGraphs(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.VIEWER);
        if (session == null) return;
        String path = exchange.getRequestURI().getPath();
        String prefix = "/api/graphs";
        if (path.equals(prefix) || path.equals(prefix + "/")) {
            handleGraphIndex(exchange);
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(path.substring(prefix.length() + 1));
        } catch (Exception e) {
            JsonHttp.error(exchange, 404, "not_found", null);
            return;
        }
        // the graph payload is the biggest thing this server sends; the index above is cheap
        if (!rateOk(exchange, session, "graph", RATE_HEAVY)) return;
        WebGraphStore.Entry entry = store.get(id);
        if (entry == null) {
            JsonHttp.error(exchange, 404, "not_found", null);
            return;
        }
        if (entry.tooLarge()) {
            JsonHttp.error(exchange, 409, "graph_too_large",
                    entry.microNodes() + " micro nodes over Web Graph Node Cap");
            return;
        }
        String etag = "\"g" + entry.version() + "\"";
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        if (etag.equals(ifNoneMatch)) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (JsonHttp.acceptsGzip(exchange)) {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, entry.gzJson().length);
            try (var out = exchange.getResponseBody()) {
                out.write(entry.gzJson());
            }
        } else {
            ByteArrayOutputStream plain = new ByteArrayOutputStream(entry.gzJson().length * 4);
            try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(entry.gzJson()))) {
                in.transferTo(plain);
            }
            byte[] bytes = plain.toByteArray();
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private void handleGraphIndex(HttpExchange exchange) throws IOException {
        JsonArray graphs = new JsonArray();
        for (WebGraphStore.Entry entry : store.all()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.id().toString());
            item.addProperty("version", entry.version());
            item.addProperty("tooLarge", entry.tooLarge());
            item.addProperty("microNodes", entry.microNodes());
            item.addProperty("builtAgoMs", System.currentTimeMillis() - entry.builtAtMs());
            JsonArray dims = new JsonArray();
            JsonArray bbox = new JsonArray();
            if (!entry.tooLarge()) {
                item.addProperty("edges", entry.graph().edges.size());
                item.addProperty("stations", entry.stationCount());
                for (String dimension : entry.graph().dimensions) dims.add(dimension);
                for (double[] box : entry.bbox()) {
                    if (box == null) {
                        bbox.add(com.google.gson.JsonNull.INSTANCE);
                    } else {
                        JsonArray boxJson = new JsonArray();
                        for (double v : box) boxJson.add(Math.round(v * 10) / 10.0);
                        bbox.add(boxJson);
                    }
                }
            }
            item.add("dims", dims);
            item.add("bbox", bbox);
            graphs.add(item);
        }
        JsonObject body = new JsonObject();
        body.add("graphs", graphs);
        JsonHttp.json(exchange, 200, body);
    }

    private void handleTrains(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.VIEWER);
        if (session == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("rosterVersion", sampler.rosterVersion());
        body.add("trains", JsonHttp.GSON.toJsonTree(sampler.roster()));
        JsonHttp.json(exchange, 200, body);
    }

    private void handleNotifications(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.VIEWER);
        if (session == null) return;
        JsonHttp.json(exchange, 200, notifications.snapshot());
    }

    private void handleReplays(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.VIEWER);
        if (session == null) return;
        String path = exchange.getRequestURI().getPath();
        String prefix = "/api/replays";
        if (path.equals(prefix) || path.equals(prefix + "/")) {
            JsonHttp.json(exchange, 200, replays.index());
            return;
        }
        ReplayStore.Finalized replay = replays.get(path.substring(prefix.length() + 1));
        if (replay == null) {
            JsonHttp.error(exchange, 404, "not_found", null);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        if (JsonHttp.acceptsGzip(exchange)) {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, replay.gzJson().length);
            try (var out = exchange.getResponseBody()) {
                out.write(replay.gzJson());
            }
        } else {
            ByteArrayOutputStream plain = new ByteArrayOutputStream(replay.gzJson().length * 4);
            try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(replay.gzJson()))) {
                in.transferTo(plain);
            }
            byte[] bytes = plain.toByteArray();
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private void handleLivePositions(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.VIEWER);
        if (session == null) return;
        JsonHttp.bytes(exchange, 200, "application/json; charset=utf-8",
                trainsJson(sampler.latest(), true, null).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "no-store", true);
    }

    /** Logical stations of one graph, for corridor picking: groups → platforms with map coords. */
    private void handleStations(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.VIEWER);
        if (session == null) return;
        WebGraphStore.Entry entry = usableEntry(exchange, JsonHttp.query(exchange));
        if (entry == null) return;
        StationIndex index = StationIndex.of(entry.simGraph(), stationGroups);
        JsonArray groups = new JsonArray();
        for (String group : index.groupNames()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", group);
            JsonArray platforms = new JsonArray();
            // one row per physical platform — the index lists both track directions
            java.util.Set<UUID> seen = new java.util.HashSet<>();
            for (StationIndex.Platform platform : index.platforms(group)) {
                if (!seen.add(platform.id())) continue;
                var edge = entry.graph().edges.get(platform.edgeId());
                var position = net.Dispatcher.content.graph.v2.RailGeometry
                        .pointAlong(edge, platform.offset());
                JsonObject platformJson = new JsonObject();
                platformJson.addProperty("name", platform.name());
                platformJson.addProperty("x", Math.round(position.x * 10) / 10.0);
                platformJson.addProperty("z", Math.round(position.z * 10) / 10.0);
                platformJson.addProperty("dim", entry.graph().node(edge.from).dimension);
                platforms.add(platformJson);
            }
            item.add("platforms", platforms);
            groups.add(item);
        }
        JsonObject body = new JsonObject();
        body.addProperty("graphId", entry.id().toString());
        body.addProperty("graphVersion", entry.version());
        body.add("groups", groups);
        JsonHttp.json(exchange, 200, body);
    }

    private void handleCorridorActual(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.VIEWER);
        if (session == null) return;
        if (!rateOk(exchange, session, "corridor", RATE_HEAVY)) return;
        Map<String, String> query = JsonHttp.query(exchange);
        CorridorService.Corridor corridor = resolveCorridor(exchange, query);
        if (corridor == null) return;

        long sinceTick = parseLong(query.get("sinceTick"), 0);
        String etag = "\"a" + corridor.graphVersion() + "-" + history.lastSampleTick() + "-"
                + sinceTick + "-" + (corridor.from() + "|" + corridor.to()).hashCode() + "\"";
        exchange.getResponseHeaders().set("ETag", etag);
        if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        Map<UUID, LiveTrainSampler.RosterEntry> roster = new HashMap<>();
        for (LiveTrainSampler.RosterEntry entry : sampler.roster()) roster.put(entry.id(), entry);
        double topSpeed = com.simibubi.create.infrastructure.config.AllConfigs
                .server().trains.trainTopSpeed.getF() / 20.0;
        String body = corridors.actualJson(corridor, history, roster, sinceTick,
                sampler.latest().gameTick, topSpeed);
        JsonHttp.bytes(exchange, 200, "application/json; charset=utf-8",
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "no-cache", true);
    }

    private void handleCorridorPlan(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.VIEWER);
        if (session == null) return;
        if (!rateOk(exchange, session, "corridor", RATE_HEAVY)) return;
        Map<String, String> query = JsonHttp.query(exchange);
        CorridorService.Corridor corridor = resolveCorridor(exchange, query);
        if (corridor == null) return;

        ProjectionSims.Projection projection =
                projections.get(corridor.graphId(), corridor.graphVersion());
        MinecraftServer server = minecraftServer;
        if (projection == null) {
            if (server != null)
                projections.requestCompute(server, store, corridor.graphId(), simExecutor,
                        sampler, history, calibration);
            JsonObject body = new JsonObject();
            body.addProperty("pending", true);
            JsonHttp.json(exchange, 202, body);
            return;
        }
        if (projections.isStale(projection) && server != null)
            projections.requestCompute(server, store, corridor.graphId(), simExecutor,
                    sampler, history, calibration);
        String body = projections.planJson(corridor, projection, stationGroups, hiddenStations,
                calibration);
        JsonHttp.bytes(exchange, 200, "application/json; charset=utf-8",
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "no-cache", true);
    }

    /**
     * Diagnostic dump of what the engine actually consumes per sim edge —
     * sign events, turn ranges, static cap. {@code ?graph=<id>&ids=1,2,3}
     * (≤300 ids). Deployer-only; exists to field-debug plan-vs-actual
     * mismatches without another jar round-trip.
     */
    private void handleDebugSimEdges(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.DEPLOYER);
        if (session == null) return;
        if (!rateOk(exchange, session, "debug", RATE_HEAVY)) return;
        Map<String, String> query = JsonHttp.query(exchange);
        WebGraphStore.Entry entry = usableEntry(exchange, query);
        if (entry == null) return;
        net.Dispatcher.content.simulator.core.SimGraph simGraph = entry.simGraph();
        StringBuilder json = new StringBuilder(4096);
        json.append("{\"graphVersion\":").append(entry.version()).append(",\"edges\":[");
        boolean first = true;
        int emitted = 0;
        for (String token : query.getOrDefault("ids", "").split(",")) {
            int id = (int) parseLong(token.trim(), -1);
            if (id < 0 || id >= simGraph.edges.size() || emitted >= 300) continue;
            emitted++;
            net.Dispatcher.content.simulator.core.SimEdge edge = simGraph.edge(id);
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":").append(id)
                    .append(",\"len\":").append(CorridorService.round1(edge.length))
                    .append(",\"signCap\":").append(CorridorService.round1(edge.signCap * 20 * 3.6))
                    .append(",\"turns\":[");
            for (int i = 0; i < edge.turnRanges.length; i++) {
                if (i > 0) json.append(',');
                json.append('[').append(CorridorService.round1(edge.turnRanges[i][0]))
                        .append(',').append(CorridorService.round1(edge.turnRanges[i][1])).append(']');
            }
            json.append("],\"events\":[");
            for (int i = 0; i < edge.signEvents.size(); i++) {
                net.Dispatcher.content.simulator.core.SimEdge.SignEvent event = edge.signEvents.get(i);
                if (i > 0) json.append(',');
                json.append("{\"off\":").append(CorridorService.round1(event.offset()))
                        .append(",\"kind\":\"").append(event.kind()).append('"')
                        .append(",\"thr\":").append(Math.round(event.throttle() * 1000) / 1000.0)
                        .append('}');
            }
            json.append("],\"stations\":[");
            for (int i = 0; i < edge.stations.size(); i++) {
                net.Dispatcher.content.simulator.core.SimEdge.Station station = edge.stations.get(i);
                if (i > 0) json.append(',');
                json.append("{\"name\":").append(CorridorService.quote(station.name()))
                        .append(",\"off\":").append(CorridorService.round1(station.offset()))
                        .append(",\"appr\":").append(station.approachable()).append('}');
            }
            json.append("]}");
        }
        json.append("]}");
        JsonHttp.bytes(exchange, 200, "application/json; charset=utf-8",
                json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), "no-cache", true);
    }

    /**
     * Diagnostic dump of one train's SIMULATED run from the cached
     * projection ({@code ?graph=&train=}): end state, station visits,
     * events (path failures, signal waits...), notices. Answers "why did
     * the sim train do that" without another jar round-trip.
     */
    private void handleDebugSimTrain(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.DEPLOYER);
        if (session == null) return;
        if (!rateOk(exchange, session, "debug", RATE_HEAVY)) return;
        Map<String, String> query = JsonHttp.query(exchange);
        WebGraphStore.Entry entry = usableEntry(exchange, query);
        if (entry == null) return;
        ProjectionSims.Projection projection = projections.get(entry.id(), entry.version());
        if (projection == null) {
            JsonHttp.error(exchange, 404, "no_projection", null);
            return;
        }
        String trainId = query.getOrDefault("train", "");
        int index = -1;
        for (int i = 0; i < projection.specs().size(); i++)
            if (projection.specs().get(i).id.equals(trainId)) {
                index = i;
                break;
            }
        if (index < 0) {
            JsonHttp.error(exchange, 404, "train_not_in_projection", null);
            return;
        }
        net.Dispatcher.content.simulator.core.SimTrainSpec spec = projection.specs().get(index);
        net.Dispatcher.content.simulator.core.SimResult.TrainResult trainResult =
                projection.result().trains.get(index);
        StringBuilder json = new StringBuilder(4096);
        json.append("{\"name\":").append(CorridorService.quote(spec.name))
                .append(",\"baseTick\":").append(projection.startGameTick())
                .append(",\"endState\":").append(CorridorService.quote(trainResult.endState))
                .append(",\"startEntry\":").append(spec.startEntry)
                .append(",\"thr\":").append(Math.round(spec.initialThrottle * 100) / 100.0)
                .append(",\"visits\":[");
        boolean first = true;
        for (var visit : trainResult.visits) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"station\":").append(CorridorService.quote(visit.stationName()))
                    .append(",\"entry\":").append(visit.entryIndex())
                    .append(",\"arr\":").append(visit.arrivalTick())
                    .append(",\"dep\":").append(visit.departureTick()).append('}');
        }
        json.append("],\"events\":[");
        first = true;
        for (var event : projection.result().events) {
            if (event.trainIndex() != index) continue;
            if (!first) json.append(',');
            first = false;
            json.append("{\"type\":\"").append(event.type())
                    .append("\",\"tick\":").append(event.tick())
                    .append(",\"edge\":").append(event.edgeId())
                    .append(",\"off\":").append(CorridorService.round1(event.offset()))
                    .append(",\"data\":").append(event.data()).append('}');
        }
        json.append("],\"notices\":[");
        first = true;
        for (String notice : trainResult.notices) {
            if (!first) json.append(',');
            first = false;
            json.append(CorridorService.quote(notice));
        }
        // Every edge the head entered, in order — THE route the sim drove.
        json.append("],\"path\":[");
        java.util.List<Integer> traversed = trainResult.path;
        int from = Math.max(0, traversed.size() - 1500);
        for (int i = from; i < traversed.size(); i++) {
            if (i > from) json.append(',');
            json.append(traversed.get(i));
        }
        json.append("],\"finalPlan\":[");
        for (int i = 0; i < trainResult.finalPlan.length; i++) {
            if (i > 0) json.append(',');
            json.append(trainResult.finalPlan[i]);
        }
        json.append("]}");
        JsonHttp.bytes(exchange, 200, "application/json; charset=utf-8",
                json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), "no-cache", true);
    }

    /**
     * Diagnostic dump of one train's raw schedule (instruction ids + data)
     * and live Tramways state ({@code ?train=<uuid>}). Server-thread read,
     * deployer-only — for field-debugging plan-vs-actual mismatches.
     */
    private void handleDebugSchedule(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.DEPLOYER);
        if (session == null) return;
        if (!rateOk(exchange, session, "debug", RATE_HEAVY)) return;
        MinecraftServer server = minecraftServer;
        UUID trainId;
        try {
            trainId = UUID.fromString(JsonHttp.query(exchange).getOrDefault("train", ""));
        } catch (Exception e) {
            JsonHttp.error(exchange, 400, "bad_train", "train must be a train uuid");
            return;
        }
        if (server == null) {
            JsonHttp.error(exchange, 503, "server_not_ready", null);
            return;
        }
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        server.execute(() -> {
            try {
                com.simibubi.create.content.trains.entity.Train train =
                        com.simibubi.create.Create.RAILWAYS.trains.get(trainId);
                if (train == null) {
                    future.complete(null);
                    return;
                }
                StringBuilder json = new StringBuilder(2048);
                json.append("{\"name\":").append(CorridorService.quote(train.name.getString()))
                        .append(",\"throttle\":").append(Math.round(train.throttle * 1000) / 1000.0)
                        .append(",\"primaryLimit\":").append(Math.round(
                                net.Dispatcher.compat.TramwaysCompat.getPrimaryLimit(train) * 1000) / 1000.0)
                        .append(",\"storedPermanent\":");
                Double stored = net.Dispatcher.compat.TramwaysCompat.getStoredPermanent(train);
                json.append(stored == null ? "null" : Math.round(stored * 1000) / 1000.0);
                json.append(",\"speedKmh\":").append(Math.round(Math.abs(train.speed) * 20 * 3.6 * 10) / 10.0)
                        .append(",\"paused\":").append(train.runtime.paused)
                        .append(",\"currentEntry\":").append(train.runtime.currentEntry)
                        .append(",\"entries\":[");
                com.simibubi.create.content.trains.schedule.Schedule schedule = train.runtime.getSchedule();
                if (schedule != null) {
                    for (int i = 0; i < schedule.entries.size(); i++) {
                        var entry = schedule.entries.get(i);
                        var instruction = entry.instruction;
                        if (i > 0) json.append(',');
                        json.append("{\"i\":").append(i)
                                .append(",\"id\":").append(CorridorService.quote(
                                        instruction == null ? "?" : instruction.getId().toString()))
                                .append(",\"data\":").append(CorridorService.quote(
                                        instruction == null ? "" : instruction.getData().toString()))
                                .append(",\"cond\":[");
                        for (int c = 0; c < entry.conditions.size(); c++) {
                            if (c > 0) json.append(',');
                            json.append('[');
                            var column = entry.conditions.get(c);
                            for (int k = 0; k < column.size(); k++) {
                                var condition = column.get(k);
                                if (k > 0) json.append(',');
                                json.append("{\"id\":").append(CorridorService.quote(
                                                condition == null ? "?" : condition.getId().toString()))
                                        .append(",\"data\":").append(CorridorService.quote(
                                                condition == null ? "" : condition.getData().toString()))
                                        .append('}');
                            }
                            json.append(']');
                        }
                        json.append("]}");
                    }
                }
                json.append("]}");
                future.complete(json.toString());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        String body;
        try {
            body = future.get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            JsonHttp.error(exchange, 500, "server_read_failed", String.valueOf(e));
            return;
        }
        if (body == null) {
            JsonHttp.error(exchange, 404, "not_found", null);
            return;
        }
        JsonHttp.bytes(exchange, 200, "application/json; charset=utf-8",
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "no-cache", true);
    }

    // --- preset library (W4): reads on HTTP threads, mutations marshalled to the server thread ---

    private record JsonOutcome(int status, JsonObject body) {
        static JsonOutcome error(int status, String key) {
            JsonObject body = new JsonObject();
            body.addProperty("error", key);
            return new JsonOutcome(status, body);
        }
    }

    private JsonOutcome onServerThread(java.util.concurrent.Callable<JsonOutcome> task) {
        MinecraftServer server = minecraftServer;
        if (server == null) return JsonOutcome.error(503, "server_not_ready");
        java.util.concurrent.CompletableFuture<JsonOutcome> future = new java.util.concurrent.CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            DispatcherMod.LOGGER.error("Dispatcher web: preset mutation failed", e);
            return JsonOutcome.error(500, "server_write_failed");
        }
    }

    private void handlePresets(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.PLANNER);
        if (session == null) return;
        if (isMutation(exchange) && !rateOk(exchange, session, "write", RATE_WRITE)) return;
        MinecraftServer server = minecraftServer;
        if (server == null) {
            JsonHttp.error(exchange, 503, "server_not_ready", null);
            return;
        }
        net.Dispatcher.content.trains.schedule.presets.PresetStore store =
                net.Dispatcher.content.trains.schedule.presets.PresetStore.of(server);
        switch (exchange.getRequestMethod()) {
            case "GET", "HEAD" -> handlePresetGet(exchange, store);
            case "POST" -> handlePresetCreate(exchange, session, store);
            case "PATCH" -> handlePresetPatch(exchange, store);
            case "DELETE" -> handlePresetDelete(exchange, store);
            default -> JsonHttp.error(exchange, 405, "method_not_allowed", null);
        }
    }

    private void handlePresetGet(HttpExchange exchange,
                                 net.Dispatcher.content.trains.schedule.presets.PresetStore store)
            throws IOException {
        String id = JsonHttp.query(exchange).get("id");
        if (id == null || id.isBlank()) {
            JsonObject body = new JsonObject();
            JsonArray list = new JsonArray();
            for (var preset : store.list())
                list.add(net.Dispatcher.web.preset.PresetWeb.summaryJson(preset));
            body.add("presets", list);
            JsonHttp.json(exchange, 200, body);
            return;
        }
        var preset = store.get(parseUuid(id));
        if (preset == null) {
            JsonHttp.error(exchange, 404, "not_found", null);
            return;
        }
        var tag = store.scheduleTag(preset);
        if (tag == null) {
            JsonHttp.error(exchange, 500, "preset_corrupt", null);
            return;
        }
        JsonHttp.json(exchange, 200, net.Dispatcher.web.preset.PresetWeb.detailJson(preset, tag));
    }

    private void handlePresetCreate(HttpExchange exchange, WebAuth.Session session,
                                    net.Dispatcher.content.trains.schedule.presets.PresetStore store)
            throws IOException {
        JsonObject body = parseBody(exchange);
        if (body == null) return;
        if (body.has("sourceId")) {
            handlePresetDuplicate(exchange, session, store, body);
            return;
        }
        UUID trainId = body.has("trainId") ? parseUuid(body.get("trainId").getAsString()) : null;
        if (trainId == null) {
            JsonHttp.error(exchange, 400, "bad_train", "trainId must be a train uuid");
            return;
        }
        String requestedName = body.has("name") ? body.get("name").getAsString() : "";
        JsonOutcome outcome = onServerThread(() -> {
            com.simibubi.create.content.trains.entity.Train train =
                    com.simibubi.create.Create.RAILWAYS.trains.get(trainId);
            if (train == null) return JsonOutcome.error(404, "train_not_found");
            com.simibubi.create.content.trains.schedule.Schedule schedule = train.runtime.getSchedule();
            if (schedule == null) return JsonOutcome.error(409, "no_schedule");
            String trainName = train.name.getString();
            try {
                var preset = store.create(requestedName.isBlank() ? trainName : requestedName,
                        "train:" + trainName, schedule.write());
                return new JsonOutcome(200, net.Dispatcher.web.preset.PresetWeb.summaryJson(preset));
            } catch (net.Dispatcher.content.trains.schedule.presets.PresetStore.PresetException e) {
                return JsonOutcome.error(net.Dispatcher.web.preset.PresetWeb.statusFor(e), e.key);
            }
        });
        JsonHttp.json(exchange, outcome.status(), outcome.body());
    }

    /** POST {sourceId, name?}: copy an existing preset under a new id. */
    private void handlePresetDuplicate(HttpExchange exchange, WebAuth.Session session,
                                       net.Dispatcher.content.trains.schedule.presets.PresetStore store,
                                       JsonObject body) throws IOException {
        UUID sourceId = parseUuid(body.get("sourceId").getAsString());
        if (sourceId == null) {
            JsonHttp.error(exchange, 400, "bad_preset", "sourceId must be a preset uuid");
            return;
        }
        String requestedName = body.has("name") ? body.get("name").getAsString() : "";
        String user = session.name();
        JsonOutcome outcome = onServerThread(() -> {
            var source = store.get(sourceId);
            if (source == null) return JsonOutcome.error(404, "not_found");
            var tag = store.scheduleTag(source);
            if (tag == null) return JsonOutcome.error(500, "preset_corrupt");
            String name = requestedName.isBlank()
                    ? (source.name().length() > 55 ? source.name().substring(0, 55) : source.name()) + " copy"
                    : requestedName;
            try {
                var copy = store.create(name, "web:" + user, tag);
                return new JsonOutcome(200, net.Dispatcher.web.preset.PresetWeb.summaryJson(copy));
            } catch (net.Dispatcher.content.trains.schedule.presets.PresetStore.PresetException e) {
                return JsonOutcome.error(net.Dispatcher.web.preset.PresetWeb.statusFor(e), e.key);
            }
        });
        JsonHttp.json(exchange, outcome.status(), outcome.body());
    }

    private void handlePresetPatch(HttpExchange exchange,
                                   net.Dispatcher.content.trains.schedule.presets.PresetStore store)
            throws IOException {
        JsonObject body = parseBody(exchange);
        if (body == null) return;
        UUID id = body.has("id") ? parseUuid(body.get("id").getAsString()) : null;
        if (id == null) {
            JsonHttp.error(exchange, 400, "bad_preset", "id must be a preset uuid");
            return;
        }
        JsonOutcome outcome;
        if (body.has("key")) {
            int entry = body.has("entry") ? body.get("entry").getAsInt() : -1;
            String target = body.has("target") ? body.get("target").getAsString() : "";
            int column = body.has("col") ? body.get("col").getAsInt() : 0;
            int row = body.has("row") ? body.get("row").getAsInt() : 0;
            String key = body.get("key").getAsString();
            com.google.gson.JsonElement value = body.get("value");
            String stringValue = value != null && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
            Integer intValue = value != null && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isNumber() ? value.getAsInt() : null;
            outcome = onServerThread(() -> {
                var preset = store.get(id);
                if (preset == null) return JsonOutcome.error(404, "not_found");
                var tag = store.scheduleTag(preset);
                if (tag == null) return JsonOutcome.error(500, "preset_corrupt");
                try {
                    net.Dispatcher.web.preset.PresetWeb.applyEdit(tag, entry, target, column, row,
                            key, stringValue, intValue);
                    var updated = store.updateSchedule(id, tag);
                    return new JsonOutcome(200,
                            net.Dispatcher.web.preset.PresetWeb.detailJson(updated, store.scheduleTag(updated)));
                } catch (net.Dispatcher.web.preset.PresetWeb.EditException e) {
                    return JsonOutcome.error(400, e.getMessage());
                } catch (net.Dispatcher.content.trains.schedule.presets.PresetStore.PresetException e) {
                    return JsonOutcome.error(net.Dispatcher.web.preset.PresetWeb.statusFor(e), e.key);
                }
            });
        } else if (body.has("name")) {
            String name = body.get("name").getAsString();
            outcome = onServerThread(() -> {
                try {
                    var renamed = store.rename(id, name);
                    return new JsonOutcome(200, net.Dispatcher.web.preset.PresetWeb.summaryJson(renamed));
                } catch (net.Dispatcher.content.trains.schedule.presets.PresetStore.PresetException e) {
                    return JsonOutcome.error(net.Dispatcher.web.preset.PresetWeb.statusFor(e), e.key);
                }
            });
        } else if (body.has("folder")) {
            String folder = body.get("folder").getAsString();
            outcome = onServerThread(() -> {
                try {
                    var moved = store.move(id, folder);
                    return new JsonOutcome(200, net.Dispatcher.web.preset.PresetWeb.summaryJson(moved));
                } catch (net.Dispatcher.content.trains.schedule.presets.PresetStore.PresetException e) {
                    return JsonOutcome.error(net.Dispatcher.web.preset.PresetWeb.statusFor(e), e.key);
                }
            });
        } else {
            JsonHttp.error(exchange, 400, "bad_patch",
                    "expected a name, a folder, or a key/value edit");
            return;
        }
        JsonHttp.json(exchange, outcome.status(), outcome.body());
    }

    private void handlePresetDelete(HttpExchange exchange,
                                    net.Dispatcher.content.trains.schedule.presets.PresetStore store)
            throws IOException {
        UUID id = parseUuid(JsonHttp.query(exchange).get("id"));
        if (id == null) {
            JsonHttp.error(exchange, 400, "bad_preset", "id must be a preset uuid");
            return;
        }
        JsonOutcome outcome = onServerThread(() -> {
            try {
                store.delete(id);
                return new JsonOutcome(200, new JsonObject());
            } catch (net.Dispatcher.content.trains.schedule.presets.PresetStore.PresetException e) {
                return JsonOutcome.error(net.Dispatcher.web.preset.PresetWeb.statusFor(e), e.key);
            }
        });
        JsonHttp.json(exchange, outcome.status(), outcome.body());
    }

    /**
     * Train filing: {@code GET} returns the whole {trainId: folder} map (viewers may see the
     * organization), {@code PATCH {trainId, folder}} files one train and
     * {@code PATCH {from, to}} re-files a whole folder subtree — both planner-only. Blank
     * folders unfile. Nothing here touches Create, so it runs on the HTTP thread.
     */
    private void handleTrainFolders(HttpExchange exchange) throws IOException {
        boolean mutating = isMutation(exchange);
        WebAuth.Session session = auth.require(exchange, mutating ? Tier.PLANNER : Tier.VIEWER);
        if (session == null) return;
        if (mutating && !rateOk(exchange, session, "write", RATE_WRITE)) return;
        MinecraftServer server = minecraftServer;
        if (server == null) {
            JsonHttp.error(exchange, 503, "server_not_ready", null);
            return;
        }
        net.Dispatcher.web.folder.TrainFolders store = net.Dispatcher.web.folder.TrainFolders.of(server);
        switch (exchange.getRequestMethod()) {
            case "GET", "HEAD" -> {
                JsonObject folders = new JsonObject();
                store.snapshot().forEach((id, folder) -> folders.addProperty(id.toString(), folder));
                JsonObject body = new JsonObject();
                body.add("folders", folders);
                JsonHttp.json(exchange, 200, body);
            }
            case "PATCH" -> {
                JsonObject body = parseBody(exchange);
                if (body == null) return;
                try {
                    JsonObject result = new JsonObject();
                    if (body.has("from")) {
                        int moved = store.moveFolder(body.get("from").getAsString(),
                                body.has("to") ? body.get("to").getAsString() : "");
                        result.addProperty("moved", moved);
                    } else if (body.has("trainId")) {
                        UUID trainId = parseUuid(body.get("trainId").getAsString());
                        if (trainId == null) {
                            JsonHttp.error(exchange, 400, "bad_train", "trainId must be a train uuid");
                            return;
                        }
                        result.addProperty("folder", store.set(trainId,
                                body.has("folder") ? body.get("folder").getAsString() : ""));
                    } else {
                        JsonHttp.error(exchange, 400, "bad_patch",
                                "expected {trainId, folder} or {from, to}");
                        return;
                    }
                    JsonHttp.json(exchange, 200, result);
                } catch (net.Dispatcher.content.trains.schedule.presets.PresetStore.PresetException e) {
                    JsonHttp.error(exchange, 400, e.key, e.getMessage());
                } catch (IllegalStateException | ClassCastException e) {
                    JsonHttp.error(exchange, 400, "bad_body", "malformed folder patch");
                }
            }
            default -> JsonHttp.error(exchange, 405, "method_not_allowed", null);
        }
    }

    // --- planned timetables (W5): the saved assignment lists behind planner runs ---

    /**
     * {@code GET /api/plans} lists summaries, {@code GET ?id=} returns one in full,
     * {@code POST} saves (body {@code id} present = overwrite, absent = create),
     * {@code DELETE ?id=} removes one. Nothing here touches Create, so it all runs on the
     * HTTP thread. Plans are shared: any planner may edit any plan, and the author of
     * record stays whoever created it.
     */
    private void handlePlans(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.PLANNER);
        if (session == null) return;
        if (isMutation(exchange) && !rateOk(exchange, session, "write", RATE_WRITE)) return;
        MinecraftServer server = minecraftServer;
        if (server == null) {
            JsonHttp.error(exchange, 503, "server_not_ready", null);
            return;
        }
        net.Dispatcher.web.plan.PlanStore store = net.Dispatcher.web.plan.PlanStore.of(server);
        switch (exchange.getRequestMethod()) {
            case "GET", "HEAD" -> {
                String id = JsonHttp.query(exchange).get("id");
                if (id == null || id.isBlank()) {
                    JsonArray list = new JsonArray();
                    for (net.Dispatcher.web.plan.Plan plan : store.list())
                        list.add(net.Dispatcher.web.plan.PlanJson.summaryJson(plan));
                    JsonObject body = new JsonObject();
                    body.add("plans", list);
                    JsonHttp.json(exchange, 200, body);
                    return;
                }
                net.Dispatcher.web.plan.Plan plan = store.get(parseUuid(id));
                if (plan == null) {
                    JsonHttp.error(exchange, 404, "not_found", null);
                    return;
                }
                JsonHttp.json(exchange, 200, net.Dispatcher.web.plan.PlanJson.toJson(plan));
            }
            case "POST" -> {
                JsonObject body;
                try {
                    body = com.google.gson.JsonParser
                            .parseString(JsonHttp.readBody(exchange, 262144)).getAsJsonObject();
                } catch (Exception e) {
                    JsonHttp.error(exchange, 400, "bad_body", "expected a JSON object body");
                    return;
                }
                net.Dispatcher.web.plan.Plan parsed;
                try {
                    parsed = net.Dispatcher.web.plan.PlanJson.fromJson(body);
                } catch (Exception e) {
                    JsonHttp.error(exchange, 400, "bad_body", "malformed plan");
                    return;
                }
                try {
                    UUID id = body.has("id") && !body.get("id").isJsonNull()
                            ? parseUuid(body.get("id").getAsString()) : null;
                    net.Dispatcher.web.plan.Plan saved = id == null
                            ? store.create(parsed, session.name())
                            : store.update(id, parsed);
                    JsonHttp.json(exchange, 200, net.Dispatcher.web.plan.PlanJson.toJson(saved));
                } catch (net.Dispatcher.web.plan.PlanStore.PlanException e) {
                    JsonHttp.error(exchange, "not_found".equals(e.key) ? 404
                            : "plan_full".equals(e.key) ? 409 : 400, e.key, e.getMessage());
                }
            }
            case "DELETE" -> {
                UUID id = parseUuid(JsonHttp.query(exchange).get("id"));
                if (id == null) {
                    JsonHttp.error(exchange, 400, "bad_plan", "id must be a plan uuid");
                    return;
                }
                try {
                    store.delete(id);
                    JsonHttp.json(exchange, 200, new JsonObject());
                } catch (net.Dispatcher.web.plan.PlanStore.PlanException e) {
                    JsonHttp.error(exchange, "not_found".equals(e.key) ? 404 : 400,
                            e.key, e.getMessage());
                }
            }
            default -> JsonHttp.error(exchange, 405, "method_not_allowed", null);
        }
    }

    // --- planner sims (W5): POST queue, status/cancel, gz result, corridor diagram ---

    private void handleSims(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.PLANNER);
        if (session == null) return;
        // covers the whole subtree: submits are cooldown-gated anyway, but result/diagram
        // fetches are not, and a diagram lays a fresh corridor projection every call
        if (!rateOk(exchange, session, "sim", RATE_HEAVY)) return;
        String path = exchange.getRequestURI().getPath();
        String prefix = "/api/sims";
        String rest = path.length() > prefix.length() + 1 ? path.substring(prefix.length() + 1) : "";
        if (rest.isEmpty() || rest.equals("/")) {
            switch (exchange.getRequestMethod()) {
                case "POST" -> handleSimSubmit(exchange, session);
                case "GET", "HEAD" -> handleSimList(exchange);
                default -> JsonHttp.error(exchange, 405, "method_not_allowed", null);
            }
            return;
        }
        String[] parts = rest.split("/", 2);
        SimJobs.Job job = sims.get(parts[0]);
        if (job == null) {
            JsonHttp.error(exchange, 404, "not_found", "unknown or expired sim id");
            return;
        }
        if (parts.length == 1) {
            switch (exchange.getRequestMethod()) {
                case "GET", "HEAD" -> JsonHttp.bytes(exchange, 200,
                        "application/json; charset=utf-8",
                        sims.statusJson(job).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "no-store", true);
                case "DELETE" -> {
                    if (!job.owner.equals(session.sub())
                            && auth.tierOf(session) != Tier.DEPLOYER) {
                        JsonHttp.error(exchange, 403, "not_owner", null);
                        return;
                    }
                    sims.cancel(hub, job);
                    JsonHttp.json(exchange, 200, new JsonObject());
                }
                default -> JsonHttp.error(exchange, 405, "method_not_allowed", null);
            }
            return;
        }
        String sub = parts[1];
        if (sub.equals("result")) {
            handleSimResult(exchange, job);
        } else if (sub.equals("diagram")) {
            handleSimDiagram(exchange, job);
        } else {
            JsonHttp.error(exchange, 404, "not_found", null);
        }
    }

    private void handleSimList(HttpExchange exchange) throws IOException {
        StringBuilder json = new StringBuilder(1024);
        json.append("{\"sims\":[");
        boolean first = true;
        for (SimJobs.Job job : sims.all()) {
            if (!first) json.append(',');
            first = false;
            json.append(sims.statusJson(job));
        }
        json.append("]}");
        JsonHttp.bytes(exchange, 200, "application/json; charset=utf-8",
                json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), "no-store", true);
    }

    /**
     * POST {graphId, assignments:[{trainId, presetId, valueOverrides:[{entry,target,col,row,key,value}]}],
     * removals:[trainId], keeps:[trainId], removeScheduled?, startDayTime?, horizonHours?,
     * headwaySeconds?} → 202 {simId}. {@code removeScheduled} defaults to TRUE: a planner run
     * starts from a cleared timetable (assigned and kept trains excepted), because the point
     * is to see the plan, not the traffic it replaces. Presets are materialized here (HTTP
     * thread — store reads are concurrent-map-safe) so a bad preset id or edit fails fast
     * with a 4xx instead of a failed job.
     */
    private void handleSimSubmit(HttpExchange exchange, WebAuth.Session session) throws IOException {
        MinecraftServer server = minecraftServer;
        if (server == null) {
            JsonHttp.error(exchange, 503, "server_not_ready", null);
            return;
        }
        JsonObject body;
        try {
            body = com.google.gson.JsonParser.parseString(JsonHttp.readBody(exchange, 262144))
                    .getAsJsonObject();
        } catch (Exception e) {
            JsonHttp.error(exchange, 400, "bad_body", "expected a JSON object body");
            return;
        }
        UUID graphId = body.has("graphId") ? parseUuid(body.get("graphId").getAsString()) : null;
        if (graphId == null) {
            JsonHttp.error(exchange, 400, "bad_graph", "graphId must be a graph uuid");
            return;
        }
        WebGraphStore.Entry entry = store.get(graphId);
        if (entry == null) {
            JsonHttp.error(exchange, 404, "not_found", "unknown graph");
            return;
        }
        if (entry.tooLarge() || entry.simGraph() == null) {
            JsonHttp.error(exchange, 409, "graph_too_large", null);
            return;
        }

        net.Dispatcher.content.trains.schedule.presets.PresetStore presets =
                net.Dispatcher.content.trains.schedule.presets.PresetStore.of(server);
        java.util.List<Materialized> materialized = materialize(exchange, body, presets);
        if (materialized == null) return;
        java.util.List<SimJobs.ScheduleOverride> overrides = new java.util.ArrayList<>();
        java.util.Set<UUID> assignedTrains = new java.util.HashSet<>();
        for (Materialized assignment : materialized) {
            assignedTrains.add(assignment.trainId());
            overrides.add(new SimJobs.ScheduleOverride(assignment.trainId(), assignment.tag()));
        }
        java.util.Set<UUID> removals = new java.util.HashSet<>();
        java.util.Set<UUID> keeps = new java.util.HashSet<>();
        try {
        if (body.has("removals")) {
            for (com.google.gson.JsonElement element : body.getAsJsonArray("removals")) {
                UUID trainId = parseUuid(element.getAsString());
                if (trainId == null) {
                    JsonHttp.error(exchange, 400, "bad_removal", "removals must be train uuids");
                    return;
                }
                if (assignedTrains.contains(trainId)) {
                    JsonHttp.error(exchange, 400, "assigned_and_removed",
                            "a train cannot be both assigned and removed");
                    return;
                }
                removals.add(trainId);
            }
        }
        if (body.has("keeps")) {
            for (com.google.gson.JsonElement element : body.getAsJsonArray("keeps")) {
                UUID trainId = parseUuid(element.getAsString());
                if (trainId == null) {
                    JsonHttp.error(exchange, 400, "bad_keep", "keeps must be train uuids");
                    return;
                }
                if (removals.contains(trainId)) {
                    JsonHttp.error(exchange, 400, "kept_and_removed",
                            "a train cannot be both kept and removed");
                    return;
                }
                keeps.add(trainId);
            }
        }
        } catch (IllegalStateException | ClassCastException | NumberFormatException e) {
            JsonHttp.error(exchange, 400, "bad_body", "malformed assignments/removals/keeps");
            return;
        }
        Long startDayTime = body.has("startDayTime") && !body.get("startDayTime").isJsonNull()
                ? body.get("startDayTime").getAsLong() : null;
        int horizonHours = body.has("horizonHours") ? body.get("horizonHours").getAsInt() : 12;
        int headwaySeconds = body.has("headwaySeconds") && !body.get("headwaySeconds").isJsonNull()
                ? body.get("headwaySeconds").getAsInt() : -1;
        boolean removeScheduled = !body.has("removeScheduled")
                || body.get("removeScheduled").getAsBoolean();

        SimJobs.Submitted submitted = sims.submit(server, store, simExecutor, sampler, hub,
                session.sub(), graphId, overrides, removals, keeps, removeScheduled,
                startDayTime, horizonHours, headwaySeconds);
        if (submitted.job() == null) {
            JsonHttp.error(exchange, submitted.status(), submitted.errorKey(), null);
            return;
        }
        JsonObject response = new JsonObject();
        response.addProperty("simId", submitted.job().id);
        JsonHttp.json(exchange, 202, response);
    }

    private void handleSimResult(HttpExchange exchange, SimJobs.Job job) throws IOException {
        byte[] gz = job.gzResult;
        if (job.state != SimJobs.State.DONE || gz == null) {
            JsonHttp.error(exchange, 409, "not_done", job.state.name().toLowerCase());
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        if (JsonHttp.acceptsGzip(exchange)) {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, gz.length);
            try (var out = exchange.getResponseBody()) {
                out.write(gz);
            }
        } else {
            ByteArrayOutputStream plain = new ByteArrayOutputStream(gz.length * 4);
            try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gz))) {
                in.transferTo(plain);
            }
            byte[] bytes = plain.toByteArray();
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /** The finished run on an A→B corridor; 409 when the graph moved past the run's version. */
    private void handleSimDiagram(HttpExchange exchange, SimJobs.Job job) throws IOException {
        if (job.state != SimJobs.State.DONE || job.result == null) {
            JsonHttp.error(exchange, 409, "not_done", job.state.name().toLowerCase());
            return;
        }
        Map<String, String> query = JsonHttp.query(exchange);
        query.put("graph", job.graphId.toString());
        CorridorService.Corridor corridor = resolveCorridor(exchange, query);
        if (corridor == null) return;
        if (corridor.graphVersion() != job.graphVersion) {
            JsonHttp.error(exchange, 409, "graph_changed",
                    "graph rebuilt since this sim ran — re-run it");
            return;
        }
        String body = sims.diagramJson(job, corridor, stationGroups, hiddenStations);
        JsonHttp.bytes(exchange, 200, "application/json; charset=utf-8",
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "no-cache", true);
    }

    /** One assignment row with its preset schedule already materialized and value-edited. */
    private record Materialized(UUID trainId, UUID presetId, String presetName,
                                net.minecraft.nbt.CompoundTag tag) {}

    /**
     * Turns an {@code assignments:[{trainId, presetId, valueOverrides[]}]} array into
     * ready-to-install schedule tags. Shared by the planner sim and by deploy so both go
     * through the identical validation and the identical {@code PresetWeb} value whitelist —
     * you can never deploy an edit the simulator would have refused. Runs on the HTTP thread
     * (store reads are concurrent-map-safe), so a bad id fails fast with a 4xx.
     *
     * @return null when a response was already sent
     */
    private java.util.List<Materialized> materialize(
            HttpExchange exchange, JsonObject body,
            net.Dispatcher.content.trains.schedule.presets.PresetStore presets) throws IOException {
        java.util.List<Materialized> out = new java.util.ArrayList<>();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        if (!body.has("assignments")) return out;
        try {
            for (com.google.gson.JsonElement element : body.getAsJsonArray("assignments")) {
                JsonObject assignment = element.getAsJsonObject();
                if (!assignment.has("trainId") || !assignment.has("presetId")) {
                    JsonHttp.error(exchange, 400, "bad_assignment",
                            "assignments need trainId and presetId");
                    return null;
                }
                UUID trainId = parseUuid(assignment.get("trainId").getAsString());
                UUID presetId = parseUuid(assignment.get("presetId").getAsString());
                if (trainId == null || presetId == null) {
                    JsonHttp.error(exchange, 400, "bad_assignment",
                            "trainId and presetId must be uuids");
                    return null;
                }
                if (!seen.add(trainId)) {
                    JsonHttp.error(exchange, 400, "duplicate_train",
                            "a train may carry only one assignment");
                    return null;
                }
                var preset = presets.get(presetId);
                if (preset == null) {
                    JsonHttp.error(exchange, 404, "preset_not_found", presetId.toString());
                    return null;
                }
                var tag = presets.scheduleTag(preset);
                if (tag == null) {
                    JsonHttp.error(exchange, 500, "preset_corrupt", presetId.toString());
                    return null;
                }
                tag = tag.copy();
                if (assignment.has("valueOverrides")) {
                    for (com.google.gson.JsonElement editElement
                            : assignment.getAsJsonArray("valueOverrides")) {
                        JsonObject edit = editElement.getAsJsonObject();
                        com.google.gson.JsonElement value = edit.get("value");
                        String stringValue = value != null && value.isJsonPrimitive()
                                && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
                        Integer intValue = value != null && value.isJsonPrimitive()
                                && value.getAsJsonPrimitive().isNumber() ? value.getAsInt() : null;
                        try {
                            net.Dispatcher.web.preset.PresetWeb.applyEdit(tag,
                                    edit.has("entry") ? edit.get("entry").getAsInt() : -1,
                                    edit.has("target") ? edit.get("target").getAsString() : "",
                                    edit.has("col") ? edit.get("col").getAsInt() : 0,
                                    edit.has("row") ? edit.get("row").getAsInt() : 0,
                                    edit.has("key") ? edit.get("key").getAsString() : "",
                                    stringValue, intValue);
                        } catch (net.Dispatcher.web.preset.PresetWeb.EditException e) {
                            JsonHttp.error(exchange, 400, e.getMessage(), "value override rejected");
                            return null;
                        }
                    }
                }
                out.add(new Materialized(trainId, presetId, preset.name(), tag));
            }
        } catch (IllegalStateException | ClassCastException | NumberFormatException e) {
            JsonHttp.error(exchange, 400, "bad_body", "malformed assignments");
            return null;
        }
        return out;
    }

    // --- deploy (W6): the one web action that changes the running world ---

    /**
     * {@code POST /api/deploy {assignments (same shape as /api/sims), mode: IMMEDIATE|IDLE_ONLY}}
     * → per-train results. Deployer tier, rate-limited, marshalled to the server thread, and
     * every attempt is written to the audit journal. {@code mode} defaults to the safe
     * IDLE_ONLY: an unknown or missing value never interrupts a running train.
     */
    private void handleDeploy(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.DEPLOYER);
        if (session == null) return;
        if (!exchange.getRequestMethod().equals("POST")) {
            JsonHttp.error(exchange, 405, "method_not_allowed", null);
            return;
        }
        if (!rateOk(exchange, session, "deploy", RATE_DEPLOY)) return;
        MinecraftServer server = minecraftServer;
        if (server == null) {
            JsonHttp.error(exchange, 503, "server_not_ready", null);
            return;
        }
        JsonObject body;
        try {
            body = com.google.gson.JsonParser.parseString(JsonHttp.readBody(exchange, 262144))
                    .getAsJsonObject();
        } catch (Exception e) {
            JsonHttp.error(exchange, 400, "bad_body", "expected a JSON object body");
            return;
        }
        net.Dispatcher.content.trains.schedule.presets.PresetStore presets =
                net.Dispatcher.content.trains.schedule.presets.PresetStore.of(server);
        java.util.List<Materialized> materialized = materialize(exchange, body, presets);
        if (materialized == null) return;
        if (materialized.isEmpty()) {
            JsonHttp.error(exchange, 400, "no_assignments", "nothing to deploy");
            return;
        }
        DeployService.Mode mode = DeployService.Mode.parse(
                body.has("mode") && !body.get("mode").isJsonNull() ? body.get("mode").getAsString() : "");
        java.util.List<DeployService.Assignment> assignments = new java.util.ArrayList<>();
        for (Materialized row : materialized)
            assignments.add(new DeployService.Assignment(row.trainId(), row.presetId().toString(),
                    row.presetName(), row.tag()));
        String user = session.name();
        String discordId = session.sub();
        JsonOutcome outcome = onServerThread(() -> {
            java.util.List<DeployService.Result> results =
                    DeployService.deploy(server, mode, user, discordId, assignments);
            return new JsonOutcome(200, DeployService.resultsJson(mode, results));
        });
        if (outcome.status() == 200) {
            JsonObject event = new JsonObject();
            event.addProperty("user", user);
            event.addProperty("mode", mode.name());
            event.addProperty("applied", outcome.body().get("applied").getAsInt());
            event.addProperty("skipped", outcome.body().get("skipped").getAsInt());
            hub.broadcast("deployed", event.toString());
        }
        JsonHttp.json(exchange, outcome.status(), outcome.body());
    }

    /** {@code GET /api/audit?limit=} — the newest deploy journal entries, newest first. */
    private void handleAudit(HttpExchange exchange) throws IOException {
        WebAuth.Session session = auth.require(exchange, Tier.DEPLOYER);
        if (session == null) return;
        if (!rateOk(exchange, session, "audit", RATE_HEAVY)) return;
        MinecraftServer server = minecraftServer;
        if (server == null) {
            JsonHttp.error(exchange, 503, "server_not_ready", null);
            return;
        }
        int limit = (int) Math.max(1, Math.min(500,
                parseLong(JsonHttp.query(exchange).get("limit"), 100)));
        JsonObject body = new JsonObject();
        body.add("entries", net.Dispatcher.web.deploy.AuditLog.of(server).tail(limit));
        JsonHttp.json(exchange, 200, body);
    }

    /**
     * Fixed-window budget for endpoints that cost real work, keyed by session (so one
     * runaway tab cannot spend everyone's budget) and falling back to the client ip.
     * Sends the 429 itself.
     */
    private static boolean isMutation(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        return !method.equals("GET") && !method.equals("HEAD");
    }

    private boolean rateOk(HttpExchange exchange, WebAuth.Session session, String bucket,
                           int limit) throws IOException {
        String key = bucket + "|" + (session != null && !session.sub().isBlank()
                ? session.sub() : JsonHttp.clientIp(exchange));
        if (apiLimiter.allow(key, limit, API_RATE_WINDOW_MS)) return true;
        JsonHttp.error(exchange, 429, "rate_limited", "too many " + bucket + " requests — slow down");
        return false;
    }

    /** Parses a small JSON body; null = an error response was already sent. */
    private JsonObject parseBody(HttpExchange exchange) throws IOException {
        try {
            return com.google.gson.JsonParser.parseString(JsonHttp.readBody(exchange, 8192))
                    .getAsJsonObject();
        } catch (Exception e) {
            JsonHttp.error(exchange, 400, "bad_body", "expected a JSON object body");
            return null;
        }
    }

    private static UUID parseUuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** Shared corridor query validation; null = a response was already sent. */
    private CorridorService.Corridor resolveCorridor(HttpExchange exchange,
                                                     Map<String, String> query) throws IOException {
        WebGraphStore.Entry entry = usableEntry(exchange, query);
        if (entry == null) return null;
        String from = query.get("from");
        String to = query.get("to");
        if (from == null || from.isBlank() || to == null || to.isBlank()
                || from.equalsIgnoreCase(to)) {
            JsonHttp.error(exchange, 400, "bad_corridor", "need distinct from/to station groups");
            return null;
        }
        CorridorService.Corridor corridor =
                corridors.corridor(entry, from, to, stationGroups, hiddenStations);
        if (corridor == null) {
            JsonHttp.error(exchange, 404, "route_not_found",
                    "no route between those stations on this graph");
            return null;
        }
        return corridor;
    }

    /** Parses ?graph= and pins ?v= (409 graph_changed on mismatch); null = response sent. */
    private WebGraphStore.Entry usableEntry(HttpExchange exchange,
                                            Map<String, String> query) throws IOException {
        UUID graphId;
        try {
            graphId = UUID.fromString(query.getOrDefault("graph", ""));
        } catch (Exception e) {
            JsonHttp.error(exchange, 400, "bad_graph", "graph must be a graph uuid");
            return null;
        }
        WebGraphStore.Entry entry = store.get(graphId);
        if (entry == null) {
            JsonHttp.error(exchange, 404, "not_found", null);
            return null;
        }
        if (entry.tooLarge() || entry.simGraph() == null) {
            JsonHttp.error(exchange, 409, "graph_too_large", null);
            return null;
        }
        String pinned = query.get("v");
        if (pinned != null && !pinned.isBlank() && parseLong(pinned, -1) != entry.version()) {
            JsonHttp.error(exchange, 409, "graph_changed",
                    "graph rebuilt to version " + entry.version());
            return null;
        }
        return entry;
    }

    private static long parseLong(String text, long fallback) {
        if (text == null || text.isBlank()) return fallback;
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        if (!authRateOk(exchange)) return;
        if (!auth.secrets().discordConfigured() || auth.publicUrl().isBlank()) {
            JsonHttp.error(exchange, 503, "discord_not_configured",
                    "set discordClientId/discordClientSecret in config/createdispatcher/secrets.json and Web Public Url"
                            + " in the createdispatcher common config, or log in via a /dispatcher web session link");
            return;
        }
        String returnHash = JsonHttp.query(exchange).getOrDefault("return", "");
        if (!SAFE_RETURN_HASH.matcher(returnHash).matches()) returnHash = "";
        String state = auth.mintState(returnHash);
        JsonHttp.redirect(exchange, DiscordOAuth.authorizeUrl(
                auth.secrets().discordClientId, auth.publicUrl() + "/auth/callback", state));
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        if (!authRateOk(exchange)) return;
        var query = JsonHttp.query(exchange);
        String returnHash = auth.parseState(query.get("state"));
        String code = query.get("code");
        if (returnHash == null || code == null || code.isBlank()) {
            JsonHttp.error(exchange, 403, "invalid_state", "restart the login from the site");
            return;
        }
        DiscordOAuth.DiscordUser user;
        try {
            String token = DiscordOAuth.exchangeCode(auth.secrets().discordClientId,
                    auth.secrets().discordClientSecret, auth.publicUrl() + "/auth/callback", code);
            user = DiscordOAuth.fetchUser(token);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            JsonHttp.error(exchange, 502, "discord_error", "interrupted");
            return;
        } catch (IOException e) {
            DispatcherMod.LOGGER.error("Dispatcher web: Discord OAuth failed", e);
            JsonHttp.error(exchange, 502, "discord_error", e.getMessage());
            return;
        }
        if (auth.allowlist().tierOf(user.id()) == Tier.NONE) {
            if (!autoEnroll(user)) {
                JsonHttp.html(exchange, 403, "<pre>Not allowlisted.\n\nYour Discord ID: " + user.id()
                        + "\nAsk a server admin to run: /dispatcher web allow " + user.id() + " viewer</pre>");
                return;
            }
        }
        String cookie = auth.sessionCookie(auth.mintSession(user.id(), user.username(), null), auth.sessionTtlSeconds());
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
        JsonHttp.redirect(exchange, "/" + returnHash);
    }

    /**
     * Grants 'Web Default Tier' to a Discord user seen for the first time, writing them into
     * allowlist.json so the grant is visible to {@code /dispatcher web list} and revocable like
     * any other entry. Somebody an admin has already touched — at any tier, including none —
     * is never re-enrolled. Off by default (tier none); returns false when it did not apply.
     */
    private boolean autoEnroll(DiscordOAuth.DiscordUser user) {
        Tier tier = Tier.parse(DispatcherConfig.COMMON.WebDefaultTier.get());
        if (tier == Tier.NONE) return false;
        try {
            if (!auth.allowlist().enroll(user.id(), tier, user.username() + " (first login)")) return false;
        } catch (IOException e) {
            // The grant is only real once it is on disk — a failed write must not hand out a session.
            DispatcherMod.LOGGER.error("Dispatcher web: could not auto-enroll {} as {}", user.id(), tier, e);
            return false;
        }
        DispatcherMod.LOGGER.info("Dispatcher web: {} ({}) auto-enrolled as {} on first login",
                user.username(), user.id(), tier.name().toLowerCase());
        return true;
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            JsonHttp.error(exchange, 405, "method_not_allowed", null);
            return;
        }
        if (exchange.getRequestHeaders().getFirst("X-Dispatcher-Csrf") == null) {
            JsonHttp.error(exchange, 403, "csrf", "missing X-Dispatcher-Csrf header");
            return;
        }
        exchange.getResponseHeaders().add("Set-Cookie", auth.sessionCookie("", 0));
        JsonHttp.empty(exchange, 204);
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        if (!authRateOk(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        String prefix = "/auth/token/";
        if (path.length() <= prefix.length()) {
            JsonHttp.error(exchange, 404, "not_found", null);
            return;
        }
        Tier tier = auth.consumeLoginToken(path.substring(prefix.length()));
        if (tier == null || tier == Tier.NONE) {
            JsonHttp.html(exchange, 403, "<pre>Login link invalid, expired, or already used.\nMint a new one: /dispatcher web session &lt;tier&gt;</pre>");
            return;
        }
        String sub = "local:" + Long.toHexString(System.nanoTime());
        String cookie = auth.sessionCookie(auth.mintSession(sub, "local-" + tier.name().toLowerCase(), tier),
                auth.sessionTtlSeconds());
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
        JsonHttp.redirect(exchange, "/");
    }

    private boolean authRateOk(HttpExchange exchange) throws IOException {
        if (authLimiter.allow(JsonHttp.clientIp(exchange), AUTH_RATE_LIMIT, AUTH_RATE_WINDOW_MS)) return true;
        JsonHttp.error(exchange, 429, "rate_limited", "try again in a minute");
        return false;
    }
}
