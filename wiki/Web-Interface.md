# The web interface

An HTTP server embedded in the Minecraft **server** JVM that serves a browser interface for your
rail network. It is **off by default** and binds to nothing until you enable it.

Clients need nothing but a browser. It adds no runtime dependencies: the JDK's own HTTP server,
server-sent events for live updates, and the frontend shipped inside the mod jar.

## 1. Turning it on

In the common config (`config/createdispatcher-common.toml`), section `Web Interface`:

```toml
[general."Web Interface"]
    "Web Enabled" = true
    "Web Bind Address" = "127.0.0.1"
    "Web Port" = 8455
    "Web Public Url" = ""
```

Restart the server. The log says where it is listening and warns about anything risky:

```
[Create Dispatcher] Dispatcher web interface listening on 127.0.0.1:8455 (0 allowlisted user(s), Discord login not configured)
[Create Dispatcher] Dispatcher web: the allowlist is empty and Web Default Tier is none — nobody can get in yet — run /dispatcher web allow <discordId> <tier>, or /dispatcher web session <tier> for a one-time link
```

`/dispatcher web status` repeats those warnings at any time.

**Bind address.** `127.0.0.1` (the default) means *this machine only* — reach it through an SSH
tunnel (`ssh -L 8455:127.0.0.1:8455 user@server`) or put a reverse proxy in front of it. `0.0.0.0`
exposes it on every interface; only do that behind a proxy that terminates TLS, and set
`Web Public Url` when you do.

## 2. Logging in

Two ways in. The first needs no Discord application.

### One-time links

```
/dispatcher web session viewer
/dispatcher web session planner
/dispatcher web session deployer
```

prints a single-use link valid for **5 minutes**; open it in a browser and the session lasts
`Web Session Hours` (72 by default). This is the recommended path for private servers and for
testing.

### Discord login

1. Create an application at <https://discord.com/developers/applications>.
2. Under **OAuth2**, add the redirect `<your public url>/auth/callback` — it must match
   `Web Public Url` exactly.
3. Put the client id and secret in `config/createdispatcher/secrets.json`:

```json
{
  "discordClientId": "1234567890",
  "discordClientSecret": "…",
  "sessionSecret": "(generated for you — leave it alone; rotating it logs everybody out)"
}
```

4. `/dispatcher web reload`, then sign in from the site.

Only the `identify` scope is requested — the mod learns your Discord id, username and avatar hash,
nothing else. **Never commit `secrets.json`.** It deliberately lives outside the config toml,
because COMMON tomls ship inside client installs and SERVER configs sync to clients.

## 3. Permission tiers

| Tier | Can |
|---|---|
| `none` | nothing — logged in, but every API call is refused |
| `viewer` | live map, trains, notifications, replays, corridor diagrams |
| `planner` | the above, plus presets, saved plans, train folders and planner simulations |
| `deployer` | the above, plus **Deploy**, the audit journal, the debug endpoints **and the folder auto-sort** |

Membership lives in `config/createdispatcher/allowlist.json`:

```json
{
  "users": {
    "197123456789012345": { "tier": "deployer", "note": "server owner" },
    "204987654321098765": { "tier": "viewer",   "note": "" }
  }
}
```

Edit it by hand (it hot-reloads within 30 s, or `/dispatcher web reload`), or from the game with
`/dispatcher web allow|deny|list` — see [Commands](Commands).

**Auto-enrolment.** `Web Default Tier` (default `none`) is the tier an unknown Discord user gets on
their first login. Leave it at `none` on a public server: with it set, anyone who can reach the site
and has a Discord account is in. Auto-enrolled users are written into `allowlist.json` like any
other, so they show up in `/dispatcher web list` and can be changed or removed. An id that already
has an entry is never re-enrolled — which is why a `none` entry is a permanent block, while `deny`
merely forgets them.

**Deployer is full control over every train on the server. Treat it like op.**

## 4. What the site shows

### Live

A 2D map of every rail network with live train positions (sampled every `Web Live Sample Ticks`,
1 s by default, pushed over a single SSE stream), hover details, and:

- **Notifications** — three kinds, each with a severity:
  | Kind | Raised when |
  |---|---|
  | `SIGNAL_WAIT` | a train has been held at a red signal past `Web Signal Wait Alert Seconds` (4× = critical) |
  | `DEADLOCK` | a wait-for cycle has persisted for `Web Deadlock Confirm Seconds` |
  | `DETOUR` | the remaining route is `Web Detour Ratio` times longer than the shortest path (and at least `Web Detour Min Blocks` long) — the notification names what caused it and carries both routes for overlay |
- **Replays** — each notification captures the lead-up and the aftermath (`Web Replay Lead/Tail
  Seconds`, trains within `Web Replay Radius`), so you can scrub through what actually happened.
- **Corridor diagrams** — pick two stations and get a time–distance diagram of everything travelling
  between them: observed history as solid lines, the simulator's plan overlaid, with per-train drift
  calibration and uncertainty bands that persist across restarts.

### Planner

Preset library, assignment, simulation and deploy — see [Planner and Deploy](Planner-and-Deploy).

## 5. Behind a reverse proxy (TLS)

The embedded server speaks plain HTTP by design. For anything reachable from the internet, terminate
TLS in front of it. **The one special requirement is that the proxy must not buffer `/api/events`** —
that is the live-update channel.

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

Then set `Web Public Url = "https://trains.example.com"`. That single setting drives the Discord
redirect, the `Origin` check on mutating requests, and the `Secure` flag on the session cookie (set
only when the URL is `https://`).

## 6. Keyboard and layout

`?` opens the shortcut list in the browser: `1`/`2`/`3` switch views, `/` or `Ctrl-K` finds a train,
`Space` drives the playback transport, `T` toggles light/dark, `Esc` cancels.

The panel dividers are draggable — the diagram dock, the sim dock, and the planner's preset and
train columns. Sizes are remembered per browser, arrow keys nudge a focused divider, and
double-clicking one resets it.

## 7. Files on disk

| Path | What |
|---|---|
| `config/createdispatcher/secrets.json` | Discord credentials + session-signing secret (0600 where the OS allows it) |
| `config/createdispatcher/allowlist.json` | who may log in, and at which tier |
| `<world>/createdispatcher/presets/*.json` | the schedule preset library |
| `<world>/createdispatcher/plans/*.json` | saved planned timetables |
| `<world>/createdispatcher/train-folders.json` | the planner's train folder assignments |
| `<world>/createdispatcher/web-calibration.json` | learned plan-vs-actual drift, so restarts skip the warm-up |
| `<world>/createdispatcher/web-audit.jsonl` | the deploy journal (rotates to `web-audit.1.jsonl` at 4 MB) |

## See also

- [Configuration](Configuration) — every `Web Interface` setting
- [Commands](Commands) — `/dispatcher web …`
- [HTTP API](HTTP-API) — endpoints, SSE, auth, rate limits
- [Troubleshooting](Troubleshooting)
