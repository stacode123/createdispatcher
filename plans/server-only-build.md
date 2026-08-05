# Server-only build variant

**Status: implemented.** Both `-server` jars build and a dedicated server starts in server-only mode.
Outstanding: the real test — a mod-less client joining a real server — see Verification below.

## Context

The web interface is the reason to run Dispatcher on a server, and it is entirely server-side. But
the mod registers one item, and a client missing a registry entry the server has is **disconnected
during login** — Forge: `HandshakeHandler.handleRegistryLoading` → `GameData.injectSnapshot` reports
missing entries → `disconnect("Failed to synchronize registry data from server, closing
connection")`; Fabric: `RegistrySyncManager.checkRemoteRemap` → `RemapException`. So one item forces
every player on the server to download the mod.

Goal: a build that registers nothing, so a Forge/Fabric + Create client with no Dispatcher installed
joins normally while the server keeps the web interface and the `/dispatcher web …` commands.

## What blocks a mod-less client (and what does not)

| Thing | Blocks? | Why |
|---|---|---|
| `createdispatcher:advanced_schedule` item + menu type | **Yes — the only blocker** | registry sync, both loaders (above) |
| DragonLib on the server | In principle, yes — in practice no | it registers `dragonlib:dragon`, so a client without it would hit the same disconnect. Left alone by decision: clients running Create addons have DragonLib anyway. Worth remembering as the general rule — the server's registry must stay a subset of its clients'. |
| `mods.toml` without `displayTest` | No — cosmetic | FML's join check rejects only on mismatched channels, never on the mod list. Default `MATCH_VERSION` just paints a red X in the server list. Fixed anyway. |
| `createdispatcher:net` channel | No | `DNetworkingImpl` already uses `clientAcceptedVersions((s) -> true)` / `serverAcceptedVersions((s) -> true)`, which accept Forge's `ABSENT` sentinel |
| `CheckVersionS2CPacket` on join | No | vanilla drops unknown custom payloads; the comparison runs client-side. `sendToAll`/`sendToNear` have no callers |
| Architectury API on the server | No | both channel predicates return true; it registers no content |
| Mixins | No | `dispatcherCommon.mixins.json` already keeps its two client targets in the `"client"` section |

## Design

One codebase, one compile, a repacked variant jar. No source-set split and no class stripping — the
`-server` jar ships the same classes, they are simply never loaded, exactly like the existing
`*Opener` pattern.

`AllDispatcherItems.ADVANCED_SCHEDULE` and `AllMenuTypes.ADVANCED_SCHEDULE` register in **static
field initialisers**, so "do not register" means "never class-initialise those two classes". Only two
sites reach them:

- `DispatcherMod.commonSetup()` — the two `register()` calls, now behind `ServerOnly.enabled()`.
  `commonSetup()` runs in the mod constructor (Forge) / `onInitialize` (Fabric), before registry
  events, so this is early enough.
- `ScheduleRuntimeMixin.dispatcher$returnAdvancedSchedule` — returns early in server-only mode, after
  clearing the marker. The marker *can* be set on such a server: `DeployService` sets it directly,
  with no item involved.

Everything else refers to `AdvancedScheduleItem` only through `instanceof`, which does not touch
either holder class.

### The flag — `common/.../foundation/ServerOnly.java`

`enabled()` resolves once into a `static final boolean`, in order:

1. `-Dcreatedispatcher.serverOnly=…` — explicit, wins both ways, for the dev loop;
2. the `/createdispatcher.server-only` classpath resource — what the `-server` jar bakes in;
3. `config/createdispatcher/server-only.marker` — lets an admin flip the normal jar in place.

Deliberately not a config value: `DispatcherConfig` is registered *after* `commonSetup()` on Forge.
It is a plain `Files.exists` on `Platform.getConfigFolder()`, with no import of `net.Dispatcher.web`,
so core does not gain a dependency on the web layer.

### The variant jars

`serverJar` is a **`Zip`** task, not `Jar`: `Jar` would regenerate the manifest and lose loom's
`MixinConfigs` attribute. It repacks `remapJar` and adds the shared marker from
`gradle/server-variant/`.

