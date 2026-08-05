<script lang="ts">
  import { onMount } from 'svelte';
  import { liveTrains } from '../lib/stores/liveTrains.svelte';
  import { colorFor } from '../lib/map/palette';
  import type { RosterEntry } from '../lib/api/types';

  /** Big servers run thousands of trains — the list is a jump target, not a roster browser. */
  const RESULT_CAP = 20;

  let field = $state<HTMLInputElement | null>(null);
  let query = $state('');
  let open = $state(false);
  let cursor = $state(0);
  /** Set when a jump had nowhere to go; cleared on the next keystroke. */
  let note = $state('');

  interface Hit {
    entry: RosterEntry;
    /** Why this train matched, when it wasn't the name — shown so the row isn't a mystery. */
    via: string;
  }

  /**
   * Name matches rank first (prefix, then substring), and only then the fields a name search
   * can plausibly miss: where it's going, where it is, what it's running. Ties break on name
   * so the same query always yields the same order — a moving roster must not reshuffle the
   * row under the cursor between keystrokes.
   */
  const found = $derived.by(() => {
    const q = query.trim().toLowerCase();
    if (!q) return { hits: [] as Hit[], total: 0 };
    const scored: { hit: Hit; rank: number }[] = [];
    for (const entry of liveTrains.ui.roster) {
      const name = entry.name.toLowerCase();
      let rank = -1;
      let via = '';
      if (name.startsWith(q)) rank = 0;
      else if (name.includes(q)) rank = 1;
      else if (entry.destination.toLowerCase().includes(q)) {
        rank = 2;
        via = `→ ${entry.destination}`;
      } else if (entry.currentStation.toLowerCase().includes(q)) {
        rank = 3;
        via = `at ${entry.currentStation}`;
      } else if (entry.scheduleTitle.toLowerCase().includes(q)) {
        rank = 4;
        via = entry.scheduleTitle;
      }
      if (rank >= 0) scored.push({ hit: { entry, via }, rank });
    }
    scored.sort((a, b) => a.rank - b.rank || a.hit.entry.name.localeCompare(b.hit.entry.name));
    return { hits: scored.slice(0, RESULT_CAP).map((s) => s.hit), total: scored.length };
  });

  function show() {
    open = true;
    note = '';
    // roster fetches are trailing-throttled, so this is free when it was just refreshed
    liveTrains.refreshRoster();
  }

  function jump(id: string) {
    if (liveTrains.focus(id)) {
      open = false;
      field?.blur();
    } else {
      // selected all the same — the info panel and the dot fill in when positions arrive
      note = 'no live position for that train yet';
    }
  }

  function onKeydown(e: KeyboardEvent) {
    const hits = found.hits;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      show();
      cursor = hits.length ? (cursor + 1) % hits.length : 0;
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      cursor = hits.length ? (cursor - 1 + hits.length) % hits.length : 0;
    } else if (e.key === 'Enter') {
      if (hits[cursor]) jump(hits[cursor].entry.id);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      if (query) {
        query = '';
        cursor = 0;
        note = '';
      } else {
        open = false;
        field?.blur();
      }
    }
  }

  function onInput() {
    cursor = 0;
    note = '';
    open = true;
  }

  onMount(() => {
    // "/" is the reflex for a map search; ctrl/cmd-K is the reflex for everything else.
    const hotkey = (e: KeyboardEvent) => {
      if (e.defaultPrevented) return;
      const target = e.target as HTMLElement | null;
      const typing = target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA' || target?.isContentEditable;
      const wanted = (e.key === '/' && !typing) || (e.key.toLowerCase() === 'k' && (e.ctrlKey || e.metaKey));
      if (!wanted) return;
      e.preventDefault();
      show();
      field?.focus();
      field?.select();
    };
    window.addEventListener('keydown', hotkey);
    return () => window.removeEventListener('keydown', hotkey);
  });
</script>

<!-- Blur closes the list, but only after a click on a row has had its chance to land. -->
<div class="search" role="search">
  <div class="bar panel">
    <span class="glyph mono">⌕</span>
    <input
      bind:this={field}
      type="text"
      placeholder="find a train…"
      bind:value={query}
      onfocus={show}
      oninput={onInput}
      onkeydown={onKeydown}
      onblur={() => setTimeout(() => (open = false), 120)}
    />
    {#if query}
      <button
        class="clear"
        title="clear (esc)"
        onclick={() => {
          query = '';
          cursor = 0;
          note = '';
          field?.focus();
        }}
      >×</button>
    {:else}
      <span class="hint mono">/</span>
    {/if}
  </div>
  {#if open && query.trim()}
    <div class="results panel">
      {#if found.hits.length === 0}
        <div class="empty mono">
          {liveTrains.ui.roster.length ? 'no train matches' : 'roster still loading…'}
        </div>
      {:else}
        {#each found.hits as hit, i (hit.entry.id)}
          <button
            class="row"
            class:sel={i === cursor}
            title="jump to this train and follow it — drag the map to let go"
            onmouseenter={() => (cursor = i)}
            onclick={() => jump(hit.entry.id)}
          >
            <span class="dot" style="background: {colorFor(hit.entry.id)}"></span>
            <span class="name">{hit.entry.name}</span>
            {#if hit.via}<span class="via mono">{hit.via}</span>{/if}
            <span class="state mono">{hit.entry.state.toLowerCase()}</span>
          </button>
        {/each}
        {#if found.total > found.hits.length}
          <div class="more mono">{found.total - found.hits.length} more — keep typing</div>
        {/if}
      {/if}
      {#if note}<div class="note mono">{note}</div>{/if}
    </div>
  {/if}
</div>

<style>
  .search {
    position: absolute;
    top: 12px;
    left: 50%;
    transform: translateX(-50%);
    width: 280px;
    z-index: 6;
  }
  .bar {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 2px 6px;
  }
  .bar input {
    flex: 1;
    min-width: 0;
    border: none;
    background: transparent;
    padding: 3px 0;
    outline: none;
  }
  .glyph {
    color: var(--text-dim);
    font-size: 13px;
  }
  .hint {
    font-size: 10px;
    color: var(--text-dim);
    border: 1px solid var(--border);
    border-radius: 3px;
    padding: 0 4px;
  }
  .clear {
    padding: 0 5px;
    border-color: transparent;
    background: transparent;
    color: var(--text-dim);
  }
  .results {
    margin-top: 4px;
    max-height: 260px;
    overflow-y: auto;
    padding: 3px;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 7px;
    width: 100%;
    text-align: left;
    padding: 4px 6px;
    background: transparent;
    border: 1px solid transparent;
    border-radius: 3px;
  }
  .row.sel {
    background: var(--control);
    border-color: var(--station);
  }
  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    flex-shrink: 0;
  }
  .name {
    flex: 1;
    font-size: 12px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .via {
    font-size: 10px;
    color: var(--station);
    max-width: 110px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    flex-shrink: 0;
  }
  .state {
    font-size: 10px;
    color: var(--text-dim);
    flex-shrink: 0;
  }
  .empty,
  .more,
  .note {
    padding: 7px 8px;
    font-size: 11px;
    color: var(--text-dim);
    text-align: center;
  }
  .note {
    color: var(--warn);
  }
</style>
