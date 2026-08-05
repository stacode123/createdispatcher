<script lang="ts">
  import { notifications, KIND_COLOR } from '../lib/stores/notifications.svelte';
  import { flash } from '../lib/stores/flash.svelte';
</script>

<div class="stack">
  {#each flash.items as item (item.key)}
    <button
      class="toast panel"
      style="border-left-color: var({item.tone === 'warn' ? '--warn' : '--info'})"
      onclick={() => flash.dismiss(item.key)}
    >
      <span class="msg">{item.text}</span>
    </button>
  {/each}
  {#each notifications.ui.toasts as toast (toast.key)}
    <button
      class="toast panel"
      style="border-left-color: {KIND_COLOR[toast.n.kind]}"
      onclick={() => {
        notifications.focus(toast.n);
        notifications.dismiss(toast.key);
      }}
    >
      <span class="kind mono" style="color: {KIND_COLOR[toast.n.kind]}">
        {toast.n.kind.replace('_', ' ').toLowerCase()}
      </span>
      <span class="msg">{toast.n.message}</span>
    </button>
  {/each}
</div>

<style>
  .stack {
    position: fixed;
    right: 16px;
    bottom: 60px;
    display: flex;
    flex-direction: column;
    gap: 8px;
    z-index: 20;
    max-width: 360px;
  }
  .toast {
    display: flex;
    flex-direction: column;
    gap: 2px;
    text-align: left;
    padding: 8px 12px;
    border-left: 3px solid;
    animation: slide-in 0.18s ease-out;
  }
  .kind {
    font-size: 10px;
    letter-spacing: 1px;
  }
  .msg {
    font-size: 12px;
    line-height: 1.35;
  }
  @keyframes slide-in {
    from {
      transform: translateX(24px);
      opacity: 0;
    }
    to {
      transform: none;
      opacity: 1;
    }
  }
</style>
