<script lang="ts">
  import { onMount } from 'svelte';
  import { liveTrains } from '../lib/stores/liveTrains.svelte';
  import { graphStore } from '../lib/stores/graphs.svelte';
  import { focusMap } from '../lib/stores/mapFocus.svelte';
  import { colorFor } from '../lib/map/palette';
  import type { RosterEntry } from '../lib/api/types';
  import type { LoadedGraph, StationView } from '../lib/map/geometry';
  import { MOCK, api } from '../lib/api/http';

  /** Big servers run thousands of trains — the list is a jump target, not a roster browser. */
  const RESULT_CAP = 20;
  const STATION_CAP = 20;

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

  interface StationHit {
    name: string;
    /** CRN station tag, or null for untagged platforms (shown flat below the tags). */
    tag: string | null;
    g: LoadedGraph;
    st: StationView;
  }

  type Row =
    | { t: 'div'; label: string }
    | { t: 'train'; entry: RosterEntry; via: string }
    | { t: 'tag'; tag: string; count: number }
    | { t: 'station'; hit: StationHit };

  /** CRN station tag lookup: platform name -> tag (loaded once from the server). */
  let stationTags = $state({ loaded: false, byStation: new Map<string, string>() });

  async function ensureStationTags() {
    if (stationTags.loaded) return;
    if (MOCK) {
      const by = new Map<string, string>();
      for (const g of graphStore.graphs)
        for (const st of g.stations) {
          const tag = st.name.replace(/\s+\d+[a-z]?$/i, '');
          if (tag !== st.name) by.set(st.name, tag);
        }
      stationTags.byStation = by;
      stationTags.loaded = true;
      return;
    }
    try {
      const dto = await api<{ tags: { tag: string; stations: string[] }[] }>('/api/station-tags');
      const by = new Map<string, string>();
      for (const t of dto.tags) for (const s of t.stations) by.set(s, t.tag);
      stationTags.byStation = by;
    } catch {
      /* tags are an enhancement — station search still works flat */
    } finally {
      stationTags.loaded = true;
    }
  }

  /**
   * All platforms across every loaded graph, deduped by name and cached per graph generation
   * (a keystroke must not rescan thousands of stations; the catalog rebuilds on a republish).
   */
  let catalogGen = -1;
  const catalog = new Map<string, { g: LoadedGraph; st: StationView }>();
  function stationCatalog() {
    if (catalogGen !== graphStore.generation) {
      catalog.clear();
      for (const g of graphStore.graphs)
        for (const st of g.stations)
          if (!catalog.has(st.name)) catalog.set(st.name, { g, st });
      catalogGen = graphStore.generation;
    }
    return catalog;
  }

  /**
   * Name matches rank first (prefix, then substring), and only then the fields a name search
   * can plausibly miss: where it's going, where it is, what it's running. Ties break on name
   * so the same query always yields the same order — a moving roster must not reshuffle the
   * row under the cursor between keystrokes.
   *
   * Train rows come first, then stations: tagged platforms grouped under their CRN tag, and
   * untagged platforms flat below — so a tag name is always the first thing a station query
   * surfaces.
   */
  const found = $derived.by(() => {
    const q = query.trim().toLowerCase();
    if (!q) return { rows: [] as Row[], total: 0 };

    const scored: { rank: number; entry: RosterEntry; via: string }[] = [];
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
      if (rank >= 0) scored.push({ rank, entry, via });
    }
    scored.sort((a, b) => a.rank - b.rank || a.entry.name.localeCompare(b.entry.name));
    const rows: Row[] = scored
      .slice(0, RESULT_CAP)
      .map((s) => ({ t: 'train', entry: s.entry, via: s.via } as Row));

    const stationRows: Row[] = [];
    const hits: StationHit[] = [];
    for (const [name, { g, st }] of stationCatalog()) {
      const tag = stationTags.byStation.get(name) ?? null;
      const nl = name.toLowerCase();
      let score = -1;
      if (nl.startsWith(q)) score = 0;
      else if (nl.includes(q)) score = 1;
      else if (tag && tag.toLowerCase().includes(q)) score = 2;
      if (score >= 0) hits.push({ name, tag, g, st });
    }
    hits.sort((a, b) => a.name.localeCompare(b.name));
    const tagged = hits.filter((h) => h.tag);
    const untagged = hits.filter((h) => !h.tag);

    if (tagged.length || untagged.length) {
      if (rows.length) stationRows.push({ t: 'div', label: 'stations' });
      const byTag = new Map<string, StationHit[]>();
      for (const h of tagged) {
        const list = byTag.get(h.tag!) ?? [];
        list.push(h);
        byTag.set(h.tag!, list);
      }
      const tagOrder = [...byTag.keys()].sort((a, b) =>
        a.toLowerCase().localeCompare(b.toLowerCase()),
      );
      for (const tag of tagOrder) {
        const list = byTag.get(tag)!;
        stationRows.push({ t: 'tag', tag, count: list.length });
        for (const h of list) stationRows.push({ t: 'station', hit: h });
      }
      for (const h of untagged.slice(0, STATION_CAP)) stationRows.push({ t: 'station', hit: h });
    }
    rows.push(...stationRows);
    return { rows, total: scored.length };
  });

  /** Row indexes the keyboard may land on — tag/divider headers are skipped. */
  const actionable = $derived(
    found.rows
      .map((row, i) => (row.t === 'train' || row.t === 'station' ? i : -1))
      .filter((i) => i >= 0),
  );

  function show() {
    open = true;
    note = '';
    cursor = actionable[0] ?? 0;
    // roster fetches are trailing-throttled, so this is free when it was just refreshed
    liveTrains.refreshRoster();
    ensureStationTags();
  }

  function jumpTrain(id: string) {
    if (liveTrains.focus(id)) {
      open = false;
      field?.blur();
    } else {
      // selected all the same — the info panel and the dot fill in when positions arrive
      note = 'no live position for that train yet';
    }
  }

  function jumpStation(row: Row) {
    if (row.t !== 'station') return;
    const { g, st } = row.hit;
    focusMap(st.x, st.z, g.dims[st.dim] ?? g.dims[0] ?? '');
    open = false;
    field?.blur();
  }

  /** Jump whatever row the cursor is on; a tag header jumps to its first platform. */
  function jumpRow(i: number) {
    const row = found.rows[i];
    if (!row) return;
    if (row.t === 'train') jumpTrain(row.entry.id);
    else if (row.t === 'station') jumpStation(row);
    else if (row.t === 'tag')
      for (let k = i + 1; k < found.rows.length; k++) {
        const next = found.rows[k];
        if (next.t === 'station' && next.hit.tag === row.tag) {
          jumpStation(next);
          return;
        }
      }
  }

  function onKeydown(e: KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      show();
      const pos = actionable.indexOf(cursor);
      cursor = actionable[(pos + 1) % actionable.length] ?? actionable[0] ?? 0;
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      const pos = actionable.indexOf(cursor);
      const len = actionable.length;
      cursor = len ? actionable[(pos - 1 + len) % len] : 0;
    } else if (e.key === 'Enter') {
      jumpRow(cursor);
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
    cursor = actionable[0] ?? 0;
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
      placeholder="find trains, stations…"
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
      {#if found.rows.length === 0}
        <div class="empty mono">
          {liveTrains.ui.roster.length ? 'no matches' : 'roster still loading…'}
        </div>
      {:else}
        {#each found.rows as row, i (i)}
          {#if row.t === 'div'}
            <div class="divider mono">{row.label}</div>
          {:else if row.t === 'tag'}
            <button
              class="tag-row"
              title="jump to the first {row.tag} platform"
              onmouseenter={() => (cursor = i)}
              onclick={() => jumpRow(i)}
            >
              <span class="dot" style="background: var(--station)"></span>
              <span class="name">{row.tag}</span>
              <span class="via mono">{row.count}</span>
            </button>
          {:else if row.t === 'train'}
            <button
              class="row"
              class:sel={i === cursor}
              title="jump to this train and follow it — drag the map to let go"
              onmouseenter={() => (cursor = i)}
              onclick={() => jumpRow(i)}
            >
              <span class="dot" style="background: {colorFor(row.entry.id)}"></span>
              <span class="name">{row.entry.name}</span>
              {#if row.via}<span class="via mono">{row.via}</span>{/if}
              <span class="state mono">{row.entry.state.toLowerCase()}</span>
            </button>
          {:else}
            <button
              class="row"
              class:sel={i === cursor}
              title="jump the map to this station"
              onmouseenter={() => (cursor = i)}
              onclick={() => jumpRow(i)}
            >
              <span class="dot" style="background: var(--station)"></span>
              <span class="name">{row.hit.name}</span>
              {#if row.hit.tag}<span class="via mono">{row.hit.tag}</span>{/if}
            </button>
          {/if}
        {/each}
        {#if found.total > RESULT_CAP}
          <div class="more mono">{found.total - RESULT_CAP} more trains — keep typing</div>
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
  .tag-row {
    display: flex;
    align-items: center;
    gap: 7px;
    width: 100%;
    text-align: left;
    padding: 3px 6px;
    background: transparent;
    border: 1px solid transparent;
    border-radius: 3px;
  }
  .tag-row:hover {
    background: var(--control);
  }
  .divider {
    padding: 6px 8px 2px;
    font-size: 10px;
    color: var(--text-dim);
    text-transform: uppercase;
    letter-spacing: 0.08em;
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
