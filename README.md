# Create Dispatcher

**Minecraft 1.20.1 addon for [Create](https://modrinth.com/mod/create) that turns train schedules into
a plannable timetable.** Forge and Fabric. Author: Stacode · MIT.

Two halves:

**In-game — the Advanced Schedule item.** A drop-in replacement for Create's schedule (conductors,
station auto-schedule and Create Railways Navigator options all work) that adds:

- **Simulate** — projects your schedule against every train on the network with a deterministic,
  Minecraft-free engine that mirrors Create's movement, signalling and `ScheduleRuntime` semantics.
- **Conflicts and root causes** — section contention, deadlocks, headway violations and platform
  clashes, each traced back to the train that actually caused it.
- **Time–distance diagram** and simulated arrival/departure times drawn on the schedule cards.
- **Map** — right-click a track with the item to open a 2D view of that network, with conflict badges.

**On the server — an embedded web interface** (off by default) at `Web Enabled` in the config:

- **Live map** of every network with real-time train positions, plus notifications for deadlocks,
  extreme signal waits and detours — with replays of what led up to them.
- **Corridor diagrams** overlaying what trains actually did against what the simulator predicted,
  self-calibrating as it learns each train's drift.
- **Planner** — a preset library, drag-and-drop assignment of schedules to trains, headless
  simulation of the result, and **Deploy** to apply it to the real trains.
- Discord OAuth or one-time login links, with `viewer / planner / deployer` tiers.

Zero new runtime dependencies: the JDK's own HTTP server, SSE, and a hand-rolled Svelte frontend
committed as a build artifact so Gradle and CI stay Node-free.

## Docs

- `docs/web-interface.md` — enabling the web server, login, TLS, deploy semantics, full config table
- `SPEC.md` — the Advanced Schedule design and its milestone history
- `SPEC-WEB.md` — the web interface design and its milestone history
- `SIM_DIVERGENCES.md` — where the simulator knowingly differs from Create at runtime

## Building

```bash
./gradlew build                # both loaders -> forge/build/libs, fabric/build/libs
./gradlew :forge:runClient     # dev client
./gradlew :common:test         # the simulator's JUnit suite (no Minecraft)
./gradlew :common:test --tests '*Benchmark*' -Dsim.benchmark=true   # opt-in perf benchmark
```

Java 21 to run Gradle, Java 17 bytecode. The Gradle daemon is disabled, so every invocation
cold-starts — allow a couple of minutes.

Frontend (only needed when you change `web/src`):

```bash
cd web && npm install && npm run build   # writes web/dist — commit it
```

`:common:verifyWebDist` recomputes the source digest recorded in `web/dist/.buildinfo.json` and fails
the build under CI if `dist/` is stale.

## Relationship to Create Realism

This started life on a branch of [Create Realism](https://github.com/stacode123/CreateRealism) and was
split out in August 2026. The two mods are independent — install either, or both. When Realism is
present its reduced acceleration is picked up automatically, because the simulator snapshots each
train's live acceleration; set `Sim Acceleration Multiplier` to match Realism's own multiplier so
phantom trains accelerate like the real ones. Realism's `time_of_day_realistic` wait condition is
understood by the simulator when that mod is installed.

## Optional integrations

Create Railways Navigator (train separation, travel sections, prioritized destinations, station tags),
Create Tramways (speed sign zones, modelled the way Tramways actually applies them — carried on the
train through junctions), Steam 'n' Rails (waypoints, redstone links).
