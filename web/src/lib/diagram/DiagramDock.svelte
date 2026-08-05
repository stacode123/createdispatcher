<script lang="ts">
  /**
   * Bottom dock of the live view: corridor A→B pickers (type a station group, or arm ⌖ and
   * click a station on the map) over the actual-vs-plan DiagramCanvas.
   */
  import DiagramCanvas from './DiagramCanvas.svelte';
  import { corridor } from '../stores/corridor.svelte';

  function commit(which: 'from' | 'to', event: Event) {
    corridor.setEndpoint(which, (event.target as HTMLInputElement).value.trim());
  }

  const planNote = $derived.by(() => {
    if (corridor.ui.planState === 'pending') return 'plan: simulating…';
    if (corridor.ui.planState === 'ready' && corridor.ui.planStale)
      return `plan: ${corridor.plan?.ageSeconds ?? '?'}s old, refreshing`;
    if (corridor.ui.planState === 'ready') {
      const cal = corridor.plan?.cal;
      const tuned = corridor.plan?.lines.filter((l) => l.cal && l.cal.n >= 8).length ?? 0;
      if (cal && cal.n >= 8)
        return `plan: dashed, drift-calibrated ×${(1 + cal.stretch).toFixed(2)} ±band`
          + (tuned ? ` · ${tuned} per-train` : '');
      return 'plan: dashed (calibrating…)';
    }
    return '';
  });
</script>

<div class="dock panel">
  <div class="head mono">
    <span class="title">corridor</span>
    <input
      list="corridor-groups"
      placeholder="from station"
      value={corridor.ui.from}
      onchange={(e) => commit('from', e)}
    />
    <button
      class="pick"
      class:armed={corridor.ui.picking === 'from'}
      onclick={() => corridor.arm('from')}
      title="click a station on the map"
    >⌖</button>
    <button class="swapbtn" onclick={() => corridor.swap()} title="swap direction">⇄</button>
    <input
      list="corridor-groups"
      placeholder="to station"
      value={corridor.ui.to}
      onchange={(e) => commit('to', e)}
    />
    <button
      class="pick"
      class:armed={corridor.ui.picking === 'to'}
      onclick={() => corridor.arm('to')}
      title="click a station on the map"
    >⌖</button>
    <datalist id="corridor-groups">
      {#each corridor.ui.groups as group (group)}<option value={group}></option>{/each}
    </datalist>
    <span class="note">
      {#if corridor.ui.picking}
        click a station on the map ({corridor.ui.picking})
      {:else if corridor.ui.status === 'error'}
        {corridor.ui.error}
      {:else if corridor.ui.status === 'loading'}
        loading…
      {:else}
        {planNote}
      {/if}
    </span>
    <button class="closebtn" onclick={() => corridor.toggle()} title="close the diagram">×</button>
  </div>
  <DiagramCanvas />
</div>

<style>
  .dock {
    display: grid;
    grid-template-rows: auto minmax(0, 1fr);
    min-height: 0;
    overflow: hidden;
    border-left: none;
    border-right: none;
    border-bottom: none;
    border-radius: 0;
  }
  .head {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 5px 10px;
    font-size: 11px;
    border-bottom: 1px solid var(--border);
  }
  .title {
    color: var(--text-dim);
    margin-right: 4px;
  }
  input {
    width: 150px;
    font-size: 11px;
    padding: 2px 6px;
  }
  .pick,
  .swapbtn,
  .closebtn {
    padding: 1px 6px;
    font-size: 11px;
  }
  .pick.armed {
    color: var(--station);
    border-color: var(--station);
  }
  .note {
    margin-left: auto;
    color: var(--text-dim);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .closebtn {
    border-color: transparent;
    background: transparent;
  }
</style>
