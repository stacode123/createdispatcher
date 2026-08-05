<script lang="ts">
  import { onMount } from 'svelte';
  import { liveTrains } from '../lib/stores/liveTrains.svelte';

  let speed = $state(0);
  const entry = $derived(liveTrains.ui.roster.find((r) => r.id === liveTrains.ui.selected));

  onMount(() => {
    const timer = setInterval(() => {
      const id = liveTrains.ui.selected;
      const sample = id ? liveTrains.newest(id) : undefined;
      speed = sample ? Math.abs(sample.speed) * 72 : 0; // blocks/tick -> km/h
    }, 500);
    return () => clearInterval(timer);
  });
</script>

{#if liveTrains.ui.selected}
  <div class="panel info">
    <div class="head">
      <strong>{entry?.name ?? '…'}</strong>
      <button class="close" onclick={() => liveTrains.select(null)}>×</button>
    </div>
    <div class="rows mono">
      <div>state <span>{entry?.state.toLowerCase() ?? ''}</span></div>
      <div>speed <span>{Math.round(speed)} km/h</span></div>
      {#if entry?.scheduleTitle}<div>schedule <span>{entry.scheduleTitle}</span></div>{/if}
      {#if entry?.destination}<div>to <span>{entry.destination}</span></div>{/if}
      {#if entry?.currentStation}<div>at <span>{entry.currentStation}</span></div>{/if}
      {#if entry}<div>length <span>{entry.length} b · {entry.carriages} car</span></div>{/if}
    </div>
    <button onclick={() => (liveTrains.ui.follow = !liveTrains.ui.follow)}>
      {liveTrains.ui.follow ? 'Unfollow' : 'Follow'}
    </button>
  </div>
{/if}

<style>
  .info {
    position: absolute;
    top: 44px;
    right: 12px;
    width: 240px;
    padding: 10px 12px;
    z-index: 4;
  }
  .head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 8px;
  }
  .close {
    padding: 0 6px;
    border-color: transparent;
    background: transparent;
  }
  .rows {
    font-size: 11px;
    color: var(--text-dim);
    display: grid;
    gap: 3px;
    margin-bottom: 10px;
  }
  .rows span {
    color: var(--text);
    float: right;
  }
</style>
