# Create Dispatcher 1.0.0

First release. Create Dispatcher is the Advanced Schedule and web interface work that grew inside
Create Realism, now its own mod — Realism stays a client-side realism mod, Dispatcher is the
server-side timetable tool. They are independent; install either or both.

## In-game

- **Advanced Schedule item** — a full replacement for Create's schedule item. Conductors, station
  auto-schedule and Create Railways Navigator's configure windows all work, and a conductor hands the
  advanced item back rather than a vanilla one.
- **Simulation** — a deterministic, Minecraft-free engine projects the edited schedule against the
  whole network: Create's bang-bang movement, signal sections and chain reservations, station dwells,
  and `ScheduleRuntime` condition semantics including CRN separation gates seeded from CRN's own
  departure history. Tramways speed-sign zones ride the train through junctions the way Tramways
  applies them, with anticipatory braking toward lower limits.
- **Conflicts and root causes** — section contention, deadlocks (Tarjan SCC over the wait-for graph),
  headway violations and platform clashes; every end-of-run signal wait is walked back through
  first-holder links to the train that is not waiting on anyone, and classified (no matching station,
  no path, parked, obstacle, schedule hold, deadlock).
- **Presentation** — time–distance diagram over the phantom's route corridor, simulated times on the
  schedule cards, conflict badges on the map, and an optional self-contained HTML playback export.
- **Map viewer** — right-click a track with the item for a 2D view of that network.
- **Preset library** — save and load schedules to a server-side library from the editor.

## Web interface (server-side, off by default)

- Live map of every network with real-time positions over a single SSE stream.
- Notifications for signal waits, deadlocks and detours — detours name their cause and carry both
  routes for overlay — with replays capturing the lead-up and aftermath.
- Corridor diagrams between any two stations: observed history against the simulated plan, with
  per-train drift calibration and uncertainty bands that persist across restarts.
- Planner: preset library with folders, drag-and-drop assignment, headless simulation with playback,
  conflict and root-cause panels, saved timetables, and Deploy (immediate or idle-only) with an audit
  journal.
- Discord OAuth or `/dispatcher web session` one-time links; `viewer / planner / deployer` tiers;
  rate limits and a startup config check.

## Migrating from Create Realism's Advanced-Schedule builds

- Data is carried over automatically on first start: `config/realism-web/` →
  `config/createdispatcher/` and `<world>/realism/` → `<world>/createdispatcher/` (copied, not moved,
  and only when the new location does not exist yet).
- **Existing `realism:advanced_schedule` items are lost** — the item now belongs to this mod
  (`createdispatcher:advanced_schedule`). Schedules already installed on trains are unaffected; only
  loose items in inventories and chests disappear. Craft or `/give` replacements.
- The config moved to `createdispatcher-common.toml`; commands moved from `/realism web` to
  `/dispatcher web`.
