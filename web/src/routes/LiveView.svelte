<script lang="ts">
  import { onMount } from 'svelte';
  import MapCanvas from '../lib/map/MapCanvas.svelte';
  import PlaybackBar from '../features/PlaybackBar.svelte';
  import TrainInfoPanel from '../features/TrainInfoPanel.svelte';
  import TrainSearch from '../features/TrainSearch.svelte';
  import NotificationsPanel from '../features/NotificationsPanel.svelte';
  import DiagramDock from '../lib/diagram/DiagramDock.svelte';
  import Splitter from '../lib/ui/Splitter.svelte';
  import { layout } from '../lib/stores/layout.svelte';
  import { graphStore } from '../lib/stores/graphs.svelte';
  import { playback } from '../lib/stores/playback.svelte';
  import { liveTrains } from '../lib/stores/liveTrains.svelte';
  import { corridor } from '../lib/stores/corridor.svelte';
  import { MOCK } from '../lib/api/http';
  import { buildFixture } from '../lib/mock/fixtures';
  import { startMockLive, stopMockLive } from '../lib/mock/livePump';

  let mockMode = $state<'live' | 'playback'>('live');

  async function enterMode(mode: 'live' | 'playback') {
    mockMode = mode;
    if (mode === 'live') {
      playback.playing = false;
      playback.syncUi();
      await startMockLive();
    } else {
      stopMockLive();
      if (!playback.ui.loaded) {
        const fixture = buildFixture();
        if (graphStore.status === 'empty') await graphStore.load([fixture.graph]);
        playback.load(fixture.sim);
      }
      playback.playing = true;
      playback.syncUi();
    }
  }

  onMount(() => {
    if (MOCK) {
      enterMode('live');
      return () => {
        stopMockLive();
        corridor.close();
      };
    }
    graphStore.loadReal();
    liveTrains.refreshRoster();
    return () => corridor.close();
  });

  const source = $derived(MOCK ? (mockMode === 'live' ? 'live' : 'playback') : 'live');
</script>

<div class="live" class:with-dock={corridor.ui.open}>
  <div class="map-area">
    <MapCanvas {source} />
    <TrainInfoPanel />
    {#if source === 'live'}
      <TrainSearch />
      <NotificationsPanel />
    {/if}
    {#if MOCK}
      <div class="mock-toggle panel mono">
        <button class:active={mockMode === 'live'} onclick={() => enterMode('live')}>live-sim</button>
        <button class:active={mockMode === 'playback'} onclick={() => enterMode('playback')}>playback</button>
      </div>
    {/if}
    {#if !MOCK && graphStore.status === 'loading'}
      <div class="overlay empty-state">loading network…</div>
    {:else if !MOCK && graphStore.status === 'ready' && graphStore.graphs.length === 0}
      <div class="overlay empty-state">No rail networks on this server yet — lay some track.</div>
    {/if}
    {#if source === 'live' && !corridor.ui.open}
      <button class="diagram-toggle mono" onclick={() => corridor.toggle()}
        title="time-distance diagram between two stations">▤ diagram</button>
    {/if}
    <!-- The transport belongs to a playback surface: a sim loaded in the planner must not
         leave its bar (or its space-bar binding) sitting over the live map. -->
    {#if source === 'playback'}
      <PlaybackBar />
    {/if}
  </div>
  {#if corridor.ui.open && source === 'live'}
    <Splitter which="liveDock" axis="row" invert label="diagram height" />
    <!-- the vh cap keeps a size dragged on a big screen from swallowing a small one -->
    <div class="dock-wrap" style="height: min({layout.sizes.liveDock}px, 72vh)">
      <DiagramDock />
    </div>
  {/if}
</div>

<style>
  .live {
    height: 100%;
    display: grid;
    grid-template-rows: minmax(0, 1fr);
  }
  .live.with-dock {
    grid-template-rows: minmax(0, 1fr) auto auto;
  }
  .dock-wrap {
    display: grid;
    grid-template-rows: minmax(0, 1fr);
    min-height: 0;
    overflow: hidden;
  }
  .map-area {
    position: relative;
    min-height: 0;
    overflow: hidden;
  }
  .overlay {
    position: absolute;
    inset: 0;
    pointer-events: none;
  }
  .diagram-toggle {
    position: absolute;
    left: 12px;
    bottom: 12px;
    font-size: 11px;
    padding: 3px 8px;
    background: var(--panel);
    z-index: 4;
  }
  /* below the search bar, which owns the top-centre slot in live mode */
  .mock-toggle {
    position: absolute;
    top: 52px;
    left: 50%;
    transform: translateX(-50%);
    display: flex;
    gap: 4px;
    padding: 4px;
    z-index: 4;
  }
  .mock-toggle button {
    font-size: 11px;
    border-color: transparent;
    background: transparent;
  }
  .mock-toggle button.active {
    border-color: var(--border);
    background: var(--control);
    color: var(--station);
  }
</style>
