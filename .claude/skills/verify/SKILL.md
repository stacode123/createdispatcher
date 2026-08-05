---
name: verify
description: Runtime-verification recipe for Create Dispatcher (Minecraft mod, Architectury multi-loader)
---

# Verifying Create Dispatcher at runtime

Modules are `:forge:` and `:fabric:` (MC 1.20.1). Verify forge first, then fabric — they share nothing.

## Surfaces

- **Interactive GUI (editor, map, simulation windows, preset library)** — no headless harness exists.
  Project convention (CLAUDE.md): launch `./gradlew :<loader>:runClient` in the background and let the
  user drive it; the client renders on the user's display. Do not attempt xdotool automation against
  their live session.
- **Dedicated server (packet registration, config, class-loading, the web interface)** — fully
  scriptable:
  1. `echo 'eula=true' > <loader>/run/eula.txt` (first boot otherwise exits).
  2. `./gradlew :<loader>:runServer` in background; poll its output file for `Done ([0-9.]+s)!`.
  3. Benign first-boot noise: `Failed to load properties: server.properties` (NoSuchFileException),
     narrator/flite errors, FabricLoader mixin `Error loading class` warnings for absent optional mods
     (ftbchunks, xaero, frex) and client-only classes probed by other mods' configs.
  4. The load-bearing check: **zero `NoClassDefFoundError`/`ClassNotFoundException` naming
     `net.Dispatcher`.** Packet classes must never reference Screen classes (dedicated servers
     verify-load them at registration) and `net.Dispatcher.web` must never touch a client class.
  5. Stop by PID, not pattern: the server JVM's command line is
     `dev.architectury.transformer.TransformerRuntime` (`pkill -f devlaunchinjector` misses it — and
     self-matches the invoking shell, killing your own script with exit 144). Find it with
     `ss -tlnp | grep <port>` or `pgrep -f TransformerRuntime`, then `kill <pid>`. The gradle
     background task then reports nonzero exit — that's the killed JVM, not a failure. A killed dev
     server may not flush its final log lines (no "Stopping server" in latest.log is normal).
- **Web interface** — with `Web Enabled = true` in the runtime config, curl against
  `http://127.0.0.1:8455`: `/` serves the committed frontend out of the jar, `/api/*` returns 401
  without a session, mutations without `X-Dispatcher-Csrf` return 403, and `/api/events` streams SSE.
  Mint a session in the server console with `/dispatcher web session viewer`.
- **Config** — runtime-generated at `<loader>/run/config/createdispatcher-common.toml`; new config keys
  must appear there after any client/server run. Secrets and the allowlist are separate, server-only
  files under `<loader>/run/config/createdispatcher/`.
- **Logs** — background task output files under the session tasks dir; client logs also in
  `<loader>/run/logs/latest.log`.

## Gotchas

- Gradle daemon disabled: every invocation cold-starts (~20-60s overhead) — use generous timeouts.
- The simulator engine (`common/src/main/java/net/Dispatcher/content/simulator/core/`) is MC-free with
  JUnit tests, but tests are not runtime evidence; its real surface is the in-game flow
  (Advanced Schedule item → editor → Simulate).
- The benchmark digest (`-Dsim.benchmark=true`) must stay `19a84b2d9cbfade6` after any change meant to
  be behaviour-preserving.
- fish shell: avoid `===` markers in echo; foreground `sleep N` alone is blocked — use
  `until <cond>; do sleep 5; done` inside one command with a timeout.
