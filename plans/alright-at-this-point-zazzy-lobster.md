# Migration: Advanced Schedule + Web Interface → Create Dispatcher

## Context

The `Advanced-Schedule` branch has grown a feature set that no longer belongs in Create Realism.
Realism is three small client-facing features (acceleration, banking, ETCS HUD) totalling ~2.9k lines.
The Advanced Schedule work on top of it is ~9.5k lines of Java, the web interface another 7.1k Java +
10.8k TypeScript/Svelte — a deterministic timetable simulator, a rail-graph translator, an embedded
HTTP/SSE server with Discord auth, a planner, and a deploy path. Different audience (server operators,
not players), different release cadence, different dependency surface. Shipping them as one jar means
every Realism player downloads a web server they will never start.

This plan lifts those two features into a new standalone mod, **Create Dispatcher**
(`createdispatcher`, package `net.Dispatcher`), in a sibling repository, using Realism's architecture
verbatim: Architectury `common`/`forge`/`fabric`, `@ExpectPlatform` statics with `*Impl` per loader,
hand-rolled packet channel, mixin+interface injection into Create, ForgeConfigSpec via
ForgeConfigAPIPort on Fabric.

**The single most important finding:** `git ls-tree 1.20.1-C6 -- .../net/Realism/content` is empty.
Every line of this feature exists only on `Advanced-Schedule`. Realism's mainline was never touched,
so there is **no deletion work on Realism** — the branch is simply retired as an archive after the lift.

### Decisions (confirmed with the user)

| Topic | Decision |
|---|---|
| Identity | mod id `createdispatcher`, package `net.Dispatcher`, name "Create Dispatcher" |
| Repo | fresh sibling repo `~/IdeaProjects/CreateDispatcher`, own git history from commit 1 |
| Coupling | **fully standalone** — no dependency on Realism either way; small shared plumbing is duplicated |
| Targets | MC 1.20.1 forge + fabric now; build structured so a `1.21.1` NeoForge branch can follow |

Realism keeps running as-is. If both mods are installed the simulator automatically picks up Realism's
reduced acceleration, because `NetworkSnapshotter` reads the live `train.acceleration()` that Realism's
`TrainMixin` already modified — no code link needed.

---

## 1. What moves, what stays

Source of truth for the lift: `common/src/main/java/net/Realism/`.

### Moves to Dispatcher (whole packages)

| Path | Files / LOC | Notes |
|---|---|---|
| `content/graph/v2/` | 12 / 1.3k | `RailGraphTranslator` (645), `RailGraphJson`, `RailGeometry`, `GraphViewService`, cache |
| `content/simulator/core/` | 16 / 4.2k | MC-free engine; `SimEngine` 1513, `ConflictDetector` 666, `SimDiagram` 594 |
| `content/simulator/` | 8 / 1.9k | `SimulationService`, `HeadlessSimService`, `ScheduleCompiler`, `NetworkSnapshotter`, `SimTopology`, `SimGraphBuilder`, `SimDebugExporter`, `SimulationPayload` |
| `web/` | 41 / 7.1k | `WebServer` 1927 + auth/live/monitor/corridor/sim/plan/preset/deploy/replay/folder/sse/http |
| `common/src/test/` | 20 / 2.9k | all 20 are simulator/graph/web tests |
| `web/` (repo root) | 58 / 10.8k TS | Vite + Svelte 5 frontend + committed `dist/` |

### Moves to Dispatcher (file-level, from mixed packages)

- `content/trains/schedule/` → `AdvancedScheduleItem`, `AdvancedScheduleMenu`, `AdvancedScheduleScreen`, `presets/{Preset,PresetJson,PresetStore}`. **`TimeOfDayRealistic` stays in Realism.**
- `content/gui/` → `GraphMapScreen`, `GraphMapOpener`, `SimulationSetupWindow`, `SimulationResultsWindow`, `SimulationResultOpener`, `SimulationClientData`, `TimeDistanceDiagramWindow`, `SimTimeFormat`, `PresetLibraryWindow`, `PresetLibraryOpener`. **`TrainSettingsGui` + `RealismScreens` stay.**
- `foundation/network/` → the 9 AS packets (`AdvancedScheduleSavePacket`, `RequestGraphViewPacket`, `GraphViewPacket`, `RequestSimulationPacket`, `SimulationResultPacket`, `PresetList{Request,}Packet`, `PresetUploadPacket`, `PresetDownloadPacket`). The 6 core packets stay.
- `foundation/util/` → `AllRealismItems` → `AllDispatcherItems`, `AllMenuTypes` (both register *only* advanced-schedule entries — they move whole). **`AllRealismIcons` stays.**
- `foundation/commands/RealismCommands` → `DispatcherCommands` (its entire body is `WebCommands.register(dispatcher)`).
- `compat/CrnCompat` — moves (Realism mainline has no CrnCompat).
- `mixin/` → `ConductorBlockInteractionMixin`, `StationBlockEntityMixin`, `mixinaccesors/{ScheduleRuntimeAccessor, NavigationAccessor, ScheduleScreenAccessor}`.

