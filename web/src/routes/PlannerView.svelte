<script lang="ts">
  /**
   * Timetable planner (W5): presets | map | trains with pointer-DnD preset
   * assignment (ghost chip, Escape cancels; click-to-assign as the armed
   * fallback), removal toggles, and the bottom sim dock — run flow, map
   * playback of the finished run, conflict/root-cause panels, diagram tab.
   */
  import { onMount } from 'svelte';
  import MapCanvas from '../lib/map/MapCanvas.svelte';
  import PlaybackBar from '../features/PlaybackBar.svelte';
  import PresetLibrary from '../lib/planner/PresetLibrary.svelte';
  import SimDock from '../lib/planner/SimDock.svelte';
  import { presets } from '../lib/stores/presets.svelte';
  import { liveTrains } from '../lib/stores/liveTrains.svelte';
  import { graphStore } from '../lib/stores/graphs.svelte';
  import { playback } from '../lib/stores/playback.svelte';
  import Splitter from '../lib/ui/Splitter.svelte';
  import { layout } from '../lib/stores/layout.svelte';
  import { sims, hasLiveSchedule } from '../lib/planner/sims.svelte';
  import { folders } from '../lib/planner/folders.svelte';
  import { session } from '../lib/stores/session.svelte';
  import { MOCK } from '../lib/api/http';
  import type { RosterEntry } from '../lib/api/types';

  /** Caps the UNFILED trains only — see the list derivation. */
  const RENDER_CAP = 250;
  let filter = $state('');
  /** "scheduled" is the timetable-participating set (RUNNING + IDLE) — see hasLiveSchedule. */
  let scheduleFilter = $state<'all' | 'scheduled' | 'unscheduled'>('all');

  const canPlan = $derived(session.me?.tier === 'planner' || session.me?.tier === 'deployer');

  /** "idle" reads like "no schedule" but means the opposite — spell each state out. */
  const STATE_HELP: Record<string, string> = {
    RUNNING: 'travelling to its next destination',
    IDLE: 'has a schedule but is not travelling — dwelling at a station under its wait conditions, or unable to find a route',
    PAUSED: 'schedule stopped — sits on the track as an obstacle',
    COMPLETED: 'finished a non-cyclic schedule — sits on the track as an obstacle',
    MANUAL: 'no schedule — driven by hand, sits on the track as an obstacle',
    DERAILED: 'derailed — sits on the track as an obstacle',
  };

  onMount(() => {
    if (MOCK) {
      void (async () => {
        const { buildFixture } = await import('../lib/mock/fixtures');
        const fixture = buildFixture();
        if (graphStore.status === 'empty') await graphStore.load([fixture.graph]);
        if (!liveTrains.ui.roster.length)
          liveTrains.setRoster(
            fixture.sim.trains.map((t, i) => ({
              id: `mock-${i}`,
              name: t.n,
              graphId: 'fixture',
              length: 30,
              carriages: 4,
              doubleEnded: true,
              // %7: paused stock (an obstacle a clear spares), %11: idle — scheduled but
              // dwelling, so a clear DOES take it, the case the roster label obscures
              state: (i % 7 === 3 ? 'PAUSED' : i % 11 === 5 ? 'IDLE' : 'RUNNING') as
                'PAUSED' | 'IDLE' | 'RUNNING',
              scheduleTitle: i % 7 === 3 ? 'Depot stabling' : 'Fixture line',
              destination: '',
              currentStation: '',
              // some unowned, to exercise the owner column's fallback
              owner: i % 5 === 2 ? '' : `driver${(i % 4) + 1}`,
              line: `S${(i % 3) + 1}`,
              category: i % 2 === 0 ? 'Regional' : 'Express',
            })),
            1,
          );
      })();
      return;
    }
    if (!liveTrains.ui.roster.length) liveTrains.refreshRoster();
    if (graphStore.status === 'empty') graphStore.loadReal();
    void folders.refresh();
  });

  const list = $derived.by(() => {
    const query = filter.trim().toLowerCase();
    const all = liveTrains.ui.roster;
    const matched = query
      ? all.filter(
          (t) =>
            t.name.toLowerCase().includes(query) ||
            t.state.toLowerCase().includes(query) ||
            t.scheduleTitle.toLowerCase().includes(query) ||
            t.destination.toLowerCase().includes(query) ||
            // the full path covers nested folders too — "Depot/Sub" matches both "depot" and "sub"
            folders.folderOfTrain(t.id).toLowerCase().includes(query),
        )
      : all;
    // counts come from the text-filtered set, so each button says what it would show
    const scheduled = matched.filter((t) => hasLiveSchedule(t.state));
    const rows =
      scheduleFilter === 'scheduled' ? scheduled
      : scheduleFilter === 'unscheduled' ? matched.filter((t) => !hasLiveSchedule(t.state))
      : matched;
    // Filing a train is a deliberate act — a folder must show everything in it, or it lies
    // about its own contents. So the cap falls on the unfiled pile only, which is the part
    // that runs to thousands on a busy server and the part nobody is curating.
    const filed: RosterEntry[] = [];
    const unfiled: RosterEntry[] = [];
    for (const train of rows) (folders.folderOfTrain(train.id) ? filed : unfiled).push(train);
    return {
      shown: [...filed, ...unfiled.slice(0, RENDER_CAP)],
      hidden: Math.max(0, unfiled.length - RENDER_CAP),
      total: rows.length,
      all: matched.length,
      scheduled: scheduled.length,
      unscheduled: matched.length - scheduled.length,
    };
  });

  const trainRows = $derived.by(() => {
    const rows = folders.group('t', list.shown, (train) => folders.folderOfTrain(train.id), {
      searching: filter.trim() !== '',
    });
    // Mid-drag, Unfiled has to exist as a target even when empty — otherwise a roster where
    // every train is filed offers no way to drag one back out.
    if (trainDrag && !rows.some((r) => r.kind === 'folder' && r.path === ''))
      rows.push({ kind: 'folder', path: '', label: 'Unfiled', depth: 0, collapsed: false, hasItems: true, hasContent: true });
    return rows;
  });
  const trainFolderCount = $derived(trainRows.filter((r) => r.kind === 'folder').length);
  const knownTrainFolders = $derived(
    folders.known([
      ...liveTrains.ui.roster.map((t) => folders.folderOfTrain(t.id)),
      ...folders.ui.extra.t,
    ]),
  );

  let renamingFolder = $state('');
  let folderRenameDraft = $state('');
  /** The "+ folder" input, when open. Trains get filed by dragging them onto a folder. */
  let newFolderOpen = $state(false);
  let newFolderDraft = $state('');

  function commitNewFolder() {
    const draft = newFolderDraft.trim();
    newFolderOpen = false;
    newFolderDraft = '';
    if (draft) folders.addFolder('t', draft);
  }

  /** Deployer-only: whether this session may run the roster's auto-sort. */
  const canAutoSort = $derived(session.me?.tier === 'deployer');

  /** The auto-sort confirmation popup — the overwrite choice is destructive. */
  let autoSortOpen = $state(false);
  let autoSortOverwrite = $state(false);
  let autoSortBusy = $state(false);
  let autoSortDone: number | null = $state(null);

  function openAutoSort() {
    autoSortOverwrite = false;
    autoSortDone = null;
    autoSortOpen = true;
  }

  function closeAutoSort() {
    if (autoSortBusy) return;
    autoSortOpen = false;
    autoSortDone = null;
  }

  async function runAutoSort() {
    if (autoSortBusy) return;
    autoSortBusy = true;
    autoSortDone = null;
    try {
      const changed = await folders.autoSortTrains(autoSortOverwrite);
      if (changed === 0) autoSortDone = 'nothing to change';
      else autoSortDone = `filed ${changed} train${changed === 1 ? '' : 's'} by category and line`;
    } finally {
      autoSortBusy = false;
    }
  }

  /**
   * Whether each train row carries its owner underneath. A view preference, so it
   * lives in localStorage rather than on the server — "who runs this?" is a question
   * you are either asking all session or not at all.
   */
  const OWNER_KEY = 'dispatcher.planner.owners';
  let showOwners = $state(readShowOwners());

  function readShowOwners(): boolean {
    try {
      return localStorage.getItem(OWNER_KEY) === '1';
    } catch {
      /* private mode or corrupt value — fall back to the schedule line */
      return false;
    }
  }

  /** Owners are off by default — a row with nothing under it is the quiet baseline. */
  function toggleOwners() {
    showOwners = !showOwners;
    try {
      localStorage.setItem(OWNER_KEY, showOwners ? '1' : '0');
    } catch {
      /* not persisting the choice is survivable — it still holds for this session */
    }
  }

  /**
   * An inline editor that opens on click has to take the caret with it. Selecting has to
   * wait a microtask: bind:value lands in a later effect than the action, so selecting now
   * would select an empty field and typing would append to the old name instead of replacing.
   */
  function autofocus(node: HTMLInputElement) {
    node.focus();
    queueMicrotask(() => node.select());
  }

  /**
   * Dragging a train row files it — the mirror of dragging a preset. It needs its own drag
   * state rather than sims.ui.drag: that one means "a preset is looking for a train", and
   * reusing it would light up every train row as a drop target for a train.
   */
  let trainDragCandidate: { id: string; name: string; x: number; y: number } | null = null;
  let trainDrag = $state<{ id: string; name: string; x: number; y: number } | null>(null);
  let hoverTrainFolder = $state<string | null>(null);
  let suppressTrainClick = false;

  function trainPointerDown(e: PointerEvent, id: string, name: string) {
    // the row's own controls (file, keep/remove, unassign, folder input) keep their behavior
    if (e.button !== 0 || (e.target as HTMLElement).closest('button, input, select')) return;
    trainDragCandidate = { id, name, x: e.clientX, y: e.clientY };
    suppressTrainClick = false;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  }

  function trainPointerMove(e: PointerEvent) {
    if (!trainDragCandidate) return;
    if (!trainDrag) {
      const dist = Math.abs(e.clientX - trainDragCandidate.x)
        + Math.abs(e.clientY - trainDragCandidate.y);
      if (dist > 6) trainDrag = { ...trainDragCandidate, x: e.clientX, y: e.clientY };
    }
    if (trainDrag) {
      trainDrag = { ...trainDrag, x: e.clientX, y: e.clientY };
      hoverTrainFolder = trainFolderAt(e.clientX, e.clientY);
    }
  }

  function trainPointerUp(e: PointerEvent) {
    trainDragCandidate = null;
    hoverTrainFolder = null;
    if (!trainDrag) return;
    suppressTrainClick = true;
    const folder = trainFolderAt(e.clientX, e.clientY);
    // "" is the Unfiled group — a real target, so test for null, not falsiness
    if (folder !== null) void folders.setTrainFolder(trainDrag.id, folder);
    trainDrag = null;
  }

  /** The train-folder path under this point, "" for Unfiled, null for no folder. */
  function trainFolderAt(x: number, y: number): string | null {
    const header = document
      .elementFromPoint(x, y)
      ?.closest('[data-train-folder]') as HTMLElement | null;
    return header ? (header.getAttribute('data-train-folder') ?? '') : null;
  }

  /**
   * Dragging a folder onto another nests it there — the drag-and-drop route to the same
   * "A/B" path nesting the rename box already does by hand. Mirrors the train-filing drag
   * above, but the only valid drop targets are folders that are not the dragged folder
   * itself or one of its own descendants (nesting a folder under its own child is nonsense).
   */
  let folderDragCandidate: { path: string; label: string; x: number; y: number } | null = null;
  let folderDrag = $state<{ path: string; label: string; x: number; y: number } | null>(null);
  let hoverFolderTarget = $state<string | null>(null);
  let suppressFolderClick = false;

  function folderPointerDown(e: PointerEvent, path: string) {
    if (!path || e.button !== 0 || (e.target as HTMLElement).closest('button.delfolder, input')) return;
    folderDragCandidate = { path, label: path.split('/').pop() ?? path, x: e.clientX, y: e.clientY };
    suppressFolderClick = false;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  }

  function folderPointerMove(e: PointerEvent) {
    if (!folderDragCandidate) return;
    if (!folderDrag) {
      const dist = Math.abs(e.clientX - folderDragCandidate.x)
        + Math.abs(e.clientY - folderDragCandidate.y);
      if (dist > 6) folderDrag = { ...folderDragCandidate, x: e.clientX, y: e.clientY };
    }
    if (folderDrag) {
      folderDrag = { ...folderDrag, x: e.clientX, y: e.clientY };
      const target = trainFolderAt(e.clientX, e.clientY);
      hoverFolderTarget =
        target !== null && target !== folderDrag.path && !target.startsWith(folderDrag.path + '/')
          ? target
          : null;
    }
  }

  function folderPointerUp() {
    folderDragCandidate = null;
    if (!folderDrag) return;
    suppressFolderClick = true;
    const { path: from, label } = folderDrag;
    const target = hoverFolderTarget;
    hoverFolderTarget = null;
    folderDrag = null;
    if (target === null) return;
    const to = target === '' ? label : `${target}/${label}`;
    if (to === from) return;
    folders.renameExtra('t', from, to);
    void folders.renameTrainFolder(from, to);
  }

  function folderPointerCancel() {
    folderDragCandidate = null;
    folderDrag = null;
    hoverFolderTarget = null;
  }

  const source = $derived(playback.ui.loaded && sims.ui.phase === 'done' ? 'playback' : 'none');

  function trainClick(trainId: string) {
    if (suppressTrainClick) {
      suppressTrainClick = false;
      return;
    }
    if (sims.ui.armed) sims.assign(trainId, sims.ui.armed.presetId, sims.ui.armed.name);
  }

  let importingTrain = $state('');
  let importError = $state('');

  async function importPreset(id: string, fallback: string) {
    if (importingTrain) return;
    importingTrain = id;
    importError = '';
    const error = await presets.createFromTrain(id, fallback);
    importingTrain = '';
    if (error) importError = error;
  }

  function onKey(e: KeyboardEvent) {
    if (e.key === 'Escape') {
      sims.cancelDrag();
      sims.ui.armed = null;
      trainDragCandidate = null;
      trainDrag = null;
      hoverTrainFolder = null;
      folderDragCandidate = null;
      folderDrag = null;
      hoverFolderTarget = null;
    }
  }
