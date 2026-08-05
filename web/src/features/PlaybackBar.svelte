<script lang="ts">
  import { playback } from '../lib/stores/playback.svelte';
  import { fmtTime } from '../lib/diagram/timeFormat';

  const speeds = [1, 5, 20, 60, 300, 1000];

  function onKey(e: KeyboardEvent) {
    if (e.code !== 'Space' || !playback.ui.loaded) return;
    const target = e.target as HTMLElement;
    if (target.tagName === 'INPUT' || target.tagName === 'SELECT' || target.tagName === 'TEXTAREA') return;
    e.preventDefault();
    playback.toggle();
  }
</script>

<svelte:window onkeydown={onKey} />

{#if playback.ui.loaded}
  <div class="bar panel">
    <button onclick={() => playback.toggle()} title="play/pause (space)">
      {playback.ui.playing ? '⏸' : '▶'}
    </button>
    <button
      class:on={playback.ui.loop}
      onclick={() => playback.toggleLoop()}
      title="loop: restart at the end instead of stopping"
    >⟲</button>
    <select
      value={playback.ui.speedMult}
      onchange={(e) => playback.setSpeed(+e.currentTarget.value)}
      title="playback speed"
    >
      {#each speeds as s (s)}
        <option value={s}>{s}×</option>
      {/each}
    </select>
    <input
      class="scrub"
      type="range"
      min="0"
      max={playback.ui.ticks}
      step="1"
      value={playback.ui.t}
      oninput={(e) => playback.seek(+e.currentTarget.value)}
    />
    <span class="mono clock">{fmtTime(playback.meta.start, playback.meta.rate, playback.ui.t)}</span>
  </div>
{/if}

<style>
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
  .scrub {
    flex: 1;
  }
  button.on {
    color: var(--station);
    border-color: var(--station);
  }
  .clock {
    min-width: 72px;
    text-align: right;
    color: var(--text-dim);
  }
</style>
