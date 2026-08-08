# Create Dispatcher — web interface

An HTTP server embedded in the Minecraft **server** JVM that serves a browser interface for
your rail network:

- **Live** — a 2D map of every rail network with live trains, hover details, notifications
  (signal waits, deadlocks, detours), replays of what led up to them, and time-distance
  diagrams of any A→B corridor with the simulated plan overlaid on observed movement.
- **Planner** — a library of schedule presets, drag-and-drop assignment of presets to trains,
  deterministic simulation of the resulting timetable (map playback, conflicts, root causes,
  corridor diagrams), and **Deploy**, which applies the assignments to the real trains.

It adds no runtime dependencies: the JDK's own HTTP server, server-sent events for live
updates, and the frontend shipped inside the mod jar.

> The whole feature is server-side. Clients need nothing but a browser — and nothing about it
> is exposed to the network until you turn it on.

## 1. Turning it on

In the **common** config (`config/createdispatcher-common.toml`), section `Web Interface`:

```toml
[general."Web Interface"]
    "Web Enabled" = true
    "Web Bind Address" = "127.0.0.1"
    "Web Port" = 8455
    "Web Public Url" = ""
```

Restart the server. The log says where it is listening, and warns about anything risky in
the configuration:

```
[Create Dispatcher] Dispatcher web interface listening on 127.0.0.1:8455 (0 allowlisted user(s), Discord login not configured)
[Create Dispatcher] Dispatcher web: the allowlist is empty and Web Default Tier is none — nobody can get in yet — run /dispatcher web allow <discordId> <tier>, or /dispatcher web session <tier> for a one-time link
```

`/dispatcher web status` repeats those warnings at any time.

**Bind address.** `127.0.0.1` (the default) means "this machine only" — reach it through an
SSH tunnel (`ssh -L 8455:127.0.0.1:8455 user@server`) or put a reverse proxy in front of it.
`0.0.0.0` exposes it on every interface; only do that behind a proxy that terminates TLS, and
set `Web Public Url` when you do.

## 2. Logging in

Two ways in. You do not need a Discord application for the first one.

### One-time links (no Discord)

```
/dispatcher web session viewer
/dispatcher web session planner
/dispatcher web session deployer
```

prints a single-use link valid for 5 minutes; click it to copy, open it in a browser, and the
session lasts `Web Session Hours` (72 by default). This is the recommended path for private
servers and for testing.

### Discord login

1. Create an application at <https://discord.com/developers/applications>.
2. Under **OAuth2**, add a redirect: `<your public url>/auth/callback`
   (e.g. `https://trains.example.com/auth/callback`). It must match `Web Public Url` exactly.
3. Copy the **Client ID** and **Client Secret** into `config/createdispatcher/secrets.json`:

```json
{
  "discordClientId": "1234567890",
  "discordClientSecret": "…",
  "sessionSecret": "(generated for you — leave it alone; rotating it logs everybody out)"
}
```

4. `/dispatcher web reload`, then sign in from the site.

Only the `identify` scope is requested — the mod learns your Discord id, username and avatar
hash, nothing else. **Never commit `secrets.json`**; it lives outside the config toml on
purpose, because common configs ship inside client installs and server configs sync to
clients.

## 3. Who may do what

| Tier | Can |
|---|---|
| `none` | nothing — logged in, but every API call is refused |
| `viewer` | the live map, trains, notifications, replays, corridor diagrams |
| `planner` | everything above, plus presets, saved plans, folders and planner simulations |
| `deployer` | everything above, plus **Deploy**, the audit journal and the debug endpoints |

Membership lives in `config/createdispatcher/allowlist.json`:

```json
{
  "users": {
    "197123456789012345": { "tier": "deployer", "note": "server owner" },
    "204987654321098765": { "tier": "viewer",   "note": "" }
  }
}
```

Edit it by hand (it hot-reloads within 30 s, or `/dispatcher web reload`) or from the game:

```
/dispatcher web allow <discordId> <viewer|planner|deployer>
/dispatcher web allow <discordId> none      # a permanent block (see below)
/dispatcher web deny  <discordId>           # forget them entirely
/dispatcher web list
```

A Discord user id is a long number — enable Developer Mode in Discord and right-click a user
to copy it.