### Duplicated (standalone decision — both mods keep a copy)

`RealismExpectPlatform` → `DispatcherExpectPlatform`; `RNetworking` + `RNetworkingImpl` (×2 loaders) →
`DNetworking`/`DNetworkingImpl`; `foundation/util/{C2SPacket,S2CPacket}`; `CommonEvents` +
`CommonEventsImpl` (version-check join hook only); `config/*ConfigRegistration` (×2 loaders);
`compat/TramwaysCompat` (+ `Interfaces/ITramSignPoint`, `mixinaccesors/TramSignDataAccessor`);
`mixinaccesors/ScreenAccessor`. Total ~700 lines — cheap next to a hard mod dependency.

### Stays in Realism, untouched

ETCS (`content/trains/etcs/`, `SignalFinder`, `TrackOverlay`), banking mixins, `TrainMixin`,
`TrainSettings`/`ITrainInterface`, `RealismSounds` + jlayer, `RExtras` + `TimeOfDayRealistic`,
`datagen/`, `AllRealismIcons`, `RNBTHelper` (dead — nothing references it).

---

## 2. Two classes need surgery, not a move

### `mixin/ScheduleRuntimeMixin` (202 lines) is shared — split it

It carries Realism's departure/arrival date bookkeeping (feeding `TimeOfDayRealistic`) **and** the
advanced-schedule marker. Both mods will mixin `ScheduleRuntime` from separate configs, which Mixin
handles fine as long as `@Unique` names differ.

- **Realism keeps** (already on `1.20.1-C6`, so literally no change): `departureDate`,
  `expectedArrivalDate`, `lastScheduledDepartureDate`, `dontCheck`, the `tick`/`setSchedule`/`reset`/
  `startCurrentInstruction`/`write` injections, and `IScheduleRuntimeMixin` minus the two AS methods.
- **Dispatcher gets a new, minimal mixin**: `@Unique boolean dispatcher$advanced` + NBT read/write,
  the `setSchedule` TAIL reset, and the `returnSchedule` `@Inject` that hands back
  `AllDispatcherItems.ADVANCED_SCHEDULE` instead of `AllItems.SCHEDULE`.
- New interface `Interfaces/IAdvancedScheduleRuntime { boolean isAdvancedSchedule(); void setAdvancedSchedule(boolean); }`
  — consumers are `AdvancedScheduleItem`, `ConductorBlockInteractionMixin`, `web/deploy/DeployService`.
  Keep SPEC-WEB §7's deploy ordering intact: strip `Progress` → `discardSchedule()` → non-empty guard →
  `setSchedule(s,false)` → `setAdvancedSchedule(true)` **after**.

### `config/RealismConfig` (216 lines) splits by prefix

`DispatcherConfig.Common` takes the 9 `Sim*`/`GraphNodeCap` values (push-block "Simulator") and the 32
`Web*` values (push-block "Web Interface") → `createdispatcher-common.toml`. Realism's 5 accel/banking/
ETCS commons and the whole `Client` block stay. Dispatcher needs no `Client` spec — verify with
`grep -rn "RealismConfig.CLIENT" content/gui content/graph content/simulator` before dropping it
(expected: only `debugMode`, which can move to the Common block or be dropped).

---

## 3. Networking: a clean slate

Dispatcher gets its own channel `createdispatcher:net` and its own version gate, so the AS packets get
IDs 0–9 in a fresh sequence and `DNetworking.VERSION` resets to `"1"`:

