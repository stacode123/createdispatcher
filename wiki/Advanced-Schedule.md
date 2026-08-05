# The Advanced Schedule item

`createdispatcher:advanced_schedule` — "Advanced Train Schedule". It behaves exactly like Create's
schedule item everywhere Create uses one, and adds three things: **Simulate**, **Presets**, and a
**map view**.

Get one with `/give @s createdispatcher:advanced_schedule` (there is no recipe yet).

## What it does everywhere a Create schedule does

| Action | Result |
|---|---|
| Right-click in the air / use the item | opens the schedule editor |
| Right-click a **conductor** | installs the schedule on that train, and remembers it was an advanced one |
| Conductor hands the schedule back | you get an **Advanced** Schedule back, with the same NBT |
| Put it in a **station's** auto-schedule slot | accepted, and applied to arriving trains like a normal schedule |
| Create Railways Navigator's *Configure* windows | work normally (train separation, travel section, prioritized destination) |
| Right-click a **track** | opens the network map (see below) — sneak to suppress this |

Because it is a real subclass of Create's `ScheduleItem` and the editor is a real subclass of
Create's `ScheduleScreen`, addon instructions and conditions render and configure exactly as they do
on a vanilla schedule.

A schedule installed by an ordinary Create schedule item stays ordinary — the "advanced" marker is
per-train and is set when *this* item applies the schedule (or when the web planner deploys one).

## The editor

Same editor as Create's, plus two buttons:

- **Simulate** — opens the simulation setup window. See
  [Simulation and Conflicts](Simulation-and-Conflicts).
- **Presets** — opens the server-side preset library.

## Presets

The preset library is stored on the **server**, under `<world>/createdispatcher/presets/`, and is
shared by everyone: the in-game window and the web planner read and write the same library.

In the Presets window:

- type a name and press **Save** to store the schedule currently held in the editor;
- pick an entry and press **Load** to write it into the held schedule (reopen the editor to edit it);
- the search box filters by name.

Rules the server enforces: names are 1–60 characters, the schedule may not be empty, folders are
1–40 characters per level and at most 4 levels deep, and the library is capped by
`Web Preset Max Count` (500 by default). Folders are created and used mainly from the web planner —
see [Planner and Deploy](Planner-and-Deploy).

## The map view

Right-click any Create track with the item (without sneaking) to open a 2D map of **that** rail
network.

- Drag to pan, scroll to zoom, hover a track for details.
- Hovering shows section length, advised curve speed and the slowest curve on the section, the
  signal at the entry (block or chain, and which direction it faces), the station on that section,
  level crossings, and Tramways speed-sign limits.
- Grey lines are *other*, separate networks — click their track in-world to get their map.
- After a simulation, conflicts are badged onto the map.

A network larger than `Graph Node Cap` (4000 track nodes by default) is refused with
"Rail network too large to map"; raise the cap if you need it, at the cost of memory and
translation time.

## Related pages

- [Simulation and Conflicts](Simulation-and-Conflicts) — what Simulate does and how to read it
- [Configuration](Configuration) — the `Advanced Schedule` config section
- [Web Interface](Web-Interface) — the same features, in a browser, for the whole network at once