**Auto-enrolment.** `Web Default Tier` (default `none`) is the tier an unknown Discord user is
granted on their first login. Leave it at `none` for a public server: with it set, anyone who
can reach the site and has a Discord account gets in. Auto-enrolled users are written into
`allowlist.json` like any other, so they show up in `/dispatcher web list` and can be changed or
removed. An id that already has an entry is never re-enrolled — which is why a `none` entry is
a permanent block, while `deny` merely forgets them.

Deployer is full control over every train on the server. Treat it like op.

## 4. Behind a reverse proxy (TLS)

The embedded server speaks plain HTTP by design. For anything reachable from the internet,
terminate TLS in front of it. **The only special requirement is that the proxy must not buffer
the `/api/events` stream** — that is the live-update channel.

nginx:

```nginx
server {
    listen 443 ssl http2;
    server_name trains.example.com;
    # ssl_certificate … ssl_certificate_key …;

    location / {
        proxy_pass http://127.0.0.1:8455;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
    }

    # server-sent events: no buffering, no idle timeout
    location /api/events {
        proxy_pass http://127.0.0.1:8455;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 1h;
        chunked_transfer_encoding off;
    }
}
```

Caddy needs no special case — it streams by default:

```
trains.example.com {
    reverse_proxy 127.0.0.1:8455
}
```

Then set `Web Public Url = "https://trains.example.com"`. That single setting drives the
Discord redirect, the `Origin` check on mutating requests, and the `Secure` flag on the
session cookie (set only when the URL is `https://`).

## 5. Deploy

Deploy is the only part of the site that changes the running world, and it is deliberately
explicit:

- **Safe (idle only)** — a train is changed only if it is standing still with no destination
  (paused, finished, dwelling at a station, or scheduleless). Anything mid-trip is skipped and
  reported.
- **Immediate** — the swap happens regardless; a running train cancels its current trip and
  reroutes from the new schedule.

Per train it strips the preset's saved progress (so the schedule starts at its first entry),
cancels navigation, installs the schedule, and marks it as an Advanced Schedule — so a
conductor removing it hands back an Advanced Schedule item. Every attempt, successful or not,
is appended to `<world>/createdispatcher/web-audit.jsonl` and readable from the dialog's *recent
deploys* view or `GET /api/audit`.

Deploy never *removes* a train from the network. "Remove" and "keep" in the planner are
simulation-only.

If a schedule uses instructions the simulator cannot model, deploy still goes through — the
result row says so, and it only means the projections will not represent those steps.

## 6. Files on disk

| Path | What |
|---|---|
| `config/createdispatcher/secrets.json` | Discord credentials + the session-signing secret (0600 where the OS allows it) |
| `config/createdispatcher/allowlist.json` | who may log in, and at which tier |
| `<world>/createdispatcher/presets/*.json` | the schedule preset library |
| `<world>/createdispatcher/plans/*.json` | saved planned timetables |
| `<world>/createdispatcher/train-folders.json` | the planner's train folder assignments |
| `<world>/createdispatcher/web-calibration.json` | learned plan-vs-actual drift, so restarts skip the warm-up |
| `<world>/createdispatcher/web-audit.jsonl` | the deploy journal (rotates to `web-audit.1.jsonl` at 4 MB) |

## 7. Configuration reference

All under `Web Interface` in the common config. Defaults are chosen to be safe and quiet.