- Fabric: that is the whole of it — the normal jar plus the marker. Fabric has no mod-list join check
  to appease, so `fabric.mod.json` is left exactly as loom remapped it, which is also what keeps its
  `accessWidener` and nested-jar entries correct for free.
- Forge: additionally swaps in `src/serverVariant/META-INF/mods.toml`, a copy of the real one with
  `displayTest = "IGNORE_ALL_VERSION"` (otherwise FML paints the server red in the multiplayer list
  over a mod-list mismatch) and a `(server)` display name. It is expanded with the same property map
  as `processResources`, hoisted to `ext.modMetaProperties` so there is one definition — keep the two
  tomls in sync, the way the repo already duplicates the `web/dist` digest recipe. The nested
  mixinextras jar is untouched.

`build` depends on both, so CI's wholesale `*/build/libs/` upload captures them with no workflow
change. Wire them into `publishMods` as additional files on the same version when D5 enables
publishing.

## What survives / what is lost

Survives: the whole web interface, planner, headless simulator, conflict analysis, deploy, live
sampling, presets, replays, `/dispatcher web <status|refresh|reload|list|allow|deny|session>`, and
the `ScheduleRuntime` advanced marker with its deploy path.

Lost: the Advanced Schedule item and its editor, the rail map screen, the simulation window, the
preset library GUI — each a C2S flow a mod-less client would never start. Conductors and stations
keep working with Create's own schedule item (the widening mixins gate on `instanceof ScheduleItem`),
and a conductor hands back a plain Create schedule.

Dispatcher registers nothing into `Schedule.CONDITION_TYPES` / `INSTRUCTION_TYPES`, so schedules
deployed from the web are plain Create schedules a mod-less client can read at a station. The caveat:
if the planner emits a condition belonging to an optional integration (e.g.
`realism:time_of_day_realistic`), the client needs that mod — same as it would without Dispatcher.

## Operational rules

- The server's registry must stay a subset of its clients'. DragonLib is fine — clients running
  Create addons have it — but any *other* content-adding mod you add server-side reintroduces exactly
  the problem this variant solves.
- A world that previously ran the full jar keeps `createdispatcher:advanced_schedule` in its registry
  mapping. Forge logs `Unidentified mapping from registry minecraft:item` once and continues; loose
  item stacks disappear. Observed in the dev world, harmless.

## Verification

Done:

1. `./gradlew build` → both `-server` jars in `*/build/libs/`. Forge variant keeps `MixinConfigs` in
   its manifest and its nested mixinextras, and its mods.toml carries `displayTest`; Fabric variant
   keeps the access widener and the nested DragonLib. Neither normal jar contains the marker.
2. `:forge:runServer` with the marker in place → `Server-only mode (config/createdispatcher/
   server-only.marker): no item or menu registered, clients do not need Create Dispatcher`, no
   Registrate item registration, web interface up, no `NoClassDefFoundError`.
3. `:common:test` — 73 tests, no failures; the benchmark digest is still `19a84b2d9cbfade6`.

Only the config-file source has been exercised at runtime; the classpath marker the shipped jar
relies on is verified structurally (present at the jar root, where a module layer can read it without
an open package) and gets its real test below. `ServerOnly` tries both `Class.getResource` and the
class loader, since the two do not always agree under FML's module layer.

Still to do:

3. **The real test.** A 1.20.1 Forge server with Create + Architectury + DragonLib +
   `…-forge-server.jar`; join with a client that has Create and DragonLib but *not* Dispatcher.
   Expect a clean join and no "Failed to synchronize registry data". Then drive the web UI end to
   end: plan → simulate → deploy → watch the train run it.
4. The same on Fabric (`:fabric:runServer`, `…-fabric-server.jar`).
5. Regression on the normal jars per `/verify`: `:forge:runClient`, confirm the item, editor,
   simulation and presets are unchanged, then `:fabric:runClient`.

## Not done

The optional polish from the original plan — having the C2S handlers that need the item
(`AdvancedScheduleSavePacket`, `RequestSimulationPacket`, `Preset*Packet`) reply with a chat line
pointing at the web UI when a *client that does have* Dispatcher hits a server-only server. Today
those handlers return silently. Skipped to stay clear of in-flight edits in those files.
