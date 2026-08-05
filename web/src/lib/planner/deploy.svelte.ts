/**
 * Deploy (W6): pushing the draft assignment list onto the real trains. This is the only
 * place the site writes to the running world, so the flow is deliberately slow — open a
 * dialog, pick how much it may interrupt, read back what happened per train — and every
 * attempt lands in the server's audit journal, which this store can also read back.
 */
import { MOCK, api, ApiError } from '../api/http';
import type { AuditEntryDto, DeployResponseDto, DeployResultDto } from '../api/types';
import { liveTrains } from '../stores/liveTrains.svelte';
import { sims } from './sims.svelte';

export type DeployMode = 'IDLE_ONLY' | 'IMMEDIATE';

/** Why a train was skipped, in words an operator can act on. */
const REASONS: Record<string, string> = {
  not_found: 'no longer on the network',
  derailed: 'derailed',
  not_idle: 'moving or mid-trip — safe mode left it alone',
  preset_invalid: 'preset could not be read',
  empty: 'preset has no entries',
  error: 'server error — see the log',
};

export function reasonLabel(reason: string | undefined): string {
  if (!reason) return '';
  return REASONS[reason] ?? reason.replace(/_/g, ' ');
}

class DeployStore {
  ui = $state({
    open: false,
    mode: 'IDLE_ONLY' as DeployMode,
    busy: false,
    error: '',
    /** Set once a deploy came back; cleared when the dialog reopens. */
    done: false,
    applied: 0,
    skipped: 0,
    results: [] as DeployResultDto[],
    auditOpen: false,
    auditBusy: false,
    audit: [] as AuditEntryDto[],
  });

  /** The rows this deploy would touch, in roster order, with their preset names. */
  get rows(): { trainId: string; trainName: string; presetName: string; live: boolean }[] {
    return Object.entries(sims.ui.assignments).map(([trainId, draft]) => {
      const entry = liveTrains.ui.roster.find((t) => t.id === trainId);
      return {
        trainId,
        trainName: entry?.name ?? sims.ui.planTrainNames[trainId] ?? trainId.slice(0, 8),
        presetName: draft.presetName,
        live: !!entry,
      };
    });
  }

  open() {
    this.ui.open = true;
    this.ui.done = false;
    this.ui.error = '';
    this.ui.results = [];
  }

  close() {
    this.ui.open = false;
    this.ui.auditOpen = false;
  }

  async deploy() {
    const rows = this.rows;
    if (!rows.length || this.ui.busy) return;
    this.ui.busy = true;
    this.ui.error = '';
    if (MOCK) {
      await new Promise((resolve) => setTimeout(resolve, 400));
      this.apply({
        mode: this.ui.mode,
        applied: this.ui.mode === 'IMMEDIATE' ? rows.length : Math.max(0, rows.length - 1),
        skipped: this.ui.mode === 'IMMEDIATE' ? 0 : Math.min(1, rows.length),
        results: rows.map((row, index) => ({
          trainId: row.trainId,
          train: row.trainName,
          ok: this.ui.mode === 'IMMEDIATE' || index !== 0,
          reason: this.ui.mode === 'IMMEDIATE' || index !== 0 ? undefined : 'not_idle',
        })),
      });
      return;
    }
    try {
      const response = await api<DeployResponseDto>('/api/deploy', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          mode: this.ui.mode,
          assignments: rows.map((row) => ({
            trainId: row.trainId,
            presetId: sims.ui.assignments[row.trainId].presetId,
            valueOverrides: [],
          })),
        }),
      });
      this.apply(response);
    } catch (e) {
      this.ui.busy = false;
      this.ui.error =
        e instanceof ApiError
          ? e.key === 'rate_limited'
            ? 'too many deploys in a row — wait a minute'
            : e.key === 'forbidden'
              ? 'your account is not a deployer'
              : e.key + (e.detail ? `: ${e.detail}` : '')
          : 'network error';
    }
  }

  private apply(response: DeployResponseDto) {
    this.ui.busy = false;
    this.ui.done = true;
    this.ui.applied = response.applied;
    this.ui.skipped = response.skipped;
    this.ui.results = response.results;
    if (this.ui.auditOpen) void this.loadAudit();
  }

  async toggleAudit() {
    this.ui.auditOpen = !this.ui.auditOpen;
    if (this.ui.auditOpen && !this.ui.audit.length) await this.loadAudit();
  }

  async loadAudit() {
    this.ui.auditBusy = true;
    if (MOCK) {
      const now = Date.now();
      this.ui.audit = [0, 1, 2].map((i) => ({
        ts: now - i * 720_000,
        gameTick: 100000 - i * 1200,
        user: 'mock',
        discordId: '0',
        mode: i === 1 ? 'IMMEDIATE' : 'IDLE_ONLY',
        trainId: `mock-${i}`,
        train: ['Eurostar 1', 'Regional 4', 'Freight 12'][i],
        presetId: `preset-${i}`,
        preset: ['HS morning', 'Branch shuttle', 'Ore run'][i],
        ok: i !== 2,
        reason: i === 2 ? 'not_idle' : undefined,
      }));
      this.ui.auditBusy = false;
      return;
    }
    try {
      const dto = await api<{ entries: AuditEntryDto[] }>('/api/audit?limit=50');
      this.ui.audit = dto.entries;
    } catch {
      this.ui.audit = [];
    }
    this.ui.auditBusy = false;
  }
}

export const deploy = new DeployStore();