| Setting | Default | Notes |
|---|---|---|
| `Web Enabled` | `false` | master switch; nothing binds while it is off |
| `Web Bind Address` | `127.0.0.1` | `0.0.0.0` exposes it on every interface |
| `Web Port` | `8455` | |
| `Web Public Url` | `""` | required for Discord login; drives Origin + cookie security |
| `Web Default Tier` | `none` | tier for a first-time Discord login; `none` = allowlist only |
| `Web Http Threads` | `4` | request workers (SSE clients get their own threads) |
| `Web Max Sse Clients` | `20` | simultaneous live-update connections |
| `Web Live Sample Ticks` | `20` | ticks between live position samples (20 = 1 s) |
| `Web History Sample Seconds` | `5` | resolution of observed-movement history |
| `Web History Hours` | `2` | how much history is kept; `0` disables actual lines |
| `Web Graph Node Cap` | `100000` | biggest network served to the map |
| `Web Graph Min Rebuild Seconds` | `60` | floor between rebuilds of one network |
| `Web Graph Max Age Seconds` | `1800` | backstop re-verify; edits are detected instantly anyway |
| `Web Signal Wait Alert Seconds` | `120` | SIGNAL_WAIT threshold (4× = critical) |
| `Web Deadlock Confirm Seconds` | `30` | how long a wait cycle must persist to be called a deadlock |
| `Web Detour Ratio` / `Web Detour Min Blocks` | `1.75` / `500` | when a route counts as a detour |
| `Web Sim Max Horizon Hours` | `48` | longest planner simulation (in-game hours) |
| `Web Sim Max Queued` / `Web Sim Cooldown Seconds` | `4` / `15` | simulation queue caps |
| `Web Sim Wall Cap Seconds` | `0` | `0` = uncapped and reproducible; nonzero marks results truncated |
| `Web Sim Cache MB` | `128` | memory budget for finished simulation results |
| `Web Projection Stale Seconds` | `300` | age at which the live plan overlay is recomputed |
| `Web Background Projections` | `true` | keep projections fresh with no browser open |
| `Web Session Hours` | `72` | login lifetime |
| `Web Preset Max Count` / `Web Plan Max Count` | `500` / `200` | library caps |
| `Web Replay Kept` | `20` | notification replays kept in memory; `0` disables |
| `Web Replay Buffer/Lead/Tail Seconds`, `Web Replay Radius` | `300` / `120` / `60` / `1200` | what a replay covers |

Rate limits are fixed, not configured: 20 auth requests/minute per IP, 60/minute per session
for graph, corridor, simulation and write endpoints, and 10/minute for deploy.

## 8. Keyboard

`?` opens the shortcut list in the browser: `1`/`2`/`3` switch views, `/` or `Ctrl-K` finds a
train, `Space` drives the playback transport, `T` toggles the light/dark theme, `Esc` cancels.

The panel dividers are draggable — the diagram dock, the sim dock, and the planner's preset
and train columns. A long corridor needs the height, so drag the dock's top edge up; the size
is remembered per browser, arrow keys nudge a focused divider, and double-clicking one resets
it.

## 9. Troubleshooting

**Nothing on the port.** `Web Enabled` false, or the server failed to bind — check the log for
`Dispatcher web interface failed to start` (port already in use is the usual cause).

**"Not allowlisted" after a Discord login.** Expected until an admin runs
`/dispatcher web allow <your id> viewer`. The page shows the id to add.

**Discord login says `discord_not_configured`.** `secrets.json` has no client id/secret, or
`Web Public Url` is empty. Use a `/dispatcher web session` link meanwhile.

**Logged in, but everything 403s.** Your tier is below what that page needs — `/api/me` shows
the tier the server resolved for you.

**The map loads but never updates.** The SSE stream is being buffered by a proxy; see §4. The
page reconnects on its own after 25 s of silence, so a constantly reloading map means the
stream is dying, not that events stopped.

**A network is missing from the map.** It is over `Web Graph Node Cap`, or it has not been
translated yet. `/dispatcher web refresh` forces a rebuild on the next poll.

**Corridor diagrams have no solid (actual) lines.** `Web History Hours` is `0`, or history was
cleared by a graph rebuild — it refills at `Web History Sample Seconds`.

**A plan overlay says `pending`.** The background projection is still running; it appears
within a minute. With `Web Background Projections` off it only computes while a browser asks.

## 10. Developing the frontend

The frontend lives in `web/` (Vite + Svelte 5) and is compiled **at build time**:
`:common:npmCi` (installs `web/package-lock.json`) then `:common:buildWebDist` (runs `vite build`)
feed `web/dist/` into `common/build.gradle`'s `processResources`, which packages it into the jar.
`web/dist/` is gitignored — a build artifact, never committed. Node ≥ 20 is needed only on machines
that run `./gradlew build` (including CI, which has a `setup-node` step), never by players.

```bash
cd web
npm install
npm run dev                       # :5173, proxies /api + /auth to a dev server on :8455
DISPATCHER_PROXY=http://host:8455 npm run dev   # …or to any other server
VITE_MOCK=1 npm run dev           # no backend at all: synthetic network + fixtures
npm run check                     # types
npm run build                     # → dist/  (gitignored; `./gradlew build` runs this itself)
```

The old `verifyWebDist` freshness check was dropped along with the committed dist — there is no
committed build left to verify against, so the jar always ships the UI the build just produced.
