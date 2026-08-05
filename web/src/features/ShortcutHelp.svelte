<script lang="ts">
  /**
   * The keyboard layer (W6): the app-wide bindings, and the `?` overlay that lists them.
   * Every binding documented here is implemented here or in the component named beside it —
   * the overlay is not allowed to promise anything that does not work.
   */
  import { router } from '../router.svelte';
  import { theme } from '../lib/stores/theme.svelte';

  let open = $state(false);

  const KEYS: [string, string][] = [
    ['?', 'this list'],
    ['1 / 2 / 3', 'Live / Planner / Replay'],
    ['/  ·  Ctrl-K', 'find a train'],
    ['Space', 'play / pause the playback or replay transport'],
    ['T', 'light / dark theme'],
    ['Esc', 'close this, cancel a drag or an armed preset'],
    ['scroll', 'zoom the map at the cursor · drag to pan'],
    ['click a train', 'select it; the info panel can then follow it'],
    ['drag a divider', 'resize a panel or the dock · double-click resets it'],
  ];

  function typingIn(target: EventTarget | null): boolean {
    const element = target as HTMLElement | null;
    return (
      element?.tagName === 'INPUT' ||
      element?.tagName === 'TEXTAREA' ||
      element?.tagName === 'SELECT' ||
      !!element?.isContentEditable
    );
  }

  function onKey(event: KeyboardEvent) {
    if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return;
    if (event.key === 'Escape' && open) {
      open = false;
      return;
    }
    if (typingIn(event.target)) return;
    if (event.key === '?') {
      event.preventDefault();
      open = !open;
      return;
    }
    if (event.key === '1' || event.key === '2' || event.key === '3') {
      event.preventDefault();
      router.navigate((['live', 'planner', 'replay'] as const)[+event.key - 1]);
      return;
    }
    if (event.key === 't' || event.key === 'T') {
      event.preventDefault();
      theme.toggle();
    }
  }
</script>

<svelte:window onkeydown={onKey} />

{#if open}
  <div class="backdrop" role="presentation" onclick={() => (open = false)}>
    <div class="card panel mono" role="dialog" aria-modal="true" aria-label="Keyboard shortcuts"
         tabindex="-1" onclick={(e) => e.stopPropagation()} onkeydown={() => {}}>
      <div class="head">keyboard</div>
      {#each KEYS as [key, what] (key)}
        <div class="row">
          <kbd>{key}</kbd>
          <span class="what">{what}</span>
        </div>
      {/each}
      <div class="foot dim">press ? or Esc to close</div>
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
    z-index: 40;
  }
  .card {
    width: 420px;
    max-width: calc(100vw - 32px);
    padding: 14px 16px;
    font-size: 12px;
  }
  .head {
    letter-spacing: 2px;
    text-transform: uppercase;
    color: var(--text-dim);
    font-size: 11px;
    margin-bottom: 10px;
  }
  .row {
    display: grid;
    grid-template-columns: 120px 1fr;
    gap: 10px;
    align-items: baseline;
    padding: 3px 0;
  }
  kbd {
    font-family: var(--font-data);
    font-size: 11px;
    color: var(--station);
    background: var(--control);
    border: 1px solid var(--border);
    border-radius: 3px;
    padding: 1px 5px;
    text-align: center;
  }
  .what {
    color: var(--text);
  }
  .foot {
    margin-top: 12px;
    font-size: 11px;
  }
</style>
