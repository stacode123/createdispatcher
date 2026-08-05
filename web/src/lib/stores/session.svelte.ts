import { api, MOCK, setUnauthorizedHandler } from '../api/http';
import type { Me } from '../api/types';

let state = $state<'probing' | 'in' | 'out'>('probing');
let me = $state<Me | null>(null);

export const session = {
  get state() {
    return state;
  },
  get me() {
    return me;
  },
  async probe() {
    if (MOCK) {
      me = { discordId: '0', username: 'mock', tier: 'deployer' };
      state = 'in';
      return;
    }
    try {
      me = await api<Me>('/api/me');
      state = 'in';
    } catch {
      me = null;
      state = 'out';
    }
  },
  async logout() {
    try {
      await api<void>('/auth/logout', { method: 'POST' });
    } catch {
      /* session may already be gone */
    }
    session.expire();
  },
  expire() {
    me = null;
    state = 'out';
  },
};

setUnauthorizedHandler(() => session.expire());