</script>

<svelte:window onkeydown={onKey} />

<div class="planner" style="--dock-height: {layout.sizes.plannerDock}px">
  <div
    class="main"
    style="grid-template-columns: min({layout.sizes.presetCol}px, 40vw) 8px minmax(0, 1fr) 8px min({layout.sizes.trainCol}px, 40vw)"
  >
    <aside class="panel left">
      <PresetLibrary />
    </aside>
    <Splitter which="presetCol" axis="col" label="preset panel width" />
    <div class="center">
      <MapCanvas {source} />
      {#if source === 'playback'}
        <PlaybackBar />
      {/if}
      {#if sims.ui.armed}
        <div class="armed-banner mono">assigning “{sims.ui.armed.name}” — click a train (Esc cancels)</div>
      {/if}
    </div>
    <Splitter which="trainCol" axis="col" invert label="train panel width" />
    <aside class="panel right" class:droppable={sims.ui.drag != null}>
      <div class="col-head mono">
        TRAINS ({liveTrains.ui.roster.length})
        <button
          class="headbtn push"
          class:on={showOwners}
          onclick={toggleOwners}
          title={showOwners ? 'hide train owners' : 'show who owns each train'}
        >owner</button>
        {#if canPlan}
          <button
            class="headbtn"
            onclick={() => {
              newFolderOpen = !newFolderOpen;
              newFolderDraft = '';
            }}
            title="new folder — then drag trains onto it to file them"
          >+ folder</button>
        {/if}
        {#if canAutoSort}
          <button
            class="headbtn"
            onclick={openAutoSort}
            title="file the whole roster by each train's CRN category, then line"
          >sort</button>
        {/if}
      </div>
      {#if newFolderOpen}
        <div class="newfolderrow">
          <input
            class="folderinput"
            use:autofocus
            list="train-folders"
            placeholder="folder name — A/B nests"
            bind:value={newFolderDraft}
            onblur={commitNewFolder}
            onkeydown={(e) => {
              if (e.key === 'Enter') e.currentTarget.blur();
              if (e.key === 'Escape') {
                newFolderDraft = '';
                e.currentTarget.blur();
              }
            }}
          />
          <datalist id="train-folders">
            {#each knownTrainFolders as path (path)}<option value={path}></option>{/each}
          </datalist>
        </div>
      {/if}
      <input class="search" type="text" placeholder="filter trains…" bind:value={filter} />
      <div class="filters mono">
        <button
          class:active={scheduleFilter === 'all'}
          onclick={() => (scheduleFilter = 'all')}
          title="every train on the server"
        >all <span class="n">{list.all}</span></button>
        <button
          class:active={scheduleFilter === 'scheduled'}
          onclick={() => (scheduleFilter = 'scheduled')}
          title="running a schedule (running or idle) — the trains a cleared timetable removes"
        >scheduled <span class="n">{list.scheduled}</span></button>
        <button
          class:active={scheduleFilter === 'unscheduled'}
          onclick={() => (scheduleFilter = 'unscheduled')}
          title="not running one (paused, completed, manual, derailed) — these stay on the network as obstacles"
        >unscheduled <span class="n">{list.unscheduled}</span></button>
      </div>
      {#if importError}
        <div class="imperr mono">{importError}</div>
      {/if}
      {#if liveTrains.ui.roster.length === 0}
        <div class="empty-state">No trains on this server.</div>
      {:else}
        <div class="list">
          {#if list.hidden > 0}
            <div class="cap mono">
              {list.hidden} unfiled train{list.hidden === 1 ? '' : 's'} hidden — narrow with the
              filter, or file them into folders
            </div>
          {:else if list.total === 0}
            <div class="empty-state">no trains match this filter</div>
          {/if}
          {#each trainRows as row (row.kind + ':' + row.path)}
          {#if row.kind === 'folder'}
            {#if row.path !== '' || trainFolderCount > 1}
              <div
                class="folder mono"
                class:droppable={trainDrag != null
                  || (folderDrag != null && row.path !== folderDrag.path
                    && !row.path.startsWith(folderDrag.path + '/'))}
                class:drop={(trainDrag != null && hoverTrainFolder === row.path)
                  || (folderDrag != null && hoverFolderTarget === row.path)}
                data-train-folder={row.path}
                style="padding-left: {6 + row.depth * 10}px"
              >
                <button
                  class="twist"
                  onclick={() => folders.toggle('t', row.path)}
                  title={row.collapsed ? 'expand' : 'collapse'}
                >{row.collapsed ? '▸' : '▾'}</button>
                {#if renamingFolder === row.path && row.path !== ''}
                  <input
                    class="foldername"
                    use:autofocus
                    bind:value={folderRenameDraft}
                    onblur={() => {
                      const from = renamingFolder;
                      const to = folderRenameDraft.trim();
                      renamingFolder = '';
                      if (from && to && to !== from) {
                        folders.renameExtra('t', from, to);
                        void folders.renameTrainFolder(from, to);
                      }
                    }}
                    onkeydown={(e) => {
                      if (e.key === 'Enter') e.currentTarget.blur();
                      if (e.key === 'Escape') renamingFolder = '';
                    }}
                  />
                {:else}
                  <button
                    class="foldername-btn"
                    onpointerdown={(e) => folderPointerDown(e, row.path)}
                    onpointermove={folderPointerMove}
                    onpointerup={folderPointerUp}
                    onpointercancel={folderPointerCancel}
                    onclick={() => {
                      if (suppressFolderClick) {
                        suppressFolderClick = false;
                        return;
                      }
                      folders.toggle('t', row.path);
                    }}
                    ondblclick={() => {
                      if (!row.path) return;
                      renamingFolder = row.path;
                      folderRenameDraft = row.path;
                    }}
                    title={row.path ? `${row.path} — double-click to rename, drag onto another folder to nest it` : 'trains in no folder'}
                  >📁 {row.label}</button>
                  {#if canPlan && row.path !== '' && !row.hasContent}
                    <button
                      class="delfolder"
                      title="remove this empty folder"
                      onclick={() => folders.removeFolder('t', row.path)}
                    >×</button>
                  {/if}
                {/if}
              </div>
              {#if row.path !== '' && !row.hasContent && !row.collapsed}
                <div
                  class="dropnote mono"
                  class:drop={trainDrag != null && hoverTrainFolder === row.path}
                  data-train-folder={row.path}
                  style="padding-left: {16 + row.depth * 10}px"
                >drag trains here</div>
              {/if}
            {/if}
          {:else}
            {#each row.items as train (train.id)}
              {@const assignment = sims.ui.assignments[train.id]}
              {@const excepted = sims.isException(train.id)}
              {@const dropped = !assignment && sims.ui.removeScheduled && !excepted
                && hasLiveSchedule(train.state)}
              <div
                class="train"
                class:target={sims.ui.drag != null || sims.ui.armed != null}
                class:removed={dropped || (!sims.ui.removeScheduled && excepted)}
                style="padding-left: {10 + row.depth * 10}px"
                data-train-id={train.id}
                role="button"
                tabindex="0"
                onpointerdown={(e) => trainPointerDown(e, train.id, train.name)}
                onpointermove={trainPointerMove}
                onpointerup={trainPointerUp}
                onpointercancel={() => {
                  trainDragCandidate = null;
                  hoverTrainFolder = null;
                  trainDrag = null;
                }}
                onclick={() => trainClick(train.id)}
                onkeydown={(e) => e.key === 'Enter' && trainClick(train.id)}
              >
                <div class="row">
                  <span class="name">{train.name}</span>
                  <span class="controls-inline">
                    {#if canPlan}
                      <button
                        class="impt mono"
                        title="import this train's schedule as a preset"
                        disabled={importingTrain === train.id}
                        onclick={(e) => {
                          e.stopPropagation();
                          void importPreset(train.id, train.name);
                        }}
                      >{importingTrain === train.id ? '…' : '⤓'}</button>
                      <button
                        class="excl mono"
                        class:on={excepted}
                        title={sims.ui.removeScheduled
                          ? (excepted
                            ? 'kept: this train keeps running its own schedule'
                            : 'keep this train running its own schedule')
                          : (excepted
                            ? 'removed from the sim'
                            : 'remove this train from the sim')}
                        onclick={(e) => {
                          e.stopPropagation();
                          sims.toggleException(train.id);
                        }}
                      >{sims.ui.removeScheduled
                        ? (excepted ? 'kept' : '⊕')
                        : (excepted ? 'removed' : '⌦')}</button>
                    {/if}
                    <span
                      class="chip {train.state.toLowerCase()}"
                      title="{STATE_HELP[train.state] ?? ''}{dropped
                        ? ' · cleared from this sim'
                        : sims.ui.removeScheduled && !assignment ? ' · stays on the network' : ''}"
                    >{train.state.toLowerCase()}</span>
                  </span>
                </div>
                {#if assignment}
                  <div class="assignchip mono">
                    ⇥ {assignment.presetName}
                    <button
                      class="unassign"
                      title="drop this assignment"
                      onclick={(e) => {
                        e.stopPropagation();
                        sims.unassign(train.id);
                      }}
                    >×</button>
                  </div>
                {/if}
                {#if showOwners}
                  <div class="sub mono" class:unowned={!train.owner}>
                    {train.owner || 'no owner'}
                  </div>
                {/if}
              </div>
            {/each}
          {/if}
          {/each}
        </div>
      {/if}
    </aside>
  </div>
  {#if canPlan}
    <Splitter which="plannerDock" axis="row" invert label="dock height" />
    <SimDock />
  {/if}
</div>

{#if sims.ui.drag}
  <div class="ghost mono" style="left: {sims.ui.drag.x + 12}px; top: {sims.ui.drag.y + 8}px">
    ⇥ {sims.ui.drag.name}
  </div>
{/if}
{#if trainDrag}
  <div class="ghost filing mono" style="left: {trainDrag.x + 12}px; top: {trainDrag.y + 8}px">
    📁 {trainDrag.name}
  </div>
{/if}
{#if folderDrag}
  <div class="ghost filing mono" style="left: {folderDrag.x + 12}px; top: {folderDrag.y + 8}px">
    📁 {folderDrag.label}
  </div>
{/if}

{#if autoSortOpen}
  <div class="autosort-backdrop" role="presentation" onclick={closeAutoSort}>
    <div
      class="autosort dialog panel"
      role="dialog"
      aria-modal="true"
      aria-label="Sort trains into folders"
      tabindex="-1"
      onclick={(e) => e.stopPropagation()}
      onkeydown={() => {}}
    >
      <div class="head mono">
        <span class="title">sort trains</span>
        <span class="dim">category → line</span>
        <span class="spacer"></span>
        <button class="x" onclick={closeAutoSort} disabled={autoSortBusy}>×</button>
      </div>
      <div class="body">
        <p class="note">File the roster by each train's CRN category, then its line as a
          sub-folder — “Category/Line”. Trains with no category are left unfiled.</p>
        <div class="modes">
          <label class:sel={!autoSortOverwrite}>
            <input type="radio" value="fill" checked={!autoSortOverwrite}
                   onclick={() => (autoSortOverwrite = false)} />
            <span class="mname">Fill unfiled only</span>
            <span class="mdesc dim">trains already in a folder are left where they are; nothing is moved</span>
          </label>
          <label class:sel={autoSortOverwrite}>
            <input type="radio" value="overwrite" checked={autoSortOverwrite}
                   onclick={() => (autoSortOverwrite = true)} />
            <span class="mname">Redo everything</span>
            <span class="mdesc dim">every train's folder is replaced — a manual filing is overwritten</span>
          </label>
        </div>
        {#if autoSortDone}<p class="ok mono">{autoSortDone}</p>{/if}
        {#if folders.ui.error}<p class="err mono">{folders.ui.error}</p>{/if}
      </div>
      <div class="foot">
        {#if autoSortDone}
          <button class="go" onclick={closeAutoSort}>done</button>
        {:else}
          <button onclick={closeAutoSort} disabled={autoSortBusy}>cancel</button>
          <button class="sortbtn" class:overwrite={autoSortOverwrite} disabled={autoSortBusy}
            onclick={() => void runAutoSort()}>
            {autoSortBusy ? 'sorting…' : autoSortOverwrite ? '⇩ redo everything' : '⇩ sort'}
          </button>
        {/if}
      </div>
    </div>
  </div>
{/if}

<style>
  .planner {
    display: grid;
    grid-template-rows: minmax(0, 1fr) auto auto;
    height: 100%;
  }
  .main {
    display: grid;
    /* columns come from the layout store (inline) — the splitters live between them */
    /* pin the single row to the container — an auto row would stretch to the train list's
       content height, ballooning the map canvas and breaking every camera calculation */
    grid-template-rows: minmax(0, 1fr);
    min-height: 0;
    padding: 8px;
  }
  .main > * {
    min-height: 0;
    overflow: hidden;
  }
  .planner > :global(.dock) {
    height: min(var(--dock-height), 72vh);
  }
  .center {
    position: relative;
    border: 1px solid var(--border);
    border-radius: 4px;
    overflow: hidden;
  }
  .armed-banner {
    position: absolute;
    top: 12px;
    left: 50%;
    transform: translateX(-50%);
    background: var(--panel);
    border: 1px solid var(--station);
    color: var(--station);
    border-radius: 4px;
    padding: 3px 10px;
    font-size: 11px;
    z-index: 6;
    pointer-events: none;
  }
  .col-head {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 10px;
    font-size: 11px;
    letter-spacing: 1px;
    color: var(--text-dim);
    border-bottom: 1px solid var(--border);
  }
  .headbtn {
    font-size: 11px;
    padding: 1px 6px;
    letter-spacing: 0;
  }
  /* The first header button carries the gap, so the row still packs right when
     "+ folder" is hidden from viewers. */
  .push {
    margin-left: auto;
  }
  .headbtn.on {
    color: var(--station);
    border-color: var(--station);
  }
  /* An unowned train is a fact about the train, not a missing value — say so quietly. */
  .sub.unowned {
    opacity: 0.55;
    font-style: italic;
  }
  .newfolderrow {
    padding: 6px 8px 0;
  }
  /* Column flex, not a magic offset: the header/search/filter block grows and shrinks. */
  .right {
    display: flex;
    flex-direction: column;
  }
  .search {
    margin: 6px 8px 4px;
    width: calc(100% - 16px);
  }
  .filters {
    display: flex;
    gap: 4px;
    padding: 0 8px 6px;
    border-bottom: 1px solid var(--border);
  }
  .filters button {
    flex: 1;
    font-size: 10px;
    padding: 3px 4px;
    border-color: transparent;
    background: transparent;
    color: var(--text-dim);
    white-space: nowrap;
  }
  .filters button:hover {
    color: var(--text);
  }
  .filters button.active {
    border-color: var(--border);
    background: var(--control);
    color: var(--station);
  }
  .filters .n {
    opacity: 0.7;
  }
  .list {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
  }
  .cap {
    padding: 6px 10px;
    font-size: 10px;
    color: var(--warn);
  }
  .train {
    padding: 6px 10px;
    border-bottom: 1px solid var(--grid);
    touch-action: none;
  }
  .folder {
    display: flex;
    align-items: center;
    gap: 2px;
    padding: 3px 6px;
    background: var(--panel-raised);
    border-bottom: 1px solid var(--border);
    font-size: 11px;
  }
  /* mid-drag: every folder is a target, the one under the pointer is THE target */
  .folder.droppable {
    outline: 1px dashed var(--border);
    outline-offset: -2px;
  }
  .folder.drop {
    outline: 1px dashed var(--station);
    background: var(--control);
  }
  .folder.drop .foldername-btn {
    color: var(--station);
  }
  .twist,
  .foldername-btn {
    border: none;
    background: transparent;
    color: var(--text-dim);
    font-size: 11px;
    padding: 1px 3px;
    cursor: pointer;
  }
  .foldername-btn {
    flex: 1;
    text-align: left;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    touch-action: none;
  }
  .foldername-btn:hover {
    color: var(--station);
  }
  .foldername,
  .folderinput {
    flex: 1;
    font-size: 11px;
    padding: 1px 4px;
  }
  .folderinput {
    width: 100%;
  }
  .delfolder {
    border: none;
    background: transparent;
    color: var(--text-dim);
    font-size: 12px;
    line-height: 1;
    padding: 1px 4px;
    cursor: pointer;
  }
  .delfolder:hover {
    color: var(--crit);
  }
  /* An empty folder is invisible without this — and it doubles the drop target. */
  .dropnote {
    padding-top: 5px;
    padding-bottom: 5px;
    font-size: 10px;
    color: var(--text-dim);
    border-bottom: 1px solid var(--grid);
  }
  .dropnote.drop {
    color: var(--station);
    background: var(--control);
  }
  .train.target {
    cursor: copy;
  }
  .train.target:hover {
    background: var(--control);
    box-shadow: inset 2px 0 0 var(--station);
  }
  .right.droppable {
    border-color: var(--station);
  }
  .train.removed .name {
    text-decoration: line-through;
    color: var(--text-dim);
  }
  .row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
  }
  .controls-inline {
    display: flex;
    align-items: center;
    gap: 4px;
    flex-shrink: 0;
  }
  .name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .sub {
    font-size: 10px;
    color: var(--text-dim);
    margin-top: 2px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .assignchip {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    margin-top: 3px;
    font-size: 10px;
    padding: 1px 6px;
    border-radius: 3px;
    border: 1px solid var(--warn);
    color: var(--warn);
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .unassign {
    border: none;
    background: transparent;
    color: var(--warn);
    font-size: 11px;
    padding: 0 2px;
    cursor: pointer;
  }
  .unassign:hover {
    color: var(--crit);
  }
  .excl {
    font-size: 12px;
    line-height: 1;
    min-width: 24px;
    padding: 3px 6px;
    border-color: var(--border);
    background: var(--control);
    color: var(--text-dim);
  }
  .excl:hover {
    color: var(--station);
    border-color: var(--station);
  }
  .excl.on {
    font-size: 10px;
    color: var(--ok);
    border-color: var(--ok);
  }
  .impt {
    font-size: 12px;
    line-height: 1;
    min-width: 24px;
    padding: 3px 6px;
    border-color: var(--border);
    background: var(--control);
    color: var(--text-dim);
  }
  .impt:hover {
    color: var(--station);
    border-color: var(--station);
  }
  .impt:disabled {
    opacity: 0.6;
    cursor: default;
  }
  .imperr {
    padding: 4px 10px;
    font-size: 10px;
    color: var(--crit);
    border-bottom: 1px solid var(--grid);
  }
  .chip {
    font-size: 10px;
    font-family: var(--font-data);
    padding: 1px 6px;
    border-radius: 3px;
    border: 1px solid var(--border);
    color: var(--text-dim);
    flex-shrink: 0;
  }
  .chip.running {
    color: var(--ok);
    border-color: var(--ok);
  }
  .chip.derailed {
    color: var(--crit);
    border-color: var(--crit);
  }
  .chip.paused,
  .chip.completed {
    color: var(--warn);
    border-color: var(--warn);
  }
  .ghost.filing {
    border-color: var(--station);
    color: var(--station);
  }
  .ghost {
    position: fixed;
    z-index: 50;
    pointer-events: none;
    background: var(--panel-raised);
    border: 1px solid var(--warn);
    color: var(--warn);
    border-radius: 3px;
    padding: 2px 8px;
    font-size: 11px;
    box-shadow: 0 2px 10px rgba(0, 0, 0, 0.5);
  }
  .autosort-backdrop {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.45);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 30;
  }
  .autosort.dialog {
    width: 480px;
    max-width: calc(100vw - 32px);
    display: flex;
    flex-direction: column;
  }
  .autosort .head {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 7px 12px;
    font-size: 11px;
    border-bottom: 1px solid var(--border);
    background: var(--panel-raised);
  }
  .autosort .title {
    letter-spacing: 1px;
    text-transform: uppercase;
    color: var(--text-dim);
  }
  .autosort .spacer {
    flex: 1;
  }
  .autosort .x {
    border-color: transparent;
    background: transparent;
    color: var(--text-dim);
  }
  .autosort .body {
    padding: 12px;
  }
  .autosort .note {
    margin: 0 0 12px;
    font-size: 11px;
    line-height: 1.4;
  }
  .autosort .modes {
    display: grid;
    gap: 6px;
  }
  .autosort .modes label {
    display: grid;
    grid-template-columns: auto auto 1fr;
    align-items: baseline;
    gap: 8px;
    padding: 7px 9px;
    border: 1px solid var(--border);
    border-radius: 4px;
    cursor: pointer;
  }
  .autosort .modes label.sel {
    border-color: var(--station);
    background: var(--control);
  }
  .autosort .mname {
    font-weight: 600;
  }
  .autosort .mdesc {
    font-size: 11px;
  }
  .autosort .dim {
    color: var(--text-dim);
  }
  .autosort .ok {
    margin: 12px 0 0;
    color: var(--ok);
    font-size: 11px;
  }
  .autosort .err {
    margin: 12px 0 0;
    color: var(--crit);
    font-size: 11px;
  }
  .autosort .foot {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    padding: 9px 12px;
    border-top: 1px solid var(--border);
    background: var(--panel-raised);
  }
  .autosort .go {
    color: var(--ok);
    border-color: var(--ok);
  }
  .autosort .sortbtn {
    color: var(--ok);
    border-color: var(--ok);
  }
  .autosort .sortbtn.overwrite {
    color: var(--warn);
    border-color: var(--warn);
  }
</style>
