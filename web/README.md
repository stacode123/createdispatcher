# Create Dispatcher — web frontend

Vite + Svelte 5 SPA served by the mod's embedded web server from jar resources
(`assets/createdispatcher/web/`, copied from `web/dist/` by `common/build.gradle`). The dist is a
**build-time artifact**: `./gradlew build` runs `:common:npmCi` + `:common:buildWebDist` (npm ci +
vite build) itself and puts the result in the jar, so `web/dist/` is gitignored — never built by
hand and never committed.

- Node ≥ 20.19, npm — only needed on machines that run `./gradlew build` (the CI workflow has a
  Node step); players never need it.
- `npm run dev` — dev server on :5173, proxying `/api` + `/auth` to a running MC dev server
  on :8455 (enable `Web Enabled` in the createdispatcher common config; log in via a
  `/dispatcher web session <tier>` one-time link).
- `VITE_MOCK=1 npm run dev` — no backend needed: synthetic 100k-point network + sim playback.
- `npm run check` — svelte-check/type check.
