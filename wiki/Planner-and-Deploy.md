# Planner and Deploy

The planner is the second view of the [web interface](Web-Interface). It answers "what happens if I
run *this* timetable?" and then, if you like the answer, applies it.

You need the **planner** tier to plan and simulate, and the **deployer** tier to deploy.

## 1. Presets

A preset is a saved Create schedule. The library is shared with the in-game Presets window — the
same files under `<world>/createdispatcher/presets/`.

- **Create** one from a train that already has a schedule (its current schedule is copied), or
  **duplicate** an existing preset.
- **Rename**, **delete**, and file into **folders** (1–40 characters per level, up to 4 levels).
- **Edit values in place** — the destination of an entry, a condition's number, and so on. The
  editor only accepts edits from a whitelist of schedule fields, and the *same* whitelist is applied
  to simulation and to deploy, so you can never deploy an edit the simulator would have refused.

Trains can be filed into folders too (`<world>/createdispatcher/train-folders.json`), which is how
you keep a hundred-train network navigable. Viewers can see the filing; planners can change it.

## 2. Assignment

Drag presets onto trains. Each train may carry at most one assignment. Two more per-run switches
affect the simulation only:

- **remove** — simulate the network *without* this train;
- **keep** — keep this train exactly as it is, rather than letting the run's "remove scheduled
  trains" default apply.

Neither ever touches the world. Deploy never removes a train from the network.

Assignments can be saved as a **plan** (`<world>/createdispatcher/plans/`) and reloaded later. Plans
are shared: any planner may edit any plan, and the author of record stays whoever created it.

## 3. Simulation

Submitting a run queues a headless simulation on a worker thread. Parameters:

| Parameter | Default | Notes |
|---|---|---|
| start time | now | any in-game day time |
| horizon | 12 in-game hours | capped by `Web Sim Max Horizon Hours` (48) |
| headway | `Sim Headway Seconds` | per-run override |
| remove scheduled | on | trains with a schedule but no assignment are taken off the simulated network |

Queue behaviour is governed by `Web Sim Max Queued` (4), `Web Sim Cooldown Seconds` (15) and
`Web Sim Wall Cap Seconds` (0 = uncapped and fully reproducible; a nonzero cap marks results
truncated). Finished results are cached within `Web Sim Cache MB`.

The result gives you map playback of the whole run, the conflict list, the root-cause panel, and
corridor diagrams for any A→B pair — the same analysis as the in-game simulation, described in
[Simulation and Conflicts](Simulation-and-Conflicts).

## 4. Deploy

Deploy is the only part of the site that changes the running world, and it is deliberately explicit.

Two modes:

- **Safe (idle only)** — the default. A train is changed only if it is standing still with no
  destination: paused, finished, dwelling at a station, or scheduleless. Anything mid-trip is
  skipped and reported. An unknown or missing mode value falls back to this, so a malformed request
  can never interrupt a running train.
- **Immediate** — the swap happens regardless; a running train cancels its current trip and reroutes
  from the new schedule.

Per train, deploy strips the preset's saved progress (so the schedule starts at its first entry),
cancels navigation, installs the schedule, and marks the train as carrying an Advanced Schedule — so
a conductor removing it hands back an Advanced Schedule item.

Every attempt, successful or not, is appended to `<world>/createdispatcher/web-audit.jsonl` and is
readable from the dialog's *recent deploys* view or `GET /api/audit`. Deploy is rate-limited to 10
requests per minute.

If a schedule uses instructions the simulator cannot model, deploy still goes through — the result
row says so, and it only means the projections will not represent those steps.

Schedules deployed from the web are ordinary Create schedules, so a player without this mod
installed can still read them at a station.
