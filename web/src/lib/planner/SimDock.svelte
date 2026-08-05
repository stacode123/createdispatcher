<script lang="ts">
  /**
   * The planner's bottom dock (W5): run controls (horizon, headway, start
   * time), the sim's live status (queue → progress → done), and the result
   * tabs — conflicts and root causes with map-focus + seek, and the corridor
   * diagram of the finished run.
   */
  import { onMount } from 'svelte';
  import { sims, causeLabel, maskStartTime, normalizeStartTime, CONFLICT_COLORS } from './sims.svelte';
  import { plans } from './plans.svelte';
  import { deploy } from './deploy.svelte';
  import DeployDialog from './DeployDialog.svelte';
  import { session } from '../stores/session.svelte';
  import SimDiagramCanvas from './SimDiagramCanvas.svelte';
  import { playback } from '../stores/playback.svelte';
  import { fmtDuration, fmtTime } from '../diagram/timeFormat';

  let planName = $state('');
  let confirmDeletePlan = $state('');

  onMount(() => {
    void plans.refresh();
  });

  // the name field follows the loaded plan, but never fights the user mid-typing
  let lastPlanId = '';
  $effect(() => {
    if (sims.ui.planId !== lastPlanId) {
      lastPlanId = sims.ui.planId;
      planName = sims.ui.planName;
    }
  });

  const busy = $derived(
    sims.ui.phase === 'queued' || sims.ui.phase === 'preparing' || sims.ui.phase === 'running'
      || sims.ui.phase === 'loading',
  );
  const progressPct = $derived(
    sims.ui.horizonTicks > 0 ? Math.min(100, Math.round((sims.ui.progressTicks / sims.ui.horizonTicks) * 100)) : 0,
  );

  const statusText = $derived.by(() => {
    switch (sims.ui.phase) {
      case 'queued':
        return sims.ui.queuePos > 0 ? `queued #${sims.ui.queuePos}` : 'queued';
      case 'preparing':
        return 'snapshotting network…';
      case 'running':
        return `simulating ${progressPct}%`;
      case 'loading':
        return 'loading result…';
      case 'done': {
        const t = `${sims.ui.conflicts.length} conflicts · ${sims.ui.rootCauses.length} root causes`;
        return sims.ui.truncated ? t + ' · TRUNCATED' : t;
      }
      case 'failed':
        return sims.ui.error || 'failed';
      case 'cancelled':
        return 'cancelled';
      default:
        return '';
    }
  });

  function timeOf(tick: number): string {
    const meta = playback.meta;
    return fmtTime(meta.start, meta.rate, tick);
  }

  // The mask rewrites the element in place — Svelte only re-renders `value` when the
  // store differs, so a rejected keystroke would otherwise stick in the DOM.
  function onStartInput(event: Event & { currentTarget: HTMLInputElement }) {
    const masked = maskStartTime(event.currentTarget.value);
    event.currentTarget.value = masked;
    sims.ui.startTime = masked;
  }

  function setTab(tab: 'conflicts' | 'causes' | 'diagram') {
    sims.ui.tab = tab;
    if (tab === 'diagram') {
      void sims.ensureGroups();
      void sims.loadDiagram();
    }
  }
</script>

