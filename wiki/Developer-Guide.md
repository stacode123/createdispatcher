# Developer guide

Everything you need to build the mod, find your way around it, and not break the rules that are load-
bearing.

## Building

```bash
./gradlew build                  # both loaders → forge/build/libs, fabric/build/libs (what CI runs)
./gradlew :forge:runClient       # dev client (the primary loop)
./gradlew :fabric:runClient      # second loader, verified after Forge
./gradlew :common:test           # the simulator's JUnit suite — Minecraft-free
./gradlew :common:test --tests '*Benchmark*' -Dsim.benchmark=true   # opt-in perf benchmark
# the frontend needs Node (>= 20) — `./gradlew build` runs npm ci + vite build itself
```

- **Java 17** is enough (that is what CI uses); a 21 JDK works too. Bytecode target is 17.
- The Gradle daemon is **disabled** (`org.gradle.daemon=false`), so every invocation cold-starts —
  allow a couple of minutes and use generous timeouts.
- Dependency versions live in `gradle.properties`. DragonLib and Create Railways Navigator resolve
  from the Modrinth maven **by version id, not version name** (`maven.modrinth:<slug>:<versionId>`) —
  copy those ids exactly. CRN is `modCompileOnly` + `modLocalRuntime`; the compile-only entry is what
  makes loom remap its calls into Create.
- `forge/gradle.properties` holds `loom.platform=forge`. Without it the Forge module fails to
  configure with "Loom is not running on Forge".
- Steam 'n' Rails cannot run in the dev environment (see the note in `forge/build.gradle`); its
  schedule support is covered by JUnit and verified on a production pack.

### The benchmark digest is a semantics guard

`./gradlew :common:test --tests '*Benchmark*' -Dsim.benchmark=true` prints a digest that must stay
**`19a84b2d9cbfade6`** across any change meant to be pure refactoring or pure performance work. A
moved digest means behaviour moved — either you did not mean it, or the change is not a refactor.

## Module layout

Architectury multi-module. `common/` holds essentially everything; `forge/` and `fabric/` hold glue.

- `common` compiles against **create-fabric** (`modCompileOnly`); the Architectury transformer
  bridges the Forge side.
- `@ExpectPlatform` statics in common (`DispatcherExpectPlatform`, `DNetworking`) are implemented per
  loader as `*Impl` classes.

```
common/src/main/java/net/Dispatcher/
├── content/graph/v2/        RailGraphTranslator → junction/signal/station nodes
├── content/simulator/core/  the engine — zero Minecraft imports
├── content/simulator/       the Minecraft-coupled glue around it
├── content/trains/schedule/ the Advanced Schedule item, menu, screen, presets
├── content/gui/             client screens (map, sim results, diagram, preset window)
├── mixin/                   three mixins into Create
├── foundation/network/      hand-rolled packets
├── Interfaces/              the interfaces mixins implement
└── web/                     the embedded web server — server-only, no MC packet/client classes
└── web/                     Vite + Svelte 5 frontend; gitignored, built by `:common:buildWebDist`
```

## The layers

**`content/graph/v2/`** — `RailGraphTranslator` collapses Create's track graph into junction, signal
and station nodes with deterministic, position-derived ids. Server-only; cached per `TrackGraph`.

**`content/simulator/core/`** — the engine, and **zero Minecraft imports by design**. Deterministic:
sorted train order, fixed tick step, no wall clock. Mirrors Create 6.0.8 — bang-bang `v²/2a` control,
per-tick section reservations, atomic chain signals, persistent first-come-first-served claims.
Everything here is unit-testable, and is unit-tested.

**`content/simulator/`** — the coupled glue: `NetworkSnapshotter` (server thread), `ScheduleCompiler`
(dispatches conditions and instructions **by NBT id string** — no CRN or SnR imports),
`SimulationService` (in-game item simulation, worker thread), `HeadlessSimService` (the web planner's
entry point).

**`web/`** — **server-only, and it must stay that way.** It may never reference a client class or a
Minecraft packet class, or dedicated servers fail class verification. JDK `com.sun.net.httpserver`
plus SSE; no WebSocket, no new runtime dependencies. The coupling from core into web is exactly two
lines: `WebBootstrap.init()` in `DispatcherMod.commonSetup()` and `WebCommands.register(…)` in
`DispatcherCommands`.

**`web/` (repo root)** — the frontend: Vite + Svelte 5, zero UI or chart libraries, hand-rolled
canvas.

## Threading rules

Only these may touch `Create.RAILWAYS` or a `Level`, and only on the **server thread**: the live
sampler, the tick-side analyzers, `WebGraphStore.rebuild`, `DeployService`, preset train-import, and
`HeadlessSimService.prepare`. Everything else runs on worker threads over immutable snapshots.

HTTP handlers marshal to the server thread explicitly (`onServerThread(…)`, with a 5-second timeout)
and everything else — store reads, JSON building — happens on the HTTP pool. SSE clients each own a
dedicated writer thread; they never occupy an HTTP worker, because SSE pins its connection.

## Hooking into Create

Mixin + interface: a mixin in `common/.../mixin/` adds state to a Create class and implements an
interface from `common/.../Interfaces/`; the rest of the code casts to that interface.

