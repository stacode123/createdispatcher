<script lang="ts">
  import { onMount } from 'svelte';
  import MapCanvas from '../lib/map/MapCanvas.svelte';
  import { replays } from '../lib/stores/replays.svelte';
  import { graphStore } from '../lib/stores/graphs.svelte';
  import { KIND_COLOR } from '../lib/stores/notifications.svelte';
  import { router } from '../router.svelte';
  import { fmtTime } from '../lib/diagram/timeFormat';
  import { MOCK } from '../lib/api/http';

  const speeds = [1, 2, 5, 20];

  onMount(() => {
    if (!MOCK) replays.refreshIndex();
  });

  $effect(() => {
    const id = router.params.id;
    if (id && id !== replays.ui.loadedId) replays.load(id);
  });

  const meta = $derived(replays.ui.meta);
  const versionOk = $derived.by(() => {
    void graphStore.generation;
    if (!meta) return true;
    const g = graphStore.byId.get(meta.graphId);
    return !!g && g.version === meta.graphVersion;
  });
  const eventFrac = $derived(
    meta ? (replays.eventTick() - meta.startTick) / Math.max(1, meta.endTick - meta.startTick) : 0,
  );

  function age(ms: number): string {
    const minutes = Math.floor((Date.now() - ms) / 60000);
    return minutes < 1 ? 'just now' : minutes < 60 ? `${minutes}m ago` : `${Math.floor(minutes / 60)}h ago`;
  }
</script>

<div class="replay">
  <aside class="panel list">
    <div class="col-head mono">REPLAYS ({replays.ui.index.length})</div>
    {#if replays.ui.index.length === 0}
      <div class="empty-state">
        No replays captured yet.<br />
        Deadlocks, detours and critical signal waits record automatically.
      </div>
    {:else}
      <div class="rows">
        {#each replays.ui.index as entry (entry.id)}
          <button
            class="row"
            class:active={replays.ui.loadedId === entry.id}
            onclick={() => router.navigate('replay', { id: entry.id })}
          >
            <span class="head-line">
              <span class="dot" style="background: {KIND_COLOR[entry.kind]}"></span>
              <span class="msg">{entry.message}</span>
            </span>
            <span class="sub mono">
              {age(entry.createdMs)} · {Math.round(entry.durationTicks / 20)}s · {entry.trains} trains
            </span>
          </button>
        {/each}
      </div>
    {/if}
  </aside>
  <div class="center">
    <MapCanvas source="replay" />
    {#if meta && !versionOk}
      <div class="overlay empty-state">
        The network changed since this replay was recorded —<br />
        its geometry can no longer be shown.
      </div>
    {:else if !meta && !replays.ui.loading}
      <div class="overlay empty-state">Pick a replay.</div>
    {/if}
    {#if meta}
      <div class="bar panel">
        <button onclick={() => replays.toggle()} title="play/pause">
          {replays.ui.playing ? '⏸' : '▶'}
        </button>
        <button
          class:on={replays.ui.loop}
          onclick={() => replays.toggleLoop()}
          title="loop: restart at the end instead of stopping"
        >⟲</button>
        <select value={replays.ui.speedMult} onchange={(e) => replays.setSpeed(+e.currentTarget.value)}>
          {#each speeds as s (s)}
            <option value={s}>{s}×</option>
          {/each}
        </select>
        <div class="scrub-wrap">
          <input
            class="scrub"
            type="range"
            min={meta.startTick}
            max={meta.endTick}
            step="1"
            value={replays.ui.t}
            oninput={(e) => replays.seek(+e.currentTarget.value)}
          />
          <span class="event-mark" style="left: {eventFrac * 100}%" title="moment the issue was detected"></span>
        </div>
        <span class="mono clock">{fmtTime(0, 0, Math.max(0, Math.round(replays.ui.t - meta.startTick)))}</span>
      </div>
    {/if}
  </div>
</div>

<style>
  .replay {
    display: grid;
    grid-template-columns: 340px 1fr;
    grid-template-rows: minmax(0, 1fr);
    gap: 8px;
    height: 100%;
    padding: 8px;
  }
  .replay > * {
    min-height: 0;
    overflow: hidden;
  }
  .list {
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }
  .col-head {
    padding: 8px 10px;
    font-size: 11px;
    letter-spacing: 1px;
    color: var(--text-dim);
    border-bottom: 1px solid var(--border);
  }
  .rows {
    overflow-y: auto;
  }
  .row {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 3px;
    width: 100%;
    text-align: left;
    padding: 8px 10px;
    background: transparent;
    border: none;
    border-bottom: 1px solid var(--grid);
    border-radius: 0;
  }
  .row:hover {
    background: var(--control);
  }
  .row.active {
    background: var(--control);
    box-shadow: inset 2px 0 0 var(--station);
  }
  .head-line {
    display: flex;
    gap: 7px;
    align-items: flex-start;
  }
  .dot {
    width: 8px;
    height: 8px;
    border-radius: 2px;
    transform: rotate(45deg);
    flex-shrink: 0;
    margin-top: 3px;
  }
  .msg {
    font-size: 12px;
    line-height: 1.35;
  }
  .sub {
    font-size: 10px;
    color: var(--text-dim);
    padding-left: 15px;
  }
  .center {
    position: relative;
    border: 1px solid var(--border);
    border-radius: 4px;
    overflow: hidden;
  }
  .overlay {
    position: absolute;
    inset: 0;
    pointer-events: none;
  }
  .bar {
    position: absolute;
    left: 12px;
    right: 12px;
    bottom: 12px;
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 10px;
  }
  .scrub-wrap {
    position: relative;
    flex: 1;
    display: flex;
    align-items: center;
  }
  .bar button.on {
    color: var(--station);
    border-color: var(--station);
  }
  .scrub {
    width: 100%;
  }
  .event-mark {
    position: absolute;
    top: -3px;
    width: 7px;
    height: 7px;
    margin-left: -3px;
    background: var(--crit);
    transform: rotate(45deg);
    pointer-events: none;
  }
  .clock {
    min-width: 56px;
    text-align: right;
    color: var(--text-dim);
  }
</style>