```
0 CheckVersionS2CPacket   5 SimulationResultPacket
1 RequestGraphViewPacket  6 PresetListRequestPacket
2 GraphViewPacket         7 PresetListPacket
3 AdvancedScheduleSave    8 PresetUploadPacket
4 RequestSimulationPacket 9 PresetDownloadPacket
```

Reuse `RNetworking`'s exact shape (`registerS2C`/`registerC2S` off one shared `int id` counter,
`buf.writeVarInt(id)` framing, `mc.execute`/`player.server.execute` main-thread redispatch, the four
`@ExpectPlatform` senders, `CheckVersion` registered first). Realism's own `RNetworking` is unchanged on
mainline (it never had the AS packets), so no VERSION bump there.

The web interface adds nothing here — it is pure HTTP/SSE and registers zero MC packets.

---

## 4. Naming sweep

Mechanical, but every item must be hit or the mod half-works. Do it as one scripted pass after the file
move, then compile.

| From | To |
|---|---|
| `net.Realism` | `net.Dispatcher` (dirs + `package`/`import`) |
| `RealismMod` / `MOD_ID "realism"` | `DispatcherMod` / `"createdispatcher"` |
| `RNetworking` | `DNetworking` (channel `createdispatcher:net`) |
| `realismCommon.mixins.json` | `dispatcherCommon.mixins.json`, package `net.Dispatcher.mixin` |
| `realism.accesswidener` | `createdispatcher.accesswidener` (empty header; keep the wiring — cheap, and the 1.21.1 branch will want it) |
| lang keys `realism.*` | `dispatcher.*` (~145 keys, in both `en_us.json` and the Java that references them) |
| `item.realism.advanced_schedule` | `item.createdispatcher.advanced_schedule` (forced by mod id) |
| `assets/realism/…` | `assets/createdispatcher/…` (item model/texture, `sim_debug.html`, `web/`) |
| `/dispatcher web …` | `/dispatcher web …` |
| `config/realism-web/` | `config/createdispatcher/` (`secrets.json`, `allowlist.json`) — `web/WebPaths.java` |
| `<world>/realism/` | `<world>/createdispatcher/` (`presets/`, `plans/`, `web-audit.jsonl`, `web-calibration.json`, `train-folders.json`) |
| `realism-sim-debug.html` | `dispatcher-sim-debug.html` |
| thread names `Realism-Web-N`, `Realism-WebSim`, `Realism-Web-IO` | `Dispatcher-*` |
| `X-Realism-Csrf` | `X-Dispatcher-Csrf` — **server and frontend must change together** (`web/http/JsonHttp` + `web/src/lib/api/http.ts:23`) |
| localStorage `realism.layout`, `realism.folders.extra` | `dispatcher.*` (`web/src/lib/stores/{layout,folders}.svelte.ts`) |
| `web/package.json` name, `vite.config.ts` plugin name | `dispatcher-web` |

**Data migration for the field server.** The 1949-graph production server has live presets, plans,
allowlist, secrets and calibration under the old paths. Add a one-shot copy in `WebBootstrap.init()` /
`PresetStore`: if the legacy dir exists and the new one does not, copy it and log once. Small, and it
saves re-authoring the allowlist and losing warm drift calibration.

---

## 5. Repository skeleton

```
~/IdeaProjects/CreateDispatcher/
  settings.gradle          rootProject.name = "createdispatcher"; include common/forge/fabric
  build.gradle             Realism's root verbatim; publishRealism → publishDispatcher
  gradle.properties        mod_id=createdispatcher, mod_group_id=net.dispatcher, mod_version=1.0.0
  gradle/wrapper/          8.8 (copy Realism's, incl. gradlew/gradlew.bat)
  common/ forge/ fabric/
  web/                     frontend + committed dist/
  .github/workflows/build.yml
  CLAUDE.md  README.md  CHANGELOG.md  LICENSE (MIT, same author)
  SPEC.md  SPEC-WEB.md  SIM_DIVERGENCES.md  docs/web-interface.md  plans/
  .claude/skills/verify/SKILL.md
```

Copy `build.gradle`, `common/build.gradle`, `forge/build.gradle`, `fabric/build.gradle` from Realism and
adjust:

- **Drop**: `javazoom:jlayer` everywhere (ETCS sounds only) and both `shadowJar { relocate("javazoom.jl", …) }`
  lines — this also kills the fabric copy-paste bug where the prefix reads `purplecreate.realism.…`.
  Drop `curse.maven:journeymap` from `forge/build.gradle`.
