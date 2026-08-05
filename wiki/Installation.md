# Installation

## Requirements

| | |
|---|---|
| Minecraft | 1.20.1 |
| Loader | Forge 47.3.3+ **or** Fabric (loader 0.17.2+, Fabric API 0.92.2+) |
| Create | 6.0.8 (Forge `6.0.8-289` / Fabric `6.0.8.1+build.1744`) |
| Architectury API | 9.2.14+ |
| DragonLib | required — a **separate download on Forge**; bundled inside the Fabric jar |
| Java | 17+ |

On Fabric the config file is provided by **Forge Config API Port**, which Create Fabric already
requires, so you will normally have it. Create Tramways is recommended but optional.

## Which jar

Each build produces four jars:

| Jar | Use it when |
|---|---|
| `createdispatcher-<version>-forge.jar` | normal Forge install (client **and** server) |
| `createdispatcher-<version>-fabric.jar` | normal Fabric install (client **and** server) |
| `createdispatcher-<version>-forge-server.jar` | Forge server whose players have **no** Dispatcher install |
| `createdispatcher-<version>-fabric-server.jar` | same, on Fabric |

The normal jar must be installed on **both** the server and every client — it registers an item, and
a client missing a registry entry the server has is kicked during login. If you only want the web
interface and do not want to make players install anything, use the `-server` jar; see
[Server-Only Mode](Server-Only-Mode).

## Getting the Advanced Schedule item

The item is `createdispatcher:advanced_schedule` ("Advanced Train Schedule"). It currently has **no
crafting recipe and no creative-tab entry**, so give yourself one:

```
/give @s createdispatcher:advanced_schedule
```

It is a full replacement for Create's own schedule item — an existing schedule's NBT is preserved
when a conductor hands one back, so you can move between the two freely.

## Optional integrations

These are detected at runtime; none of them is a dependency, and nothing breaks when they are
absent.

| Mod | What the simulator understands |
|---|---|
| **Create Railways Navigator** (CRN) | train separation, travel sections, prioritized destinations, station tags |
| **Create Tramways** | speed-sign zones, modelled the way Tramways applies them — a limit is carried on the train through junctions, with anticipatory braking toward lower limits |
| **Steam 'n' Rails** | waypoints, redstone links |
| **Create Realism** | its `time_of_day_realistic` wait condition, and its reduced train acceleration |

## First run

1. Start the server (or a single-player world) once. It writes `config/createdispatcher-common.toml`.
2. The in-game half works immediately — no configuration needed.
3. The web interface is **off by default**. To turn it on, see [Web Interface](Web-Interface).

## Upgrading from Create Realism's Advanced-Schedule builds

Data is migrated automatically on first start, copied (never moved) and only when the new location
does not exist yet:

- `config/realism-web/` → `config/createdispatcher/`
- `<world>/realism/` → `<world>/createdispatcher/`

Two things do **not** carry over:

- **Loose `realism:advanced_schedule` items disappear**, because the item now belongs to this mod.
  Schedules already installed on trains are unaffected — only items lying in inventories and chests.
  `/give` replacements.
- The config file is now `createdispatcher-common.toml`, and the commands moved from `/realism web`
  to `/dispatcher web`.
