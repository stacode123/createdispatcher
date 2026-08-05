# Create Dispatcher — web frontend

Vite + Svelte 5 SPA served by the mod's embedded web server from jar resources
(`assets/createdispatcher/web/`, copied from the committed `dist/` by `common/build.gradle`).

- Node ≥ 20.19, npm. Gradle and CI never run Node — **after changing anything under `web/`,
  run `npm run build` and commit `dist/`**.
- `npm run dev` — dev server on :5173, proxying `/api` + `/auth` to a running MC dev server
  on :8455 (enable `Web Enabled` in the createdispatcher common config; log in via a
  `/dispatcher web session <tier>` one-time link).
- `VITE_MOCK=1 npm run dev` — no backend needed: synthetic 100k-point network + sim playback.
- `npm run check` — svelte-check/type check.