| Mixin | What it does |
|---|---|
| `ScheduleRuntimeMixin` | carries the advanced-schedule marker (fields are `dispatcher$`-prefixed, so Create Realism can mix into the same class without colliding) and swaps the item `returnSchedule()` hands back |
| `ConductorBlockInteractionMixin` | widens Create's registry-entry item gate to any `ScheduleItem` |
| `StationBlockEntityMixin` | the same, for the station auto-schedule slot — on both insertion and application |

There is **one** mixin config, `dispatcherCommon.mixins.json`, declared in `fabric.mod.json` and via
`loom.forge.mixinConfig(...)` in `forge/build.gradle`. There are no platform-side mixins. Client-only
targets live in the config's `"client"` section, which is part of what makes server-only mode work.

## Networking

Hand-rolled, not Architectury networking. Packets implement `C2SPacket` / `S2CPacket`, register with
sequential ids in `DNetworking.register()`, and travel over one channel (`createdispatcher:net`).

**Registration order is the wire format — append at the end and bump `DNetworking.VERSION`**, which
is checked on join and disconnects mismatched clients with a translated message.

Current order (ids are positional):

```
CheckVersion (S2C) · RequestGraphView (C2S) · GraphView (S2C) · AdvancedScheduleSave (C2S)
RequestSimulation (C2S) · SimulationResult (S2C) · PresetListRequest (C2S) · PresetList (S2C)
PresetUpload (C2S) · PresetDownload (C2S)
```

The web interface adds no packets; it is pure HTTP/SSE.

**Never construct a client screen in a packet class** — dedicated servers crash on class
verification. Packet → GUI goes through the client-only `*Opener` holders (`SimulationResultOpener`,
`GraphMapOpener`, `PresetLibraryOpener`).

## Config and on-disk state

`DispatcherConfig` is **COMMON-only** — nothing here reads a client value. It is registered through
Forge's `ModLoadingContext` on Forge and ForgeConfigAPIPort on Fabric.

**Secrets and the allowlist never live in a config spec.** COMMON tomls ship inside client installs
and SERVER configs sync to clients, so both are server-only JSON under `config/createdispatcher/`.
World state (presets, plans, folders, calibration, audit) lives under `<world>/createdispatcher/`.
`LegacyMigration` copies both from the old Create Realism locations once, on first start.

## The frontend

`web/` is a Vite + Svelte 5 SPA, compiled at build time by Gradle itself (`:common:npmCi` +
`:common:buildWebDist`, both `Exec` tasks running `npm`) into `web/dist/`, which
`common/build.gradle` copies into the jar. `web/dist/` is **gitignored** — it is a build artifact,
never committed. Node is only needed on machines that run `./gradlew build` (like CI); players never
need it.

```bash
cd web
npm install
npm run dev                                     # :5173, proxies /api + /auth to a dev server on :8455
DISPATCHER_PROXY=http://host:8455 npm run dev   # …or to any other server
VITE_MOCK=1 npm run dev                         # no backend at all: synthetic network + fixtures
npm run check                                   # types
npm run build                                   # → dist/  (gitignored; `./gradlew build` runs this)
```

For a dev-server login: enable `Web Enabled`, then use `/dispatcher web session deployer`.

## The server-only variant

`./gradlew build` also emits a `-server` jar per loader. `serverJar` is a **`Zip`** task, not a
`Jar` — a `Jar` would regenerate the manifest and lose loom's `MixinConfigs` attribute. It repacks
`remapJar` and adds a `createdispatcher.server-only` marker, plus a `displayTest` mods.toml from
`src/serverVariant/` on Forge. Fabric needs no metadata change and gets none, which is what keeps
loom's `accessWidener` and nested-jar entries intact.

`foundation/ServerOnly.enabled()` — system property, classpath marker, or
`config/createdispatcher/server-only.marker` — gates the two `register()` calls in `commonSetup()`.
The item and menu type register in **static field initialisers**, so gating the calls is exactly what
keeps them out of the registries. **Anything new that reaches `AllDispatcherItems` or `AllMenuTypes`
needs the same guard.** Full rationale in `plans/server-only-build.md`.

## Testing and verification

- `./gradlew :common:test` — the simulator's JUnit suite plus the graph JSON and Tarjan SCC tests.
  Minecraft-free, and the only automated tests in the repo.
- Runtime verification: `:forge:runClient` first, confirm the item / editor / simulation / presets
  in-game, then `:fabric:runClient`.
- Dedicated-server and web checks via `runServer` + `curl`: auth codes, the SSE stream, and zero
  `NoClassDefFoundError` naming `net.Dispatcher`.

## Gotchas

- The package is `net.Dispatcher` — capital **D** — with directories like `Interfaces/`. The
  nonstandard capitalisation is inherited convention; match it.
- `realism:time_of_day_realistic` in `ScheduleCompiler` is deliberate. Create Realism is an optional
  integration like CRN, Tramways and Steam 'n' Rails, resolved **by NBT id string with no import**.
  Do not add a compile dependency to support one.
- Registrate registers the item in a static field initialiser. That is load-bearing for server-only
  mode; do not "clean it up" into an eager registration.

## Reference documents in the repository

| File | What |
|---|---|
| `SPEC.md` | the Advanced Schedule design and its milestone history |
| `SPEC-WEB.md` | the web interface design and its milestone history |
| `SIM_DIVERGENCES.md` | where the simulator knowingly differs from Create at runtime |
| `plans/server-only-build.md` | the server-only variant, in full |
| `docs/web-interface.md` | the admin-facing web guide this wiki's [Web Interface](Web-Interface) page is based on |
| `CHANGELOG.md` | release notes |