<div class="dock panel">
  <div class="head mono planrow">
    <span class="title">plan</span>
    <select
      class="planpick"
      value={sims.ui.planId}
      disabled={plans.ui.busy}
      onchange={(e) => {
        const id = e.currentTarget.value;
        if (id) void plans.load(id);
        else sims.clearDraft();
      }}
      title="load a saved timetable"
    >
      <option value="">— new plan —</option>
      {#each plans.ui.list as plan (plan.id)}
        <option value={plan.id}>{plan.name} ({plan.assignments})</option>
      {/each}
    </select>
    <input
      class="planname"
      type="text"
      placeholder="plan name"
      bind:value={planName}
      disabled={plans.ui.busy}
    />
    <button
      disabled={plans.ui.busy || !planName.trim()}
      onclick={() => void plans.save(planName, sims.ui.planId || undefined)}
      title={sims.ui.planId ? 'overwrite this plan' : 'save as a new plan'}
    >{sims.ui.planId ? '💾 save' : '💾 save new'}</button>
    {#if sims.ui.planId}
      <button
        disabled={plans.ui.busy || !planName.trim()}
        onclick={() => void plans.save(planName)}
        title="save the current draft as a separate plan"
      >save as new</button>
      {#if confirmDeletePlan === sims.ui.planId}
        <button class="del confirm" onclick={() => {
          void plans.remove(sims.ui.planId);
          confirmDeletePlan = '';
        }}>sure?</button>
      {:else}
        <button class="del" onclick={() => (confirmDeletePlan = sims.ui.planId)} title="delete this plan">×</button>
      {/if}
    {/if}
    {#if plans.ui.error}<span class="err">{plans.ui.error}</span>{/if}
    {#if sims.staleRows}
      <span class="warncol" title="the plan references trains that are not on the network right now">
        ⚠ {sims.staleRows} row{sims.staleRows > 1 ? 's' : ''} without a live train
      </span>
    {/if}
  </div>
  <div class="head mono">
    <span class="title">simulate</span>
    <select
      class="modepick"
      value={sims.ui.removeScheduled ? 'clear' : 'live'}
      onchange={(e) => sims.setMode(e.currentTarget.value === 'clear')}
      title="what the plan runs against"
    >
      <option value="clear">clear timetable</option>
      <option value="live">keep live traffic</option>
    </select>
    <span class="draft">
      {sims.draftCount} assigned
      {#if sims.ui.removeScheduled}
        · {sims.ui.keeps.length} kept
      {:else}
        · {sims.ui.removals.length} removed
      {/if}
    </span>
    {#if sims.draftCount || sims.exceptionCount}
      <button class="ghost" onclick={() => sims.clearDraft()} title="drop all draft assignments">clear</button>
    {/if}
    <label>horizon <input class="num" type="number" min="1" max="336" bind:value={sims.ui.horizonHours} disabled={busy} /> h</label>
    <label>headway <input class="num" type="text" placeholder="default" bind:value={sims.ui.headwaySeconds} disabled={busy} /> s</label>
    <label>
      start
      <input
        class="num time"
        type="text"
        inputmode="numeric"
        maxlength="5"
        placeholder="--:--"
        value={sims.ui.startTime}
        oninput={onStartInput}
        onblur={() => (sims.ui.startTime = normalizeStartTime(sims.ui.startTime))}
        disabled={busy}
        title="in-game start time, 24-hour HH:MM — leave empty to start now"
      />
      <span class="hint">{sims.ui.startTime ? 'in-game' : 'now'}</span>
    </label>
    {#if busy && sims.ui.phase !== 'loading'}
      <button class="run cancel" onclick={() => void sims.cancel()}>⏹ cancel</button>
    {:else}
      <button class="run" onclick={() => void sims.run()} disabled={busy}>▶ run sim</button>
    {/if}
    {#if session.me?.tier === 'deployer'}
      <button
        class="deploy"
        disabled={!sims.draftCount}
        onclick={() => deploy.open()}
        title={sims.draftCount
          ? 'apply these assignments to the real trains'
          : 'assign a preset to a train first'}
      >⇧ deploy…</button>
    {/if}
    <span class="status" class:err={sims.ui.phase === 'failed'} class:warn={sims.ui.truncated && sims.ui.phase === 'done'}>
      {statusText}
    </span>
    {#if sims.ui.phase === 'running'}
      <span class="bar"><span class="fill" style="width: {progressPct}%"></span></span>
    {/if}
  </div>

  {#if sims.ui.issues.length || sims.ui.phase === 'done'}
    <div class="tabs mono">
      <button class:active={sims.ui.tab === 'conflicts'} onclick={() => setTab('conflicts')}>
        conflicts ({sims.ui.conflicts.length})
      </button>
      <button class:active={sims.ui.tab === 'causes'} onclick={() => setTab('causes')}>
        root causes ({sims.ui.rootCauses.length})
      </button>
      <button class:active={sims.ui.tab === 'diagram'} onclick={() => setTab('diagram')}>
        ▤ diagram
      </button>
      {#if sims.ui.issues.length}
        <span class="issues" title={sims.ui.issues.map((i) => `${i[0]}: ${i[1]} ${i[2]}`.trim()).join('\n')}>
          ⚠ {sims.ui.issues.length} assignment{sims.ui.issues.length > 1 ? 's' : ''} skipped
        </span>
      {/if}
      {#if sims.ui.removed.length}
        <span class="excluded" title={sims.ui.removed.join('\n')}>
          {sims.ui.removed.length} train{sims.ui.removed.length > 1 ? 's' : ''} off the network
        </span>
      {/if}
      {#if sims.ui.excluded.length}
        <span class="excluded" title={sims.ui.excluded.map((e) => `${e[0]} (${e[1].split('.').pop()})`).join('\n')}>
          {sims.ui.excluded.length} static obstacle{sims.ui.excluded.length > 1 ? 's' : ''}
        </span>
      {/if}
    </div>
  {/if}

  <div class="body">
    {#if sims.ui.phase !== 'done'}
      <div class="empty-state">
        {#if busy}
          simulating the virtual timetable…
        {:else}
          Drag a preset onto a train to assign it, or onto a folder to file it — then ▶ run sim.
          (Or arm a preset with ⇥ and click a train.)
        {/if}
      </div>
    {:else if sims.ui.tab === 'conflicts'}
      {#if sims.ui.conflicts.length === 0}
        <div class="empty-state">No conflicts within the horizon.</div>
      {:else}
        <div class="rows">
          {#each sims.ui.conflicts as conflict (conflict.index)}
            <button
              class="row"
              class:focused={sims.ui.focused === `c${conflict.index}`}
              onclick={() => sims.focusConflict(conflict)}
            >
              <span class="kind mono" style="color: {CONFLICT_COLORS[conflict.type]}">{conflict.typeName}</span>
              <span class="when mono">{timeOf(conflict.start)}</span>
              <span class="who">
                {#each conflict.trains.slice(0, 4) as trainIdx (trainIdx)}
                  <span class="sw" style="background: {sims.colorOf(trainIdx)}"></span>{sims.nameOf(trainIdx)}{' '}
                {/each}
                {#if conflict.trains.length > 4}+{conflict.trains.length - 4}{/if}
              </span>
              <span class="res mono">{conflict.resource}</span>
              <span class="extra mono">
                {#if conflict.count > 1}×{conflict.count}{/if}
                {#if conflict.nonDet}~{/if}
              </span>
            </button>
          {/each}
        </div>
      {/if}
    {:else if sims.ui.tab === 'causes'}
      {#if sims.ui.rootCauses.length === 0}
        <div class="empty-state">No stranded trains — every wait chain resolved.</div>
      {:else}
        <div class="rows">
          {#each sims.ui.rootCauses as cause (cause.index)}
            <button
              class="row"
              class:focused={sims.ui.focused === `r${cause.index}`}
              onclick={() => sims.focusCause(cause)}
            >
              <span class="kind mono warncol">{causeLabel(cause.kind)}</span>
              <span class="who"><span class="sw" style="background: {sims.colorOf(cause.root)}"></span>{sims.nameOf(cause.root)}</span>
              {#if cause.detail}<span class="res mono">{cause.detail}</span>{/if}
              <span class="extra mono">
                {#if cause.stranded.length}strands {cause.stranded.length}{/if}
                · since {timeOf(cause.since)} ({fmtDuration(playback.meta.rate, Math.max(0, sims.ui.ticksSimulated - cause.since))} held)
              </span>
            </button>
          {/each}
        </div>
      {/if}
    {:else}
      <div class="diagram-tab">
        <div class="pickers mono">
          <input
            list="sim-diagram-groups"
            placeholder="from station"
            value={sims.ui.diagramFrom}
            onchange={(e) => sims.setDiagramEndpoint('from', e.currentTarget.value.trim())}
            onfocus={() => void sims.ensureGroups()}
          />
          <span>→</span>
          <input
            list="sim-diagram-groups"
            placeholder="to station"
            value={sims.ui.diagramTo}
            onchange={(e) => sims.setDiagramEndpoint('to', e.currentTarget.value.trim())}
            onfocus={() => void sims.ensureGroups()}
          />
          <datalist id="sim-diagram-groups">
            {#each sims.ui.groups as group (group)}<option value={group}></option>{/each}
          </datalist>
          {#if sims.ui.diagramStatus === 'error'}
            <span class="err mono">{sims.ui.diagramError}</span>
          {/if}
        </div>
        <SimDiagramCanvas />
      </div>
    {/if}
  </div>
</div>

<DeployDialog />

<style>
  .dock {
    display: grid;
    grid-template-rows: auto auto auto minmax(0, 1fr);
    min-height: 0;
    border-top: 1px solid var(--border);
  }
  .planrow {
    background: var(--panel-raised);
  }
  .planpick {
    min-width: 150px;
    font-size: 11px;
  }
  .planname {
    width: 150px;
    font-size: 11px;
  }
  .modepick {
    font-size: 11px;
  }
  .planrow button {
    font-size: 11px;
  }
  .del {
    color: var(--text-dim);
  }
  .del:hover,
  .del.confirm {
    color: var(--crit);
    border-color: var(--crit);
  }
  .warncol {
    color: var(--warn);
  }
  .head {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 5px 10px;
    font-size: 11px;
    border-bottom: 1px solid var(--border);
    flex-wrap: wrap;
  }
  .title {
    letter-spacing: 1px;
    color: var(--text-dim);
    text-transform: uppercase;
  }
  .draft {
    color: var(--text-dim);
  }
  .head label {
    display: flex;
    align-items: center;
    gap: 4px;
    color: var(--text-dim);
  }
  .num {
    width: 52px;
    font-size: 11px;
  }
  /* fixed-width digits so the mask doesn't shift as HH:MM fills in */
  .num.time {
    width: 52px;
    font-variant-numeric: tabular-nums;
    text-align: center;
  }
  .hint {
    color: var(--text-dim);
    opacity: 0.7;
  }
  .run {
    color: var(--ok);
    border-color: var(--ok);
    font-size: 11px;
  }
  .deploy {
    color: var(--warn);
    border-color: var(--warn);
    font-size: 11px;
  }
  .run.cancel {
    color: var(--crit);
    border-color: var(--crit);
  }
  .ghost {
    font-size: 10px;
    border-color: transparent;
    background: transparent;
    color: var(--text-dim);
  }
  .status {
    color: var(--text-dim);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .status.err {
    color: var(--crit);
  }
  .status.warn {
    color: var(--warn);
  }
  .bar {
    width: 120px;
    height: 5px;
    background: var(--control);
    border: 1px solid var(--border);
    display: inline-block;
  }
  .fill {
    display: block;
    height: 100%;
    background: var(--ok);
    transition: width 0.4s linear;
  }
  .tabs {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 3px 10px;
    font-size: 11px;
    border-bottom: 1px solid var(--border);
  }
  .tabs button {
    font-size: 11px;
    border-color: transparent;
    background: transparent;
  }
  .tabs button.active {
    border-color: var(--border);
    background: var(--control);
    color: var(--station);
  }
  .issues {
    color: var(--warn);
    margin-left: 8px;
  }
  .excluded {
    color: var(--text-dim);
    margin-left: 8px;
  }
  .body {
    min-height: 0;
    overflow: hidden;
    display: grid;
  }
  .rows {
    overflow-y: auto;
    min-height: 0;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    text-align: left;
    background: transparent;
    border: none;
    border-bottom: 1px solid var(--grid);
    padding: 4px 10px;
    font-size: 11px;
    cursor: pointer;
  }
  .row:hover {
    background: var(--control);
  }
  .row.focused {
    background: var(--control);
    box-shadow: inset 2px 0 0 var(--station);
  }
  .kind {
    min-width: 72px;
    flex-shrink: 0;
  }
  .warncol {
    color: var(--warn);
  }
  .when {
    color: var(--text-dim);
    min-width: 60px;
    flex-shrink: 0;
  }
  .who {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .sw {
    display: inline-block;
    width: 7px;
    height: 7px;
    border-radius: 50%;
    margin-right: 4px;
    vertical-align: middle;
  }
  .res {
    color: var(--text-dim);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .extra {
    margin-left: auto;
    color: var(--text-dim);
    flex-shrink: 0;
  }
  .err {
    color: var(--crit);
  }
  .diagram-tab {
    display: grid;
    grid-template-rows: auto minmax(0, 1fr);
    min-height: 0;
  }
  .pickers {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 4px 10px;
    font-size: 11px;
  }
  .pickers input {
    width: 160px;
    font-size: 11px;
  }
</style>
