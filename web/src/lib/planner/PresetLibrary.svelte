<script lang="ts">
  /**
   * The preset library panel (W4): list and a detail drawer with the
   * whitelisted condition-value editor. Trains are imported as presets from
   * the train panel, not here. Everything the server refuses to edit renders
   * read-only.
   */
  import { onMount } from 'svelte';
  import { presets } from '../stores/presets.svelte';
  import { session } from '../stores/session.svelte';
  import { sims } from './sims.svelte';
  import { folders } from './folders.svelte';
  import type { PresetFieldSpec, PresetNodeDto } from '../api/types';

  /** Folder header being renamed inline, and its draft text. */
  let renamingFolder = $state('');
  let folderDraft = $state('');

  const grouped = $derived.by(() => {
    const rows = folders.group('p', presets.ui.list, (p) => p.folder);
    // Mid-drag, Unfiled has to exist as a target even when empty — otherwise a library
    // where everything is filed offers no way to drag a preset back out.
    if (sims.ui.drag && !rows.some((r) => r.kind === 'folder' && r.path === ''))
      rows.push({ kind: 'folder', path: '', label: 'Unfiled', depth: 0, collapsed: false, hasItems: true, hasContent: true });
    return rows;
  });
  const folderCount = $derived(grouped.filter((r) => r.kind === 'folder').length);
  const knownFolders = $derived(
    folders.known([...presets.ui.list.map((p) => p.folder), ...folders.ui.extra.p]),
  );

  /** The "+ folder" input, when open. Presets get filed by dragging them onto a folder. */
  let newFolderOpen = $state(false);
  let newFolderDraft = $state('');

  function commitNewFolder() {
    const draft = newFolderDraft.trim();
    newFolderOpen = false;
    newFolderDraft = '';
    if (draft) folders.addFolder('p', draft);
  }

  function startRename(path: string) {
    renamingFolder = path;
    folderDraft = path;
  }

  /**
   * An inline editor that opens on double-click has to take the caret with it. Selecting has
   * to wait a microtask: bind:value lands in a later effect than the action, so selecting now
   * would select an empty field and typing would append to the old name instead of replacing.
   */
  function autofocus(node: HTMLInputElement) {
    node.focus();
    queueMicrotask(() => node.select());
  }

  function commitRename() {
    const from = renamingFolder;
    const to = folderDraft.trim();
    renamingFolder = '';
    if (from && to && to !== from) {
      folders.renameExtra('p', from, to);
      void presets.renameFolder(from, to);
    }
  }
  let confirmDelete = $state('');
  let nameDraft = $state('');

  const canEdit = $derived(session.me?.tier === 'planner' || session.me?.tier === 'deployer');
  const detail = $derived(presets.ui.detail);

  onMount(() => {
    void presets.refresh();
  });

  const ID_LABELS: Record<string, string> = {
    'create:destination': 'Destination',
    'createrailwaysnavigator:prioritized_destination_instruction': 'Prioritized destination',
    'create:throttle': 'Throttle',
    'tramways:set_primary_limit': 'Primary limit',
    'tramways:request_stop': 'Request stop',
    'railways:waypoint_destination': 'Waypoint',
    'create:rename': 'Rename',
    'createrailwaysnavigator:travel_section': 'Travel section',
    'createrailwaysnavigator:reset_timings': 'Reset timings',
    'railways:redstone_link': 'Redstone link',
    'create:delay': 'Delay',
    'create:time_of_day': 'Time of day',
    'realism:time_of_day_realistic': 'Time of day (realistic)',
    'createrailwaysnavigator:dynamic_delay': 'Dynamic delay',
    'createrailwaysnavigator:train_separation': 'Train separation',
    'railways:loaded': 'Station loaded',
  };
  const TIME_UNITS = ['ticks', 'seconds', 'minutes'];
  const TRAIN_FILTERS = ['any train', 'same line', 'same category', 'same name'];

  function labelFor(id: string): string {
    return ID_LABELS[id] ?? (id.split(':')[1] ?? id).replace(/_/g, ' ');
  }

  function age(ms: number): string {
    const minutes = Math.max(0, Math.round((Date.now() - ms) / 60_000));
    if (minutes < 60) return `${minutes}m`;
    const hours = Math.floor(minutes / 60);
    if (hours < 48) return `${hours}h`;
    return `${Math.floor(hours / 24)}d`;
  }

  function displayFields(node: PresetNodeDto): [string, string | number][] {
    const editableKeys = new Set(node.editable.map((f) => f.key));
    return Object.entries(node.fields).filter(([key, value]) => !editableKeys.has(key) && `${value}` !== '');
  }

  function selectOptions(key: string): string[] | null {
    if (key === 'TimeUnit') return TIME_UNITS;
    if (key === 'TrainFilter') return TRAIN_FILTERS;
    return null;
  }

  function commitEdit(
    entry: number,
    target: 'instruction' | 'condition',
    col: number,
    row: number,
    spec: PresetFieldSpec,
    raw: string,
  ) {
    if (!detail) return;
    let value: string | number = raw;
    if (spec.type === 'int') {
      const parsed = Math.round(Number(raw));
      if (!Number.isFinite(parsed)) return;
      value = Math.max(spec.min ?? 0, Math.min(spec.max ?? parsed, parsed));
    }
    void presets.editValue(detail.id, { entry, target, col, row, key: spec.key, value });
  }

  function openDetail(id: string) {
    confirmDelete = '';
    void presets.open(id);
    const row = presets.ui.list.find((p) => p.id === id);
    nameDraft = row?.name ?? '';
  }

  /**
   * Pointer DnD onto train rows. The drag starts on 6px of movement alone — gating it on
   * a hold time as well would make a quick flick-drag open the detail drawer instead.
   * A press that never moves stays a click.
   */
  let dragCandidate: { id: string; name: string; x: number; y: number } | null = null;
  let suppressClick = false;
  /** Folder header the pointer is currently over mid-drag, for the drop highlight. */
  let hoverFolder = $state<string | null>(null);

  function rowPointerDown(e: PointerEvent, id: string, name: string) {
    if (e.button !== 0) return;
    dragCandidate = { id, name, x: e.clientX, y: e.clientY };
    suppressClick = false;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  }

  function rowPointerMove(e: PointerEvent) {
    if (!dragCandidate) return;
    if (!sims.ui.drag) {
      const dist = Math.abs(e.clientX - dragCandidate.x) + Math.abs(e.clientY - dragCandidate.y);
      if (dist > 6)
        sims.ui.drag = { presetId: dragCandidate.id, name: dragCandidate.name, x: e.clientX, y: e.clientY };
    }
    if (sims.ui.drag) {
      sims.ui.drag = { ...sims.ui.drag, x: e.clientX, y: e.clientY };
      hoverFolder = folderAt(e.clientX, e.clientY);
    }
  }

  /**
   * One drag, two meanings — decided by what you drop on, not by the gesture: a train row
   * assigns the preset, a folder header in this panel files it. They are different
   * elements in different panels, so nothing is ambiguous.
   */
  function rowPointerUp(e: PointerEvent) {
    dragCandidate = null;
    hoverFolder = null;
    if (!sims.ui.drag) return;
    suppressClick = true;
    // pointer capture keeps events on the source row — hit-test the drop point instead
    const under = document.elementFromPoint(e.clientX, e.clientY);
    const train = under?.closest('[data-train-id]') as HTMLElement | null;
    if (train?.dataset.trainId) {
      sims.assign(train.dataset.trainId, sims.ui.drag.presetId, sims.ui.drag.name);
    } else {
      const folder = folderAt(e.clientX, e.clientY);
      // "" is the Unfiled group — a real target, so test for null, not falsiness
      if (folder !== null) void presets.move(sims.ui.drag.presetId, folder);
    }
    sims.ui.drag = null;
  }

  /** The preset-folder path under this point, "" for Unfiled, null for no folder. */
  function folderAt(x: number, y: number): string | null {
    const header = document
      .elementFromPoint(x, y)
      ?.closest('[data-preset-folder]') as HTMLElement | null;
    return header ? (header.getAttribute('data-preset-folder') ?? '') : null;
  }

  function rowClick(id: string) {
    if (suppressClick) {
      suppressClick = false;
      return;
    }
    openDetail(id);
  }