- **Keep**: `create-fabric` `modCompileOnly` in common (the established cross-loader pattern),
  Registrate (`AllDispatcherItems`/`AllMenuTypes` use `CreateRegistrate`), DragonLib as a hard dep —
  contrary to a first reading it *is* required here: `SimulationSetupWindow`, `SimulationResultsWindow`,
  `TimeDistanceDiagramWindow`, `PresetLibraryWindow` and `AdvancedScheduleScreen` are all DragonLib
  windows. Keep Tramways `modCompileOnly`+`modRuntimeOnly`, CRN `modCompileOnly`+`modLocalRuntime`
  (compile-only entry is what keeps loom remapping its Create calls), the JUnit block and the
  `sim.benchmark` system property.
- **Keep verbatim** in `common/build.gradle`: the `processResources { from(rootProject.file("web/dist")) { into "assets/createdispatcher/web" } }`
  block and the whole `verifyWebDist` task (its digest recipe is mirrored in `web/vite.config.ts` — the
  two must stay in lockstep), plus `tasks.named("check") { dependsOn("verifyWebDist") }`.
- `loom { forge { mixinConfig("dispatcherCommon.mixins.json") } }` — **one config, no platform mixins.**
  Every forge/fabric-side mixin in Realism (`TrainHudMixin`, `TrackPlacementOverlayMixin`, the render
  tilt mixins) is core ETCS/banking and does not come along.
- `mods.toml` / `fabric.mod.json`: new id/name/description; deps = minecraft, forge|fabricloader+api,
  create, architectury, dragonlib (mandatory); tramways optional. While copying, fix Realism's malformed
  `versionRange="${create_version},)"` (missing `[`).
- `publishMods`: new Modrinth/CurseForge project ids in `gradle.properties` (create the projects first;
  leave the ids blank and the task unwired until then).
- CI: copy `build.yml`, fix the artifact paths to `forge/build/libs/` + `fabric/build/libs/` (Realism's
  point at the right dirs on this branch already). `CI` env being set makes `verifyWebDist` fail hard on
  a stale `dist/` — that is intended.

The `forge`/`fabric` modules only need: entry class (`DispatcherModForge` / `DispatcherFabric`),
`DispatcherExpectPlatformImpl`, `DNetworkingImpl`, `{Forge,Fabric}ConfigRegistration`, `CommonEventsImpl`,
command registration. Follow Realism's ordering exactly — `EventBuses.registerModEventBus` →
`REGISTRATE.registerEventListeners(bus)` → `init()` → `commonSetup()` on forge; `init()` →
`commonSetup()` → `REGISTRATE.register()` → config → `serverInit()` on fabric.

`DispatcherMod.commonSetup()`: `DNetworking.register(); AllDispatcherItems.register(); AllMenuTypes.register(); WebBootstrap.init();`
(no `RExtras` — `TimeOfDayRealistic` stayed behind).

### Realism as an optional integration

`ScheduleCompiler` dispatches conditions purely by NBT id string, so keep the
`case "realism:time_of_day_realistic"` arm exactly as-is. Realism becomes just another optional mod
alongside CRN, Tramways and Steam 'n' Rails — no dependency, no `isModLoaded` guard needed, the arm is
simply never hit when Realism is absent.

---

## 6. Execution order

**D0 — Archive first.** 41 web files, 4 preset packets, `HeadlessSimService`, 7 tests, the entire
`web/` frontend and `SPEC-WEB.md` are **untracked**. Commit everything on `Advanced-Schedule` before
touching anything; that commit is the migration's source of truth and the branch's permanent archive.
Check `.gitignore` actually admits `web/dist/` (it has a `!web/dist/` un-ignore that has not taken
effect yet) and excludes `web/node_modules/`, `tmp/`, `.architectury-transformer/`, `.vite/`.

**D1 — Skeleton boots.** New repo, gradle files, entry classes, empty mixin config, empty
`DNetworking.register()`, placeholder icon. `./gradlew build` green; `:forge:runClient` reaches the main
menu with the mod listed. No feature code yet — proves the scaffolding before 17k lines land on it.

