<script lang="ts">
  import { onMount } from 'svelte';
  import { session } from './lib/stores/session.svelte';
  import { router } from './router.svelte';
  import { MOCK } from './lib/api/http';
  import { connectSse } from './lib/api/sse';
  import { clock } from './lib/stores/clock.svelte';
  import { graphStore } from './lib/stores/graphs.svelte';
  import { liveTrains } from './lib/stores/liveTrains.svelte';
  import { mapPrefs } from './lib/stores/mapPrefs.svelte';
  import { notifications } from './lib/stores/notifications.svelte';
  import { presets } from './lib/stores/presets.svelte';
  import { replays } from './lib/stores/replays.svelte';
  import { sims } from './lib/planner/sims.svelte';
  import { plans } from './lib/planner/plans.svelte';
  import { folders } from './lib/planner/folders.svelte';
  import LiveView from './routes/LiveView.svelte';
  import PlannerView from './routes/PlannerView.svelte';
  import ReplayView from './routes/ReplayView.svelte';
  import LoginView from './routes/LoginView.svelte';
  import Toasts from './features/Toasts.svelte';
  import ShortcutHelp from './features/ShortcutHelp.svelte';
  import { theme } from './lib/stores/theme.svelte';
  import { flash } from './lib/stores/flash.svelte';

  let sseStarted = false;

  onMount(() => {
    theme.init();
    session.probe();
  });

  $effect(() => {
    if (session.state !== 'in' || MOCK || sseStarted) return;
    sseStarted = true;
    notifications.loadSnapshot();
    replays.refreshIndex();
    connectSse({
      hello: (h, receivedAt) => {
        clock.beacon(h.serverTick, receivedAt, h.dayTime, h.dayTimeRate);
        graphStore.reconcile(h.graphs);
        if (h.rosterVersion !== liveTrains.ui.rosterVersion) liveTrains.refreshRoster();
      },
      trains: (t, receivedAt) => {
        clock.beacon(t.tick, receivedAt, t.dayTime, t.rate);
        liveTrains.applyTrains(t);
        graphStore.reconcile(t.g);
      },
      trainMeta: () => liveTrains.refreshRoster(),
      graph: (g) => graphStore.applyVersion(g.id, g.version),
      graphIndex: () => graphStore.loadRealSoon(),
      notify: (n) => notifications.apply(n),
      replay: (r) => replays.noteFinalized(r.id, r.notificationId),
      presets: () => presets.changed(),
      plans: () => plans.changed(),
      trainFolders: () => folders.changed(),
      sim: (s) => sims.onSse(s),
      // somebody (maybe in another browser) just changed the running timetable
      deployed: (d) =>
        flash.push(
          `${d.user} deployed ${d.applied} schedule${d.applied === 1 ? '' : 's'}`
            + (d.skipped ? `, ${d.skipped} skipped` : '')
            + ` (${d.mode.toLowerCase()})`,
          d.skipped ? 'warn' : 'info',
        ),
      reset: () => {
        graphStore.loadReal();
        liveTrains.refreshRoster();
        notifications.loadSnapshot();
      },
    });
  });

  function dimLabel(dim: string): string {
    return dim.replace('minecraft:', '');
  }
</script>

<header>
  <span class="brand mono">CREATE DISPATCHER</span>
  {#if session.state === 'in'}
    <nav>
      <button class:active={router.route === 'live'} onclick={() => router.navigate('live')}>Live</button>
      <button class:active={router.route === 'planner'} onclick={() => router.navigate('planner')}>Planner</button>
      <button class:active={router.route === 'replay'} onclick={() => router.navigate('replay')}>Replay</button>
    </nav>
    {#if graphStore.dims.length > 1}
      <select
        value={mapPrefs.dim || graphStore.dims[0]}
        onchange={(e) => (mapPrefs.dim = e.currentTarget.value)}
        title="dimension"
      >
        {#each graphStore.dims as dim (dim)}
          <option value={dim}>{dimLabel(dim)}</option>
        {/each}
      </select>
    {/if}
    <span class="spacer"></span>
    <span class="user mono">
      {session.me?.username}
      <span class="tier">{session.me?.tier}</span>
    </span>
    {#if !MOCK}
      <button onclick={() => session.logout()}>Log out</button>
    {/if}
  {:else}
    <span class="spacer"></span>
  {/if}
  <!-- outside the session branch: the login screen gets the toggle too -->
  <button
    class="icon"
    onclick={() => theme.toggle()}
    title="light / dark theme (T)"
    aria-label="toggle light or dark theme"
  >{theme.mode === 'dark' ? '☀' : '☾'}</button>
</header>

<main>
  {#if session.state === 'probing'}
    <div class="empty-state">connecting…</div>
  {:else if session.state === 'out'}
    <LoginView />
  {:else if router.route === 'planner'}
    <PlannerView />
  {:else if router.route === 'replay'}
    <ReplayView />
  {:else}
    <LiveView />
  {/if}
</main>

<Toasts />
<ShortcutHelp />

<style>
  header {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 6px 12px;
    background: var(--panel);
    border-bottom: 1px solid var(--border);
  }
  .brand {
    font-size: 12px;
    letter-spacing: 2px;
    color: var(--text-dim);
  }
  nav {
    display: flex;
    gap: 4px;
  }
  nav button {
    border-color: transparent;
    background: transparent;
  }
  nav button.active {
    border-color: var(--border);
    background: var(--control);
    color: var(--station);
  }
  .spacer {
    flex: 1;
  }
  .user {
    font-size: 12px;
    color: var(--text-dim);
  }
  .tier {
    color: var(--station);
    margin-left: 4px;
  }
  .icon {
    padding: 2px 8px;
    font-size: 13px;
    line-height: 1.2;
  }
  main {
    flex: 1;
    min-height: 0;
  }
</style>