</script>

<div class="library">
  {#if !canEdit}
    <div class="col-head mono">PRESETS</div>
    <div class="empty-state">Preset access needs the planner tier.</div>
  {:else if detail || presets.ui.detailStatus === 'loading'}
    <div class="col-head mono">
      <button class="backbtn" onclick={() => presets.closeDetail()}>‹</button>
      PRESET
    </div>
    {#if presets.ui.detailStatus === 'loading'}
      <div class="empty-state">loading…</div>
    {:else if detail}
      <div class="drawer">
        <div class="namerow">
          <input
            class="namefield"
            bind:value={nameDraft}
            disabled={presets.ui.busy}
            onchange={() => nameDraft.trim() && nameDraft.trim() !== detail.name && presets.rename(detail.id, nameDraft.trim())}
          />
        </div>
        <div class="meta mono">
          {#if detail.folder}📁 {detail.folder} · {/if}{detail.source} · {detail.entries} entries · {detail.cyclic ? 'cyclic' : 'once'}
          <button
            class="dup mono wide"
            title="duplicate"
            disabled={presets.ui.busy}
            onclick={() => void presets.duplicate(detail.id)}
          >⧉ duplicate</button>
        </div>
        {#if presets.ui.editError}
          <div class="err mono">{presets.ui.editError}</div>
        {/if}
        <div class="entries">
          {#each detail.schedule as entry, entryIndex (entryIndex)}
            <div class="entry">
              <div class="head mono">
                <span class="idx">{entryIndex + 1}</span>
                <span class="kind">{labelFor(entry.instruction.id)}</span>
              </div>
              {#each displayFields(entry.instruction) as [key, value] (key)}
                <div class="field mono"><span class="fk">{key}</span><span class="fv">{value}</span></div>
              {/each}
              {#each entry.instruction.editable as spec (spec.key)}
                <div class="field mono">
                  <span class="fk">{spec.key}</span>
                  <input
                    class="edit"
                    type="number"
                    min={spec.min}
                    max={spec.max}
                    value={entry.instruction.fields[spec.key]}
                    disabled={presets.ui.busy}
                    onchange={(e) => commitEdit(entryIndex, 'instruction', 0, 0, spec, e.currentTarget.value)}
                  />
                </div>
              {/each}
              {#each entry.conditions as column, colIndex (colIndex)}
                {#if colIndex > 0}<div class="or mono">— or —</div>{/if}
                {#each column as condition, rowIndex (rowIndex)}
                  <div class="cond">
                    <div class="cond-name mono">{labelFor(condition.id)}</div>
                    {#each displayFields(condition) as [key, value] (key)}
                      <div class="field mono"><span class="fk">{key}</span><span class="fv">{value}</span></div>
                    {/each}
                    {#each condition.editable as spec (spec.key)}
                      <div class="field mono">
                        <span class="fk">{spec.key}</span>
                        {#if selectOptions(spec.key)}
                          <select
                            class="edit"
                            value={`${condition.fields[spec.key]}`}
                            disabled={presets.ui.busy}
                            onchange={(e) => commitEdit(entryIndex, 'condition', colIndex, rowIndex, spec, e.currentTarget.value)}
                          >
                            {#each selectOptions(spec.key)! as option, optionIndex (option)}
                              <option value={`${optionIndex}`}>{option}</option>
                            {/each}
                          </select>
                        {:else if spec.type === 'int'}
                          <input
                            class="edit"
                            type="number"
                            min={spec.min}
                            max={spec.max}
                            value={condition.fields[spec.key]}
                            disabled={presets.ui.busy}
                            onchange={(e) => commitEdit(entryIndex, 'condition', colIndex, rowIndex, spec, e.currentTarget.value)}
                          />
                        {:else}
                          <input
                            class="edit wide"
                            type="text"
                            value={condition.fields[spec.key]}
                            disabled={presets.ui.busy}
                            onchange={(e) => commitEdit(entryIndex, 'condition', colIndex, rowIndex, spec, e.currentTarget.value)}
                          />
                        {/if}
                      </div>
                    {/each}
                  </div>
                {/each}
              {/each}
            </div>
          {/each}
        </div>
      </div>
    {/if}
  {:else}
    <div class="col-head mono">
      PRESETS ({presets.ui.list.length})
      <button
        class="importbtn push"
        title="new folder — then drag presets onto it to file them"
        onclick={() => {
          newFolderOpen = !newFolderOpen;
          newFolderDraft = '';
        }}
      >+ folder</button>
    </div>
    {#if newFolderOpen}
      <div class="newfolderrow">
        <input
          class="foldername"
          use:autofocus
          list="preset-folders"
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
        <datalist id="preset-folders">
          {#each knownFolders as path (path)}<option value={path}></option>{/each}
        </datalist>
      </div>
    {/if}
    {#if presets.ui.status === 'error'}
      <div class="empty-state">{presets.ui.error}</div>
    {:else if presets.ui.status !== 'ready'}
      <div class="empty-state">loading…</div>
    {:else if presets.ui.list.length === 0}
      <div class="empty-state">No presets yet — import a train's schedule from the train list, or save one in-game from the Advanced Schedule editor.</div>
    {:else}
      <div class="list">
        {#each grouped as row (row.kind + ':' + row.path)}
          {#if row.kind === 'folder'}
            {#if row.path !== '' || folderCount > 1}
              <div
                class="folder mono"
                class:droppable={sims.ui.drag != null}
                class:drop={sims.ui.drag != null && hoverFolder === row.path}
                data-preset-folder={row.path}
                style="padding-left: {6 + row.depth * 10}px"
              >
                <button
                  class="twist"
                  onclick={() => folders.toggle('p', row.path)}
                  title={row.collapsed ? 'expand' : 'collapse'}
                >{row.collapsed ? '▸' : '▾'}</button>
                {#if renamingFolder === row.path && row.path !== ''}
                  <input
                    class="foldername"
                    use:autofocus
                    bind:value={folderDraft}
                    onblur={commitRename}
                    onkeydown={(e) => {
                      if (e.key === 'Enter') e.currentTarget.blur();
                      if (e.key === 'Escape') renamingFolder = '';
                    }}
                  />
                {:else}
                  <button
                    class="foldername-btn"
                    ondblclick={() => row.path && startRename(row.path)}
                    onclick={() => folders.toggle('p', row.path)}
                    title={row.path ? `${row.path} — double-click to rename` : 'presets in no folder'}
                  >📁 {row.label}</button>
                  {#if (row.path !== '' && !row.hasContent)}
                    <button
                      class="delfolder"
                      title="remove this empty folder"
                      onclick={() => folders.removeFolder('p', row.path)}
                    >×</button>
                  {/if}
                {/if}
              </div>
              {#if (row.path !== '' && !row.hasContent) && !row.collapsed}
                <div
                  class="dropnote mono"
                  class:drop={sims.ui.drag != null && hoverFolder === row.path}
                  data-preset-folder={row.path}
                  style="padding-left: {16 + row.depth * 10}px"
                >drag presets here</div>
              {/if}
            {/if}
          {:else}
            {#each row.items as preset (preset.id)}
            <div
              class="preset"
              class:armed={sims.ui.armed?.presetId === preset.id}
              style="padding-left: {10 + row.depth * 10}px"
            >
              <button
                class="row"
                title="click: open · drag onto a train to assign"
                onpointerdown={(e) => rowPointerDown(e, preset.id, preset.name)}
                onpointermove={rowPointerMove}
                onpointerup={rowPointerUp}
                onpointercancel={() => {
                  dragCandidate = null;
                  hoverFolder = null;
                  sims.ui.drag = null;
                }}
                onclick={() => rowClick(preset.id)}
              >
                <span class="name">{preset.name}</span>
                <span class="entries mono">{preset.entries}</span>
              </button>
              <div class="subrow">
                <span class="sub mono">{preset.source} · {age(preset.updatedMs)}</span>
                <span class="actions">
                  <button
                    class="assignbtn mono"
                    class:on={sims.ui.armed?.presetId === preset.id}
                    title="assign to a train: arm, then click a train (or just drag the row)"
                    onclick={() => sims.arm(preset.id, preset.name)}
                  >⇥</button>
                  <button
                    class="dup mono"
                    title="duplicate"
                    disabled={presets.ui.busy}
                    onclick={() => void presets.duplicate(preset.id)}
                  >⧉</button>
                  {#if confirmDelete === preset.id}
                    <button class="del confirm mono" onclick={() => void presets.remove(preset.id)}>sure?</button>
                  {:else}
                    <button class="del mono" onclick={() => (confirmDelete = preset.id)}>×</button>
                  {/if}
                </span>
              </div>
            </div>
            {/each}
          {/if}
        {/each}
      </div>
    {/if}
  {/if}
</div>

<style>
  .library {
    display: flex;
    flex-direction: column;
    min-height: 0;
    height: 100%;
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
  .importbtn,
  .backbtn {
    font-size: 11px;
    padding: 1px 6px;
    letter-spacing: 0;
  }
  /* only the first of the header buttons pushes — the rest sit next to it */
  .importbtn.push {
    margin-left: auto;
  }
  .backbtn {
    margin-right: 2px;
  }
  .newfolderrow {
    padding: 6px 10px 0;
    display: flex;
  }
  .err {
    color: var(--crit);
    font-size: 10px;
    padding: 2px 10px;
  }
  .list {
    overflow-y: auto;
    min-height: 0;
  }
  .preset {
    border-bottom: 1px solid var(--grid);
    padding: 4px 10px 6px;
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
    outline: 1px dashed var(--warn);
    background: var(--control);
  }
  .folder.drop .foldername-btn {
    color: var(--warn);
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
  }
  .foldername-btn:hover {
    color: var(--station);
  }
  .foldername {
    flex: 1;
    font-size: 11px;
    padding: 1px 4px;
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
    color: var(--warn);
    background: var(--control);
  }
  .row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
    width: 100%;
    text-align: left;
    background: transparent;
    border: none;
    padding: 2px 0;
    cursor: pointer;
  }
  .row .name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .row .entries {
    color: var(--text-dim);
    font-size: 10px;
    flex-shrink: 0;
  }
  .subrow {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .actions {
    display: flex;
    align-items: center;
    flex-shrink: 0;
  }
  .sub {
    font-size: 10px;
    color: var(--text-dim);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  /* Row actions are small targets in a dense list — keep them comfortably clickable. */
  .del,
  .dup,
  .assignbtn {
    font-size: 14px;
    line-height: 1;
    min-width: 26px;
    padding: 4px 7px;
    border-color: var(--border);
    background: var(--control);
    color: var(--text-dim);
  }
  .actions {
    gap: 4px;
  }
  .del:hover {
    color: var(--crit);
  }
  .dup:hover,
  .assignbtn:hover {
    color: var(--station);
    border-color: var(--station);
  }
  .dup.wide {
    font-size: 11px;
    min-width: 0;
    padding: 3px 8px;
    margin-left: 8px;
  }
  .assignbtn.on,
  .preset.armed .row .name {
    color: var(--station);
  }
  .assignbtn.on {
    border-color: var(--station);
  }
  .preset .row {
    touch-action: none;
  }
  .del.confirm {
    color: var(--crit);
    border-color: var(--crit);
    font-size: 11px;
  }
  .drawer {
    display: flex;
    flex-direction: column;
    min-height: 0;
    overflow-y: auto;
    padding-bottom: 8px;
  }
  .namerow {
    padding: 8px 10px 2px;
  }
  .namefield {
    width: 100%;
    font-size: 12px;
  }
  .meta {
    padding: 0 10px 6px;
    font-size: 10px;
    color: var(--text-dim);
    border-bottom: 1px solid var(--border);
  }
  .entries {
    min-height: 0;
  }
  .entry {
    padding: 6px 10px;
    border-bottom: 1px solid var(--grid);
  }
  .entry .head {
    display: flex;
    gap: 6px;
    align-items: baseline;
    font-size: 11px;
    margin-bottom: 2px;
  }
  .idx {
    color: var(--text-dim);
    font-size: 10px;
  }
  .kind {
    color: var(--station);
  }
  .cond {
    margin: 4px 0 0 10px;
    padding-left: 8px;
    border-left: 1px solid var(--border);
  }
  .cond-name {
    font-size: 10px;
    color: var(--warn);
  }
  .or {
    margin: 3px 0 0 10px;
    font-size: 9px;
    color: var(--text-dim);
  }
  .field {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 10px;
    margin-top: 2px;
  }
  .fk {
    color: var(--text-dim);
    min-width: 74px;
  }
  .fv {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .edit {
    font-size: 10px;
    padding: 1px 4px;
    width: 70px;
  }
  .edit.wide {
    width: 120px;
  }
</style>
