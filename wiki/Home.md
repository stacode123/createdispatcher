# Create Dispatcher

**A Minecraft 1.20.1 [Create](https://modrinth.com/mod/create) addon that turns train schedules into
a plannable timetable.** Forge and Fabric. Author: Stacode · MIT · mod id `createdispatcher`.

The mod has two halves, and you can run either one on its own.

**In-game — the Advanced Schedule item.** A drop-in replacement for Create's schedule item
(conductors, station auto-schedule and Create Railways Navigator's option windows all keep working)
that adds a **Simulate** button: a deterministic, Minecraft-free engine projects your schedule
against every other train on the network, reports conflicts and the trains that actually caused
them, and draws a time–distance diagram and predicted arrival/departure times.

**On the server — an embedded web interface** (off by default). A live map of every rail network,
notifications with replays, corridor diagrams comparing what trains did against what the simulator
predicted, and a planner where you assign schedule presets to trains, simulate the result and
**Deploy** it to the real trains.

## Start here

| If you are… | Read |
|---|---|
| installing the mod | [Installation](Installation) |
| a player using the item | [Advanced Schedule](Advanced-Schedule) → [Simulation and Conflicts](Simulation-and-Conflicts) |
| a server admin | [Web Interface](Web-Interface), [Commands](Commands), [Configuration](Configuration) |
| running a vanilla-client server | [Server-Only Mode](Server-Only-Mode) |
| writing a script or a client against it | [HTTP API](HTTP-API) |
| building or contributing | [Developer Guide](Developer-Guide) |
| stuck | [Troubleshooting](Troubleshooting) |

## What it is not

- It does not change how trains drive. Nothing in the simulator touches the running world; the only
  feature that writes to it is **Deploy**, which installs schedules on trains you pick.
- It is not a client mod. All the interesting work happens on the server; the client only draws
  screens. Nothing about the web interface is reachable until you turn it on in the config.
- It adds no runtime dependencies for the web half — the JDK's own HTTP server, server-sent events,
  and a frontend shipped inside the jar.

## Relationship to Create Realism

Create Dispatcher started as the `Advanced-Schedule` branch of
[Create Realism](https://github.com/stacode123/CreateRealism) and was split out into its own mod in
August 2026. The two are independent — install either, or both. When Realism is present the
simulator picks up its reduced acceleration automatically (it snapshots each train's live
acceleration); set `Sim Acceleration Multiplier` to match Realism's own multiplier so phantom trains
accelerate like the real ones. Realism's `time_of_day_realistic` wait condition is understood when
that mod is installed.
