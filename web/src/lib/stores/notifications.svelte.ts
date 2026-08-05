import { api } from '../api/http';
import type { WebNotification } from '../api/types';
import { focusMap } from './mapFocus.svelte';

export interface Toast {
  key: number;
  n: WebNotification;
}

export const KIND_COLOR: Record<WebNotification['kind'], string> = {
  SIGNAL_WAIT: '#ff6040',
  DEADLOCK: '#dd2222',
  DETOUR: '#ffc020',
};

let toastKey = 0;

class NotificationsStore {
  ui = $state({
    active: [] as WebNotification[],
    resolved: [] as WebNotification[],
    toasts: [] as Toast[],
    panelOpen: false,
    highlight: null as string | null,
  });

  apply(n: WebNotification) {
    if (n.state === 'RESOLVED') {
      this.ui.active = this.ui.active.filter((a) => a.id !== n.id);
      this.ui.resolved = [n, ...this.ui.resolved.filter((r) => r.id !== n.id)].slice(0, 50);
      if (this.ui.highlight === n.id) this.ui.highlight = null;
      return;
    }
    const isNew = !this.ui.active.some((a) => a.id === n.id);
    const next = isNew ? [...this.ui.active, n] : this.ui.active.map((a) => (a.id === n.id ? n : a));
    next.sort((a, b) =>
      a.severity === b.severity ? b.sinceTick - a.sinceTick : a.severity === 'CRITICAL' ? -1 : 1,
    );
    this.ui.active = next;
    if (isNew) this.toast(n);
  }

  async loadSnapshot() {
    try {
      const snapshot = await api<{ active: WebNotification[]; resolved: WebNotification[] }>('/api/notifications');
      this.ui.active = snapshot.active;
      this.ui.resolved = snapshot.resolved;
    } catch {
      /* offline or unauthorized — SSE will fill in */
    }
  }

  toast(n: WebNotification) {
    const entry = { key: ++toastKey, n };
    this.ui.toasts = [...this.ui.toasts, entry];
    setTimeout(() => this.dismiss(entry.key), 7000);
  }

  dismiss(key: number) {
    this.ui.toasts = this.ui.toasts.filter((t) => t.key !== key);
  }

  focus(n: WebNotification) {
    if (n.dim) focusMap(n.x, n.z, n.dim);
    this.ui.highlight = n.id;
    this.ui.panelOpen = true;
  }
}

export const notifications = new NotificationsStore();
