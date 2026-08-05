# Simulation and conflicts

Pressing **Simulate** in the schedule editor projects the schedule you are editing against every
train currently on that rail network, and tells you what would happen.

The engine is deterministic: sorted train order, a fixed tick step, no wall clock, no random
numbers. The same network state and the same schedule always produce the same result — which is what
makes the conflicts worth arguing about.

## What is simulated

The simulator mirrors Create 6.0.8:

- bang-bang acceleration/braking (`v²/2a`), so braking distance is Create's braking distance;
- per-tick section reservations, atomic chain-signal reservations, and persistent
  first-come-first-served claims;
- station dwells, and `ScheduleRuntime`'s condition semantics — including CRN separation gates,
  seeded from CRN's own departure history;
- Tramways speed-sign zones, carried on the train through junctions with anticipatory braking.

Your edited schedule is driven by a **phantom train** — a copy of the train the schedule would run
on. Everything else on the network is snapshotted from the live world and driven by its own
schedule.

Known, deliberate differences from what Create does at runtime are listed in `SIM_DIVERGENCES.md` in
the repository.

## Setup window

| Option | Meaning |
|---|---|
| **Carriages** / **Locomotives** | the phantom train's make-up (drives its acceleration and length) |
| **Acceleration** | `None`, `Standard` (applies `Sim Acceleration Multiplier`), or a custom value |
| **Start time** | `Now`, or a specific in-game hour/minute |
| **Duration** | how many in-game hours to simulate (1000 ticks per hour), capped by `Sim Max Horizon Hours` |
| **Headway alert** | minimum gap between consecutive trains through a section before it counts as a headway conflict; `Default` uses `Sim Headway Seconds` |
| **Impact analysis** | costs 2× compute and runs the network twice, then reports **only** the conflicts your schedule introduces — pre-existing network problems are hidden |

Simulations are rate-limited per player (`Sim Cooldown Seconds`), capped globally
(`Sim Max Concurrent`) and time-boxed (`Sim Max Wall Seconds`); a run that hits the time box is
marked *partial*. Runs happen on a worker thread, so the server keeps ticking; if you close the
window, a chat message tells you when the result is ready.

## Reading the results

**Your train** — the stops the phantom reached, with simulated arrival and departure times, and its
state at the end of the window (driving, waiting at a station, preparing to depart, parked,
obstacle).

**Other trains** — the same, for everything else on the network.

**Not simulated** — trains that were excluded, and why: position could not be mapped onto the
network, derailed, no schedule (manual train), schedule completed, schedule paused. An excluded
train still sits on the track: it is treated as a stationary obstacle.

**Notes** — anything the simulator had to assume or could not model, e.g. an unsupported
instruction, an unpredictable wait condition, "request stops are assumed to always be requested",
"station-loaded checks assume the chunk is loaded", or "anchored to live snapshot — depends on
current network state".

**Conflicts** — four kinds:

| Kind | Raised when |
|---|---|
| **Signal wait** (section) | a train waits at a red signal longer than `Sim Wait Conflict Seconds` |
| **Deadlock** | a cycle in the wait-for graph (Tarjan SCC) — trains waiting on each other forever |
| **Headway** | two trains pass through the same section closer together than the headway threshold |
| **Platform** | two trains want the same platform at the same time |

Each conflict carries its time window, the trains involved, a position, and a flag when it is
non-deterministic (i.e. it depends on the current live network state rather than on your schedule).

**Root causes** — the useful part. Every signal wait at the end of the run is walked back through
first-holder links until it reaches a train that is not waiting for anyone, and that train is named
and classified:

| Classification | Meaning |
|---|---|
| `no station matches "X"` | the schedule names a station that does not exist (`*` is the only wildcard) |
| `no route to "X"` | no path — check one-way signals and reversing room |
| `finished its schedule and parked on the track` | it is done and now it is scenery |
| `standing on the track without a schedule` | a manual train left in the way |
| `held at X by its schedule` | a wait condition that will not clear |
| `deadlocked with …` | it is in a deadlock cycle itself |
| `stopped at a signal` | cause could not be narrowed further |

Each root cause lists how many trains are stuck behind it and since when. Fixing the root cause
usually removes a whole page of conflicts.

## Diagram and map

From the results window:

- **Diagram** — a time–distance diagram over the phantom's route corridor. Green is your train,
  diamonds are conflicts. Drag to pan, scroll to zoom, hover a line for details. Trains whose CRN
  category matches `Sim Diagram Hidden Categories` are left out.
- **Map** — the network map with conflict badges on the sections where they happened.

## Debug export

With `Sim Debug Export` on, every simulation also writes a self-contained HTML playback viewer to
`dispatcher-sim-debug.html` in the server (or save) directory. It is a debugging tool — leave it off
in normal use.
