# Troubleshooting

## In-game

**"No rail network found near this track."** You right-clicked a block that is not part of a
translated Create track graph, or the network has no nodes yet. Place/relink a track and try again.

**"Rail network too large to map (N nodes, cap M)."** The network exceeds `Graph Node Cap` (4000 by
default). Raise it in the config — at the cost of memory and translation time — or use the web map,
which has its own, much larger cap (`Web Graph Node Cap`).

**Simulate is greyed out or refuses.** The refusal message says which one it is:

| Message | Fix |
|---|---|
| "Hold the Advanced Schedule in your main hand." | it must be in the main hand |
| "The schedule is empty." / "has no destination entry" | add at least one destination |
| "Please wait Ns before simulating again." | `Sim Cooldown Seconds` |
| "Your previous simulation is still running." | wait, or let it finish — the result arrives in chat |
| "Too many simulations are running" | `Sim Max Concurrent` across all players |
| `No station matches "X"` | check the name; `*` is the only wildcard |
| "The rail network exceeds the configured node cap." | `Graph Node Cap` |
| "Simulation failed — see the server log." | a real error; the stack trace is server-side |

**Results say "Time budget exceeded — results are partial."** The run hit `Sim Max Wall Seconds`.
Shorten the horizon or raise the budget.

**Simulated times do not match what the trains actually do.** The usual causes, in order: Create
Realism installed without `Sim Acceleration Multiplier` matched to its own multiplier; an
instruction or condition listed under *Notes* that the simulator could not model; or a genuinely
non-deterministic conflict (those are flagged). Known, deliberate differences are catalogued in
`SIM_DIVERGENCES.md`.

**A conductor handed back a plain Create schedule.** The train's schedule was installed by something
other than an Advanced Schedule item — or the server runs in [server-only mode](Server-Only-Mode),
where no advanced item exists to hand back.

## Web interface

**Nothing is listening on the port.** `Web Enabled` is false, or the bind failed — look for
`Dispatcher web interface failed to start` in the log. Port already in use is the usual cause.

**"Not allowlisted" after a Discord login.** Expected until an admin runs
`/dispatcher web allow <your id> viewer`. The page shows the id to add.

**Discord login says `discord_not_configured`.** `secrets.json` has no client id/secret, or
`Web Public Url` is empty. Use a `/dispatcher web session <tier>` link in the meantime.

**Logged in, but everything 403s.** Your tier is below what that page needs. `GET /api/me` shows the
tier the server resolved for you; `/dispatcher web list` shows what the allowlist says.

**Mutations fail with `403 csrf` or `403 bad_origin`.** A script must send an `X-Dispatcher-Csrf`
header on every non-`GET` request, and any `Origin` it sends must equal `Web Public Url`.

**The map loads but never updates.** The SSE stream is being buffered by a proxy — see the nginx
snippet in [Web Interface](Web-Interface) §5. The page reconnects on its own after 25 s of silence,
so a map that constantly reloads means the stream is dying, not that events stopped.

**`503 sse_full`.** More than `Web Max Sse Clients` browsers are connected.

**A network is missing from the map.** It is over `Web Graph Node Cap`, or it has not been translated
yet. `/dispatcher web refresh` forces a rebuild on the next poll.

**Corridor diagrams have no solid (actual) lines.** `Web History Hours` is `0`, or history was
cleared by a graph rebuild — it refills at `Web History Sample Seconds`.

**A plan overlay says `pending`.** The background projection is still running; it appears within a
minute. With `Web Background Projections` off it only computes while a browser is asking.

**`409 graph_changed` / "re-run it".** The network was rebuilt after the run you are looking at.
Re-run the simulation; the pinning is there so you are never shown a diagram computed against a
different track layout.

**Deploy skipped most of my trains.** That is *Safe (idle only)* mode doing its job — a train
mid-trip is never interrupted. Use *Immediate* if you mean it.

## Server startup

**A client is kicked with "Failed to synchronize registry data from server".** The client does not
have Create Dispatcher installed while the server registers its item. Either install the mod on the
client, or run the server in [server-only mode](Server-Only-Mode).

**`NoClassDefFoundError` naming `net.Dispatcher` on a dedicated server.** That is a bug — the web
layer and packet classes must never touch a client class. Please report it with the full stack
trace.

**Fabric server complains about the config.** Fabric needs **Forge Config API Port**; without it
there is no `createdispatcher-common.toml`.