**D2 — Java lift.** Move the packages, run the naming sweep, split `ScheduleRuntimeMixin` and the config,
write `DNetworking.register()` in the order above. Gate: `./gradlew :common:test` — 30 tests green, and
`./gradlew :common:test --tests '*Benchmark*' -Dsim.benchmark=true` still prints digest
**`19a84b2d9cbfade6`**. A moved-and-renamed simulator that changes its digest has been broken by the move.

**D3 — Resources + frontend.** Lang subset (~145 keys, re-prefixed), item model/texture, `sim_debug.html`,
accesswidener, `architectury.common.json`, mod metadata. Frontend: copy `web/`, apply the CSRF header /
localStorage / package-name renames, `npm run build`, commit `dist/`. Gate: `./gradlew build` green
including `verifyWebDist`.

**D4 — Runtime verification** (the `/verify` skill workflow — see §7).

**D5 — Release + retire.** Modrinth/CurseForge projects, CHANGELOG, README, `CLAUDE.md` for the new repo,
`docs/web-interface.md` updated for the new paths and command. Then on Realism: leave `1.20.1-C6`
untouched, and mark `Advanced-Schedule` archived in the README so it is never merged forward.

---

## 7. Verification

Nothing here is provable by inspection — the simulator's correctness lives in its tests and its field
behavior.

**Automated (fast loop):**
```bash
./gradlew :common:test                                              # 30 JUnit tests, MC-free
./gradlew :common:test --tests '*Benchmark*' -Dsim.benchmark=true   # digest must read 19a84b2d9cbfade6
./gradlew build                                                     # includes verifyWebDist
cd web && npm run build && npx svelte-check                         # 0 errors, then commit dist/
```

**In-game (per the `/verify` skill — forge first, wait for the user's confirmation, then fabric):**
`./gradlew :forge:runClient` → right-click a track with the Advanced Schedule item (map opens focused on
that edge), right-click elsewhere (editor opens), CRN "Configure" buttons still open and persist through
the save path, conductor hand-off returns an *Advanced Schedule* item and not a vanilla one, station
auto-schedule accepts the item, Simulate produces conflicts + diagram + times on cards, Presets save/load
round-trips. Then `./gradlew :fabric:runClient` for the same pass.

**Dedicated server + web** (`runServer` + curl, the W0–W6 pattern):
zero `NoClassDefFoundError` mentioning `net.Dispatcher` (the server-only rule — `net.Dispatcher.web`
must never touch a client class), `WebEnabled=false` → no bind, `/` serves `dist` from the jar,
401/403/CSRF/tier gating, `/api/events` SSE cadence, `/dispatcher web session` login link,
`secrets.json` created at 600 under the **new** path, and the legacy-path migration copying an existing
`config/realism-web/` + `<world>/realism/` on first start.

**Field pass** on the 1949-graph server once the jar is built: graph rebuild storm behavior unchanged,
`/api/corridor/*` and `/api/sims/*` under load, plan overlay + per-train calibration warming after
~30 min, a deploy applying and firing `deployed` to a second browser.

**Compat regression** (the one that catches a bad NBT round-trip): a schedule authored in Dispatcher's
editor must load identically in Create's vanilla editor, and — new to this migration — a world that had
Realism's advanced-schedule items in chests must be checked: the item id changes from
`realism:advanced_schedule` to `createdispatcher:advanced_schedule`, so existing stacks will vanish on
load. Decide before release whether to accept that (document it) or ship a small item-id remap; the
schedule NBT itself is Create's and survives either way.

---

## 8. Known risks

1. **Item id break.** Existing `realism:advanced_schedule` stacks disappear. Unavoidable with a rename;
   only the release note or a remap fixes it. Flag this to the user before D5.
2. **Two mods mixin `ScheduleRuntime`.** Fine in principle (distinct `@Unique` prefixes, distinct
   injection points), but it is the one place the two mods can collide at runtime — test with both
   installed, on both loaders.
3. **`verifyWebDist` digest recipe is duplicated** between `common/build.gradle` (Groovy) and
   `web/vite.config.ts` (TS). Carry both across together or the check fails on arrival.
4. **CRN/Tramways/DragonLib pins are Modrinth version ids**, not versions (`Byo7nLl9`, `r5q4MIYy`,
   `uz4tlLzk`, `RWduTzyi`). Copy them exactly; they are not resolvable by name.
5. **Untracked work is the whole feature.** D0 is not optional bookkeeping — a lost `web/` directory is
   7k lines of Java and 10k of TypeScript with no backup.
