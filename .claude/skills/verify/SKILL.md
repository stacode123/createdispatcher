---
name: verify
description: Runtime-verification recipe for Create Realism (Minecraft mod, Architectury multi-loader)
---

# Verifying Create Realism at runtime

Branch-dependent modules (`git branch --show-current` first): `Advanced-Schedule`/`1.20.1-C6` use `:forge:`/`:fabric:`, `1.21.1` uses `:neoforge:` only.

## Surfaces

- **Interactive GUI (screens, HUDs, in-game behavior)** — no headless harness exists. Project convention (CLAUDE.md): launch `./gradlew :<loader>:runClient` in the background and let the user drive it; the client renders on the user's display. Do not attempt xdotool automation against their live session.
- **Dedicated server (packet registration, config, class-loading)** — fully scriptable:
  1. `echo 'eula=true' > <loader>/run/eula.txt` (first boot otherwise exits).
  2. `./gradlew :<loader>:runServer` in background; poll its output file for `Done ([0-9.]+s)!`.
  3. Benign first-boot noise: `Failed to load properties: server.properties` (NoSuchFileException), narrator/flite errors, FabricLoader mixin `Error loading class` warnings for absent optional mods (ftbchunks, xaero, frex) and client-only classes probed by other mods' configs.
  4. The load-bearing check for this repo: **zero realism-related `NoClassDefFoundError`/`ClassNotFoundException`** — packet classes must never reference Screen classes (dedicated servers verify-load them at registration).
  5. Stop by PID, not pattern: the server JVM's command line is `dev.architectury.transformer.TransformerRuntime` (devlaunchinjector is only loaded internally, so `pkill -f devlaunchinjector` misses it — and self-matches the invoking shell, killing your own script with exit 144). Find it with `ss -tlnp | grep <port>` or `pgrep -f TransformerRuntime`, then `kill <pid>`. The gradle background task then reports nonzero exit — that's the killed JVM, not a failure. A killed dev server may not flush its final log lines (no "Stopping server" in latest.log is normal).
- **Config** — runtime-generated at `<loader>/run/config/realism-common.toml`; new config keys must appear there after any client/server run.
- **Logs** — background task output files under the session tasks dir; client logs also in `<loader>/run/logs/latest.log`.

## Gotchas

- Gradle daemon disabled: every invocation cold-starts (~20-60s overhead) — use generous timeouts.
- Simulator engine (`common/src/main/java/net/Realism/content/simulator/core/`) is MC-free with JUnit tests, but per /verify rules tests are not runtime evidence; the engine's real surface is the in-game Simulate flow (schedule item → editor → Simulate button).
- Fabric and Forge clients share nothing; verify both loaders on 1.20.1 branches.
- fish shell: avoid `===` markers in echo; foreground `sleep N` alone is blocked — use `until <cond>; do sleep 5; done` inside one command with a timeout.
