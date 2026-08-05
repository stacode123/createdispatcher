<script lang="ts">
  import { onMount } from 'svelte';
  import { notifications, KIND_COLOR } from '../lib/stores/notifications.svelte';
  import { clock } from '../lib/stores/clock.svelte';
  import { replays } from '../lib/stores/replays.svelte';
  import { router } from '../router.svelte';
  import type { WebNotification } from '../lib/api/types';

  function openReplay(event: MouseEvent, notificationId: string) {
    event.stopPropagation();
    router.navigate('replay', { id: replays.ui.byNotification[notificationId] });
  }

  let nowTick = $state(0);
  onMount(() => {
    const timer = setInterval(() => (nowTick = clock.estServerTick()), 1000);
    return () => clearInterval(timer);
  });

  function age(n: WebNotification): string {
    const seconds = Math.max(0, Math.floor((nowTick - n.sinceTick) / 20));
    return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m`;
  }

  const critical = $derived(notifications.ui.active.filter((n) => n.severity === 'CRITICAL').length);
</script>

<div class="wrap">
  <button
    class="head panel mono"
    class:alert={critical > 0}
    onclick={() => (notifications.ui.panelOpen = !notifications.ui.panelOpen)}
  >
    ⚠ {notifications.ui.active.length}
    <span class="chev">{notifications.ui.panelOpen ? '‹' : '›'}</span>
  </button>
  {#if notifications.ui.panelOpen}
    <div class="list panel">
      {#if notifications.ui.active.length === 0}
        <div class="empty mono">Network is healthy.</div>
      {/if}
      {#each notifications.ui.active as n (n.id)}
        <button class="row" class:hl={notifications.ui.highlight === n.id} onclick={() => notifications.focus(n)}>
          <span class="dot" style="background: {KIND_COLOR[n.kind]}"></span>
          <span class="msg">{n.message}</span>
          {#if replays.ui.byNotification[n.id]}
            <span class="rp mono" role="button" tabindex="-1" title="watch replay"
                  onclick={(e) => openReplay(e, n.id)} onkeydown={() => {}}>▶</span>
          {/if}
          <span class="age mono">{age(n)}</span>
        </button>
      {/each}
      {#if notifications.ui.resolved.length}
        <div class="sep mono">recently resolved</div>
        {#each notifications.ui.resolved.slice(0, 8) as n (n.id + ':' + n.resolvedTick)}
          <div class="row resolved">
            <span class="dot" style="background: {KIND_COLOR[n.kind]}"></span>
            <span class="msg">{n.message}</span>
            {#if replays.ui.byNotification[n.id]}
              <span class="rp live mono" role="button" tabindex="-1" title="watch replay"
                    onclick={(e) => openReplay(e, n.id)} onkeydown={() => {}}>▶</span>
            {/if}
          </div>
        {/each}
      {/if}
    </div>
  {/if}
</div>

<style>
  .wrap {
    position: absolute;
    top: 12px;
    left: 12px;
    z-index: 4;
    display: flex;
    flex-direction: column;
    gap: 6px;
    max-height: calc(100% - 80px);
  }
  .head {
    align-self: flex-start;
    padding: 5px 10px;
    font-size: 12px;
  }
  .head.alert {
    color: var(--crit);
    border-color: var(--crit);
  }
  .chev {
    margin-left: 6px;
    color: var(--text-dim);
  }
  .list {
    width: 320px;
    overflow-y: auto;
    padding: 4px;
  }
  .empty {
    padding: 12px;
    font-size: 11px;
    color: var(--text-dim);
    text-align: center;
  }
  .row {
    display: flex;
    align-items: flex-start;
    gap: 7px;
    width: 100%;
    text-align: left;
    padding: 6px 8px;
    background: transparent;
    border: 1px solid transparent;
    border-radius: 4px;
  }
  .row:hover {
    background: var(--control);
  }
  .row.hl {
    border-color: var(--station);
  }
  .row.resolved {
    opacity: 0.45;
    cursor: default;
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
    flex: 1;
    font-size: 12px;
    line-height: 1.35;
  }
  .age {
    font-size: 10px;
    color: var(--text-dim);
    flex-shrink: 0;
  }
  .rp {
    flex-shrink: 0;
    color: var(--station);
    font-size: 11px;
    padding: 0 3px;
    cursor: pointer;
  }
  .rp.live {
    pointer-events: auto;
  }
  .row.resolved .rp {
    opacity: 1;
  }
  .sep {
    padding: 6px 8px 2px;
    font-size: 10px;
    letter-spacing: 1px;
    color: var(--text-dim);
  }
</style>
