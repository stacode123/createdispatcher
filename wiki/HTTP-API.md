# HTTP API

Everything the browser frontend does, it does over this API — there is nothing privileged it can
reach that a script cannot. It is plain JSON over the JDK's `com.sun.net.httpserver`, plus one
server-sent-events stream. No WebSocket.

Base URL is whatever the server binds to (`Web Bind Address`:`Web Port`, default
`http://127.0.0.1:8455`), or your reverse proxy's `Web Public Url`.

> This API is **not** versioned yet and is written for the bundled frontend. Treat it as unstable
> across mod versions.

## Authentication

Sessions are a signed cookie:

```
dispatcher_session=…; Path=/; HttpOnly; SameSite=Lax; Max-Age=<Web Session Hours>[; Secure]
```

`Secure` is set only when `Web Public Url` starts with `https://`. Two ways to get one:

- `GET /auth/token/<token>` — the one-time link minted by `/dispatcher web session <tier>`. Consumes
  the token, sets the cookie, redirects to `/`.
- `GET /auth/login` → Discord OAuth → `GET /auth/callback` — sets the cookie, redirects to `/`.

For scripting, the practical route is: mint a session link in-game, follow it once with a cookie
jar, then reuse the cookie.

```bash
# one-time link from /dispatcher web session deployer
curl -c jar.txt -L 'http://127.0.0.1:8455/auth/token/<token>'
curl -b jar.txt 'http://127.0.0.1:8455/api/me'
```

### Rules for every request

| Rule | Detail |
|---|---|
| No cookie / bad cookie | `401 {"error":"unauthorized"}` |
| Tier too low | `403 {"error":"forbidden","detail":"requires planner, you are viewer"}` |
| Any mutating method (not `GET`/`HEAD`/`OPTIONS`) | must send an `X-Dispatcher-Csrf` header — any value. Missing → `403 csrf` |
| Mutating request with an `Origin` header | must equal `Web Public Url` when that is set. Otherwise → `403 bad_origin` |

### Errors

Always the same envelope:

```json
{ "error": "graph_too_large", "detail": "120000 micro nodes over Web Graph Node Cap" }
```

Common keys: `unauthorized`, `forbidden`, `csrf`, `bad_origin`, `not_found`, `bad_body`,
`bad_graph`, `graph_too_large`, `graph_changed`, `rate_limited`, `server_not_ready`,
`method_not_allowed`, `internal_error`.

### Rate limits

Fixed, not configurable. Fixed 60-second windows, keyed by session (falling back to client IP,
respecting `X-Forwarded-For`):

| Bucket | Limit / minute | Applies to |
|---|---|---|
| auth | 20 (per IP) | `/auth/*` |
| graph, corridor, sim, audit, debug | 60 | the heavy read endpoints |
| write | 60 | mutations on presets, plans, folders |
| deploy | 10 | `POST /api/deploy` |

Over the limit → `429 {"error":"rate_limited"}`.

### Conventions

- Large payloads (graphs, replays, sim results) are stored gzipped and streamed as-is when you send
  `Accept-Encoding: gzip`; otherwise they are inflated for you.
- Graphs and corridor-actual responses carry an `ETag`; send `If-None-Match` to get `304`.
- Endpoints that take `?graph=<uuid>` also accept `?v=<version>` to pin the graph version — a
  mismatch is `409 graph_changed` instead of silently answering about a different network.

## Endpoints

Tier is the *minimum* required.

### Session and status

| Method | Path | Tier | Notes |
|---|---|---|---|
| `GET` | `/api/me` | any session | `{discordId, username, tier}` |
| `GET` | `/api/status` | viewer | mod version, uptime, train count, SSE client count, active notifications, and the limits the UI needs |
| `POST` | `/auth/logout` | — | requires `X-Dispatcher-Csrf`; clears the cookie, `204` |

### Live network

| Method | Path | Tier | Notes |
|---|---|---|---|
| `GET` | `/api/graphs` | viewer | index of rail networks: id, version, node/edge/station counts, dimensions, bounding boxes, `tooLarge` |
| `GET` | `/api/graphs/<uuid>` | viewer | the full graph payload. `ETag`; `409 graph_too_large` over `Web Graph Node Cap` |
| `GET` | `/api/trains` | viewer | the train roster and its `rosterVersion` |
| `GET` | `/api/live/positions` | viewer | the latest position frame for every train |
| `GET` | `/api/stations?graph=<uuid>[&v=]` | viewer | logical station groups → platforms with map coordinates |
| `GET` | `/api/notifications` | viewer | active notifications (`SIGNAL_WAIT`, `DEADLOCK`, `DETOUR`) |
| `GET` | `/api/replays` | viewer | replay index |
| `GET` | `/api/replays/<id>` | viewer | one replay in full |

### Corridors

Both need `?graph=<uuid>&from=<station group>&to=<station group>`, with distinct `from`/`to`;
optional `&v=<graphVersion>`.

| Method | Path | Tier | Notes |
|---|---|---|---|
| `GET` | `/api/corridor/actual` | viewer | observed movement along the corridor. Extra `?sinceTick=`; `ETag` |
| `GET` | `/api/corridor/plan` | viewer | the simulator's projection for the same corridor |

`404 route_not_found` when there is no route between those two station groups on that graph.

