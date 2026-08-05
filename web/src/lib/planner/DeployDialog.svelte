<script lang="ts">
  /**
   * The deploy dialog (W6): the last step between a drafted timetable and real trains.
   * Mode is an explicit choice every time — safe (idle trains only) is preselected, and
   * the immediate option says plainly what it interrupts. After the run, every train
   * reports back; skipped ones say why.
   */
  import { deploy, reasonLabel } from './deploy.svelte';

  const rows = $derived(deploy.rows);
  const stale = $derived(rows.filter((row) => !row.live).length);

  function onKey(event: KeyboardEvent) {
    if (event.key === 'Escape' && deploy.ui.open && !deploy.ui.busy) deploy.close();
  }

  function fmtAge(ts: number): string {
    const seconds = Math.max(0, Math.round((Date.now() - ts) / 1000));
    if (seconds < 90) return `${seconds}s ago`;
    if (seconds < 5400) return `${Math.round(seconds / 60)}m ago`;
    return `${Math.round(seconds / 3600)}h ago`;
  }
</script>

<svelte:window onkeydown={onKey} />

{#if deploy.ui.open}
  <!-- click-through backdrop: the dialog itself stops propagation -->
  <div
    class="backdrop"
    role="presentation"
    onclick={() => !deploy.ui.busy && deploy.close()}
  >
    <div class="dialog panel" role="dialog" aria-modal="true" aria-label="Deploy timetable"
         tabindex="-1" onclick={(e) => e.stopPropagation()} onkeydown={() => {}}>
      <div class="head mono">
        <span class="title">deploy</span>
        <span class="dim">{rows.length} train{rows.length === 1 ? '' : 's'}</span>
        <span class="spacer"></span>
        <button class="x" onclick={() => deploy.close()} disabled={deploy.ui.busy}>×</button>
      </div>

      {#if !deploy.ui.done}
        <div class="body">
          {#if !rows.length}
            <p class="empty">
              Nothing to deploy — assign a preset to a train first. Removals and keeps are
              simulation-only; deploy never takes a train off the network.
            </p>
          {:else}
            <div class="modes">
              <label class:sel={deploy.ui.mode === 'IDLE_ONLY'}>
                <input type="radio" value="IDLE_ONLY" bind:group={deploy.ui.mode} />
                <span class="mname">Safe</span>
                <span class="mdesc dim">only trains standing still with nowhere to go; anything mid-trip is skipped and reported</span>
              </label>
              <label class:sel={deploy.ui.mode === 'IMMEDIATE'}>
                <input type="radio" value="IMMEDIATE" bind:group={deploy.ui.mode} />
                <span class="mname">Immediate</span>
                <span class="mdesc dim">swap right now — a running train cancels its trip and reroutes from the new schedule</span>
              </label>
            </div>
            <div class="rows mono">
              {#each rows as row (row.trainId)}
                <div class="row" class:gone={!row.live}>
                  <span class="train">{row.trainName}</span>
                  <span class="arrow dim">←</span>
                  <span class="preset">{row.presetName}</span>
                  {#if !row.live}<span class="warncol">not on the network</span>{/if}
                </div>
              {/each}
            </div>
            {#if stale}
              <p class="note warncol">
                {stale} row{stale === 1 ? '' : 's'} reference a train that is not on the
                network right now — those will come back as “no longer on the network”.
              </p>
            {/if}
            <p class="note dim">
              The preset replaces the train's current schedule from its first entry, and the
              conductor will hand back an Advanced Schedule item.
            </p>
          {/if}
          {#if deploy.ui.error}<p class="err mono">{deploy.ui.error}</p>{/if}
        </div>
        <div class="foot">
          <button onclick={() => deploy.close()} disabled={deploy.ui.busy}>cancel</button>
          <button
            class="go"
            class:immediate={deploy.ui.mode === 'IMMEDIATE'}
            disabled={!rows.length || deploy.ui.busy}
            onclick={() => void deploy.deploy()}
          >
            {deploy.ui.busy ? 'deploying…' : deploy.ui.mode === 'IMMEDIATE' ? '⇧ deploy now' : '⇧ deploy (safe)'}
          </button>
        </div>
      {:else}
        <div class="body">
          <p class="summary">
            <span class="okcol">{deploy.ui.applied} applied</span>
            {#if deploy.ui.skipped}· <span class="warncol">{deploy.ui.skipped} skipped</span>{/if}
          </p>
          <div class="rows mono">
            {#each deploy.ui.results as result (result.trainId)}
              <div class="row">
                <span class="mark" class:ok={result.ok}>{result.ok ? '✓' : '×'}</span>
                <span class="train">{result.train || result.trainId.slice(0, 8)}</span>
                {#if !result.ok}
                  <span class="why warncol">{reasonLabel(result.reason)}</span>
                {:else if result.notices?.length}
                  <span class="why dim" title={result.notices.join('\n')}>
                    deployed · {result.notices.length} instruction{result.notices.length === 1 ? '' : 's'} the simulator cannot model
                  </span>
                {/if}
              </div>
            {/each}
          </div>
        </div>
        <div class="foot">
          <button onclick={() => void deploy.toggleAudit()}>
            {deploy.ui.auditOpen ? 'hide' : 'recent deploys'}
          </button>
          <button class="go" onclick={() => deploy.close()}>done</button>
        </div>
      {/if}

      {#if deploy.ui.auditOpen}
        <div class="audit mono">
          {#if deploy.ui.auditBusy}
            <div class="dim">loading…</div>
          {:else if !deploy.ui.audit.length}
            <div class="dim">no deploys recorded yet</div>
          {:else}
            {#each deploy.ui.audit as entry (entry.ts + entry.trainId)}
              <div class="arow" title="{entry.train} ← {entry.preset} · {entry.mode.toLowerCase()} · {entry.user}">
                <span class="mark" class:ok={entry.ok}>{entry.ok ? '✓' : '×'}</span>
                <span class="when dim">{fmtAge(entry.ts)}</span>
                <span class="who">{entry.user}</span>
                <span class="train">{entry.train}</span>
                <span class="preset">← {entry.preset}</span>
                <span class="mode dim">{entry.mode.toLowerCase()}</span>
                {#if !entry.ok}<span class="why warncol">{reasonLabel(entry.reason)}</span>{/if}
              </div>
            {/each}
          {/if}
        </div>
      {/if}
    </div>
  </div>
{/if}

<style>
  .backdrop {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.45);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 30;
  }
  .dialog {
    width: 560px;
    max-width: calc(100vw - 32px);
    max-height: calc(100vh - 64px);
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  .head {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 7px 12px;
    font-size: 11px;
    border-bottom: 1px solid var(--border);
    background: var(--panel-raised);
  }
  .title {
    letter-spacing: 1px;
    text-transform: uppercase;
    color: var(--text-dim);
  }
  .spacer {
    flex: 1;
  }
  .x {
    border-color: transparent;
    background: transparent;
    color: var(--text-dim);
  }
  .body {
    padding: 12px;
    overflow-y: auto;
    min-height: 0;
  }
  .modes {
    display: grid;
    gap: 6px;
    margin-bottom: 12px;
  }
  .modes label {
    display: grid;
    grid-template-columns: auto auto 1fr;
    align-items: baseline;
    gap: 8px;
    padding: 7px 9px;
    border: 1px solid var(--border);
    border-radius: 4px;
    cursor: pointer;
  }
  .modes label.sel {
    border-color: var(--station);
    background: var(--control);
  }
  .mname {
    font-weight: 600;
  }
  .mdesc {
    font-size: 11px;
  }
  .rows {
    font-size: 11px;
    border: 1px solid var(--grid);
    border-radius: 4px;
    max-height: 220px;
    overflow-y: auto;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 4px 8px;
    border-bottom: 1px solid var(--grid);
  }
  .row:last-child {
    border-bottom: none;
  }
  .row.gone .train {
    text-decoration: line-through;
  }
  .train {
    min-width: 130px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .preset {
    color: var(--station);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .why {
    margin-left: auto;
    font-size: 11px;
  }
  .mark {
    color: var(--crit);
    width: 10px;
  }
  .mark.ok {
    color: var(--ok);
  }
  .summary {
    margin: 0 0 10px;
    font-size: 12px;
  }
  .okcol {
    color: var(--ok);
  }
  .warncol {
    color: var(--warn);
  }
  .err {
    color: var(--crit);
    font-size: 11px;
  }
  .note {
    margin: 10px 0 0;
    font-size: 11px;
    line-height: 1.4;
  }
  .empty {
    margin: 0;
    font-size: 12px;
    color: var(--text-dim);
    line-height: 1.5;
  }
  .foot {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    padding: 9px 12px;
    border-top: 1px solid var(--border);
    background: var(--panel-raised);
  }
  .go {
    color: var(--ok);
    border-color: var(--ok);
  }
  .go.immediate {
    color: var(--warn);
    border-color: var(--warn);
  }
  .audit {
    border-top: 1px solid var(--border);
    padding: 8px 12px;
    font-size: 11px;
    max-height: 200px;
    overflow-y: auto;
  }
  /* one line per entry, never wrapping: the tooltip carries the full text */
  .arow {
    display: flex;
    gap: 8px;
    align-items: baseline;
    padding: 2px 0;
    white-space: nowrap;
  }
  .arow > * {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .when {
    min-width: 54px;
  }
  .who {
    color: var(--text-dim);
    min-width: 56px;
    max-width: 90px;
  }
  .arow .train {
    min-width: 0;
    flex: 1 1 40%;
  }
  .arow .preset {
    min-width: 0;
    flex: 1 1 40%;
  }
  .mode {
    flex-shrink: 0;
  }
  .arow .why {
    flex: 0 1 auto;
    min-width: 0;
  }
</style>
