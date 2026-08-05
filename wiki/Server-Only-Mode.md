# Server-only mode

**Run the web interface on a server whose players have not installed anything.**

The web half of Create Dispatcher is entirely server-side, but the mod also registers one item — and
a client missing a registry entry the server has is **disconnected during login** ("Failed to
synchronize registry data from server"). So one item would force every player to download the mod.

Server-only mode registers nothing. A plain Forge/Fabric + Create client joins normally.

## Turning it on

Any one of these, checked in this order:

1. **Use the `-server` jar** — `createdispatcher-<version>-forge-server.jar` or
   `…-fabric-server.jar`. Same code, with a marker baked in. This is the intended way to ship it.
2. **`-Dcreatedispatcher.serverOnly=true`** on the server's JVM command line. Wins both ways, and is
   handy for testing.
3. **An empty `config/createdispatcher/server-only.marker`** file — flips the normal jar in place
   without re-downloading anything.

Either way the server logs one line at startup:

```
Server-only mode (config/createdispatcher/server-only.marker): no item or menu registered, clients do not need Create Dispatcher
```

It is deliberately **not** a config option: on Forge the config is registered after the mod's setup
runs, which is too late to decide whether to register an item.

## What you keep and what you lose

**Keeps working:** the whole web interface, the planner, the headless simulator, conflict and
root-cause analysis, deploy, live sampling, presets, notifications and replays, and every
`/dispatcher web …` command. Conductors and stations keep working with Create's own schedule item,
because the widening mixins gate on "is a schedule item", not on a specific registry entry.

**Gone:** the in-game half — the Advanced Schedule item and its editor, the map screen, the
simulation window, the preset GUI. Each of those is a client→server flow a mod-less client would
never start. A conductor hands back a plain Create schedule.

Schedules deployed from the web are **ordinary Create schedules**, so a player with no Dispatcher
install can still read them at a station. The one caveat: if a schedule uses a condition belonging to
an optional integration (say `realism:time_of_day_realistic`), the client needs *that* mod — exactly
as it would without Dispatcher.

## The rule this depends on

**The server's registry must stay a subset of its clients'.** That is not specific to this mod: any
*other* content-adding mod you run server-side reintroduces the same problem. DragonLib is the
tolerated exception here — clients running Create addons have it anyway — and the `-server` jars keep
it as a dependency even though only the client GUI uses it.

## Switching a world that previously ran the full jar

The world keeps `createdispatcher:advanced_schedule` in its registry mapping. Forge logs
`Unidentified mapping from registry minecraft:item` once and continues; loose Advanced Schedule item
stacks disappear. Schedules already installed on trains are unaffected.

## For maintainers

The build detail — why `serverJar` is a `Zip` task rather than a `Jar`, and why only Forge gets a
metadata swap — is in `plans/server-only-build.md` in the repository, and summarised in
[Developer Guide](Developer-Guide).