### Presets (planner)

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/presets` | summaries of every preset |
| `GET` | `/api/presets?id=<uuid>` | one preset, with its decoded schedule |
| `POST` | `/api/presets` | `{trainId, name?}` — snapshot a train's current schedule; or `{sourceId, name?}` to duplicate a preset |
| `PATCH` | `/api/presets` | `{id, name}` rename · `{id, folder}` move · `{id, entry, target, col, row, key, value}` edit one whitelisted schedule value |
| `DELETE` | `/api/presets?id=<uuid>` | delete |

Preset errors: `preset_full`, `preset_invalid`, `preset_empty`, `bad_name`, `bad_folder`,
`not_found`, `preset_corrupt`.

### Plans and train folders (planner)

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/plans` | summaries · `?id=<uuid>` for one in full |
| `POST` | `/api/plans` | save; body with an `id` overwrites, without one creates |
| `DELETE` | `/api/plans?id=<uuid>` | delete |
| `GET` | `/api/train-folders` | `{folders: {trainUuid: "folder/path"}}` — **viewer** may read this one |
| `PATCH` | `/api/train-folders` | `{trainId, folder}` files one train (blank unfiles) · `{from, to}` re-files a whole folder subtree |

### Simulations (planner)

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/sims` | queue a run → `202 {simId}` |
| `GET` | `/api/sims` | every job with its state |
| `GET` | `/api/sims/<simId>` | one job's status |
| `DELETE` | `/api/sims/<simId>` | cancel — owner only, unless you are deployer |
| `GET` | `/api/sims/<simId>/result` | the finished result (gzipped). `409 not_done` while running |
| `GET` | `/api/sims/<simId>/diagram?from=&to=` | corridor diagram for that run. `409 graph_changed` if the network was rebuilt since |

Submit body:

```jsonc
{
  "graphId": "…",                      // required
  "assignments": [                     // preset → train
    { "trainId": "…", "presetId": "…",
      "valueOverrides": [ { "entry": 0, "target": "…", "col": 0, "row": 0,
                            "key": "…", "value": "…" } ] }
  ],
  "removals": ["trainUuid"],           // simulate without these trains
  "keeps":    ["trainUuid"],           // keep these exactly as they are
  "removeScheduled": true,             // default: drop scheduled-but-unassigned trains
  "startDayTime": null,                // in-game day time, null = now
  "horizonHours": 12,                  // capped by Web Sim Max Horizon Hours
  "headwaySeconds": null               // null/-1 = the configured default
}
```

A train may appear at most once, and may not be both assigned and removed, or both kept and
removed. `valueOverrides` go through the same whitelist as preset edits and the same code path as
deploy — you cannot simulate an edit deploy would refuse, or vice versa.

### Deploy and audit (deployer)

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/deploy` | `{assignments: […same shape as /api/sims…], mode: "IMMEDIATE" \| "IDLE_ONLY"}` → per-train results. Unknown/missing mode = `IDLE_ONLY` |
| `GET` | `/api/audit?limit=<1..500>` | the deploy journal, newest first (default 100) |

A successful deploy also broadcasts a `deployed` SSE event.

### Debug (deployer)

Field-debugging aids for plan-vs-actual mismatches. Shapes may change without notice.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/debug/simedges?graph=<uuid>&ids=<edgeId,…>` | the simulator's own view of those graph edges |
| `GET` | `/api/debug/schedule?train=<uuid>` | one train's raw schedule: instruction/condition ids and their NBT data, plus throttle, speed, paused, current entry and live Tramways limits |
| `GET` | `/api/debug/simtrain?graph=<uuid>&train=<id>` | one train in the current projection: station visits, events, notices, the edges it actually drove, and its final plan |

## Server-sent events

```
GET /api/events        (viewer)
```

One multiplexed stream. `Web Max Sse Clients` (20) connections max — over that, `503 sse_full`.

- The stream opens with `retry: 3000` and a per-connection `hello` event carrying
  `{serverTick, serverWallMs, dayTime, dayTimeRate, rosterVersion, tier, graphs: {id: version}}` —
  everything a fresh client needs to know what to fetch.
- Every broadcast event carries a monotonic `id:`. Reconnect with `Last-Event-ID` to replay what you
  missed; if the gap is too large the server sends `reset` and you should refetch your snapshots.
- A `:` comment heartbeat is written after 15 s of silence, so proxies keep the connection open.
  The client reconnects on its own after 25 s of silence.

| Event | Meaning |
|---|---|
| `hello` | per-connection preamble (no id) |
| `reset` | your `Last-Event-ID` was too old — refetch everything |
| `trains` | a live position frame (full or delta) |
| `trainMeta` | the roster changed — refetch `/api/trains` |
| `graph` | one network was rebuilt: `{id, version}` |
| `graphIndex` | the set of networks changed |
| `notify` | a notification was raised, updated or cleared |
| `replay` | a replay finished capturing |
| `presets`, `plans`, `trainFolders` | that store changed — refetch it |
| `sim` | a simulation job changed state |
| `deployed` | `{user, mode, applied, skipped}` |

## Static files

Everything not under `/api` or `/auth` is served from the frontend bundled in the jar
(`assets/createdispatcher/web/`), with SPA fallback to `index.html`.
