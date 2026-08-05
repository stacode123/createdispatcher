# Configuration

Everything lives in the **common** config, `config/createdispatcher-common.toml`. There is
deliberately no client config: nothing in the simulator, the graph translator or the web layer reads
a client-side value. On Fabric the file is provided by Forge Config API Port.

Secrets and the user allowlist are **never** in the toml — they are server-only JSON under
`config/createdispatcher/`, because COMMON tomls ship inside client installs and SERVER-type configs
sync to clients.

## `[general."Advanced Schedule"]`

| Setting | Default | Range | What |
|---|---|---|---|
| `Graph Node Cap` | `4000` | 100–100000 | biggest network (in track nodes) that will be translated for the in-game map and simulator |
| `Sim Max Horizon Hours` | `48` | 1–336 | longest simulation a player may request, in in-game hours (1000 ticks each) |
| `Sim Cooldown Seconds` | `10` | 0–3600 | per-player wait between simulation requests |
| `Sim Max Concurrent` | `2` | 1–8 | simulations running at once, across all players |
| `Sim Max Wall Seconds` | `10` | 1–120 | real-time budget per simulation; exceeding it truncates the result |
| `Sim Headway Seconds` | `10` | 0–600 | default minimum gap between consecutive trains through a section before a headway conflict is reported (players can override per run; `0` disables the flat threshold, CRN separation conditions still apply) |
| `Sim Wait Conflict Seconds` | `30` | 0–600 | how long a simulated train may sit at a red signal before it counts as a section conflict; `0` disables |
| `Sim Acceleration Multiplier` | `1.0` | 0.1–5.0 | per-carriage acceleration penalty in the simulator's *Standard* mode (higher = slower). **With Create Realism installed, set this to match its own train-acceleration multiplier** so simulated departures match reality |
| `Sim Debug Export` | `false` | | write `dispatcher-sim-debug.html` after every simulation — a debugging tool |
| `Sim Diagram Hidden Categories` | `[]` | | trains whose CRN category name contains any of these words (case-insensitive) are hidden from the time–distance diagram, e.g. `["bus"]` |

## `[general."Web Interface"]`

Defaults are chosen to be safe and quiet. See [Web Interface](Web-Interface) for what they do in
practice.

### Server and access

| Setting | Default | What |
|---|---|---|
| `Web Enabled` | `false` | master switch; nothing binds while it is off |
| `Web Bind Address` | `127.0.0.1` | `0.0.0.0` exposes it on every interface — only behind a TLS proxy |
| `Web Port` | `8455` | 1024–65535 |
| `Web Public Url` | `""` | public base URL, e.g. `https://trains.example.com`. Required for Discord login; drives the `Origin` check and the cookie `Secure` flag |
| `Web Default Tier` | `none` | tier granted to an unknown Discord user on first login. **Never above `viewer` on a public server** |
| `Web Session Hours` | `72` | 1–720 · login lifetime |
| `Web Http Threads` | `4` | 2–16 · request workers (SSE clients get their own threads) |
| `Web Max Sse Clients` | `20` | 1–100 · simultaneous live-update connections |

### Live data and graphs

| Setting | Default | What |
|---|---|---|
| `Web Live Sample Ticks` | `20` | 5–200 · ticks between live position samples (20 = 1 s) |
| `Web History Sample Seconds` | `5` | 1–60 · resolution of observed-movement history |
| `Web History Hours` | `2` | 0–12 · how much history is kept; `0` disables the *actual* lines on diagrams |
| `Web Graph Node Cap` | `100000` | 100–1000000 · biggest network served to the web map (separate from `Graph Node Cap`) |
| `Web Graph Min Rebuild Seconds` | `60` | 5–3600 · floor between rebuilds of one network |
| `Web Graph Max Age Seconds` | `1800` | 30–86400 · backstop re-verify; node/signal/station edits are detected instantly anyway, and unchanged content never bumps a version |

### Notifications and replays

| Setting | Default | What |
|---|---|---|
| `Web Signal Wait Alert Seconds` | `120` | 10–3600 · `SIGNAL_WAIT` threshold (4× = critical) |
| `Web Deadlock Confirm Seconds` | `30` | 5–600 · how long a wait cycle must persist to be called a deadlock |
| `Web Detour Ratio` | `1.75` | 1.1–10.0 · route this many times longer than the shortest path = detour |
| `Web Detour Min Blocks` | `500` | 0–100000 · minimum remaining route length before detour detection applies |
| `Web Replay Kept` | `20` | 0–100 · replays kept in memory; `0` disables capture |
| `Web Replay Buffer Seconds` | `300` | 30–1800 · rolling window of live frames kept for lead-ups |
| `Web Replay Lead Seconds` | `120` | 10–1800 · lead-up included in a replay (capped by the buffer) |
| `Web Replay Tail Seconds` | `60` | 10–600 · aftermath a replay keeps recording |
| `Web Replay Radius` | `1200` | 100–10000 · blocks around an event whose trains are included (involved trains always are) |

### Planner simulations and projections

| Setting | Default | What |
|---|---|---|
| `Web Sim Max Horizon Hours` | `48` | 1–336 · longest planner simulation, in in-game hours |
| `Web Sim Max Queued` | `4` | 1–32 |
| `Web Sim Cooldown Seconds` | `15` | 0–3600 · per-user |
| `Web Sim Wall Cap Seconds` | `0` | 0–600 · `0` = uncapped and fully reproducible; nonzero marks results truncated |
| `Web Sim Cache MB` | `128` | 16–1024 · memory budget for finished results |
| `Web Projection Stale Seconds` | `300` | 10–3600 · age at which the live plan overlay is recomputed |
| `Web Background Projections` | `true` | keep projections fresh with no browser open, so drift calibration keeps learning |
| `Web Preset Max Count` | `500` | 1–10000 |
| `Web Plan Max Count` | `200` | 1–5000 |

## Files the mod writes

| Path | What | Reload |
|---|---|---|
| `config/createdispatcher-common.toml` | everything above | restart |
| `config/createdispatcher/secrets.json` | Discord credentials + session-signing secret (0600 where the OS allows) | `/dispatcher web reload` |
| `config/createdispatcher/allowlist.json` | who may log in, and at which tier | hot-reloads within 30 s |
| `config/createdispatcher/server-only.marker` | if present, forces [server-only mode](Server-Only-Mode) | restart |
| `<world>/createdispatcher/presets/*.json` | schedule preset library | live |
| `<world>/createdispatcher/plans/*.json` | saved planned timetables | live |
| `<world>/createdispatcher/train-folders.json` | planner train filing | live |
| `<world>/createdispatcher/web-calibration.json` | learned plan-vs-actual drift | live |
| `<world>/createdispatcher/web-audit.jsonl` | deploy journal, rotating at 4 MB | append-only |

Rotating `sessionSecret` in `secrets.json` logs everybody out. That is the intended way to revoke
all sessions at once.
