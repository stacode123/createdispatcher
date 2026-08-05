/**
 * Preset library state (W4): list + detail with whitelisted value edits.
 * Mutations round-trip through the server; the SSE "presets" event (fired on
 * every library change, including in-game saves) triggers a list refresh.
 */
import { MOCK, api, ApiError } from '../api/http';
import type { PresetDetailDto, PresetSummaryDto } from '../api/types';

export interface ValueEdit {
  entry: number;
  target: 'instruction' | 'condition';
  col?: number;
  row?: number;
  key: string;
  value: string | number;
}

class PresetsStore {
  ui = $state({
    list: [] as PresetSummaryDto[],
    status: 'idle' as 'idle' | 'loading' | 'ready' | 'error',
    error: '',
    detail: null as PresetDetailDto | null,
    detailStatus: 'idle' as 'idle' | 'loading' | 'ready' | 'error',
    /** A mutation is in flight — inputs disable. */
    busy: false,
    /** Last mutation error, shown in the drawer. */
    editError: '',
  });

  private refreshTimer: ReturnType<typeof setTimeout> | null = null;

  async refresh() {
    if (MOCK) {
      this.ui.list = mockList();
      this.ui.status = 'ready';
      return;
    }
    if (this.ui.status === 'idle') this.ui.status = 'loading';
    try {
      const dto = await api<{ presets: PresetSummaryDto[] }>('/api/presets');
      this.ui.list = dto.presets;
      this.ui.status = 'ready';
      this.ui.error = '';
    } catch (e) {
      this.ui.status = 'error';
      this.ui.error = e instanceof ApiError ? e.key : 'network error';
    }
  }

  /** SSE change signal — coalesce bursts into one refetch. */
  changed() {
    if (this.ui.status === 'idle') return; // planner not opened yet
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = null;
      void this.refresh();
      if (this.ui.detail) void this.open(this.ui.detail.id, true);
    }, 400);
  }

  async open(id: string, silent = false) {
    if (MOCK) {
      // mockDetail rebuilds from the pristine fixture — carry over anything since edited
      const current = this.ui.list.find((p) => p.id === id);
      this.ui.detail = current
        ? { ...mockDetail(id), name: current.name, folder: current.folder }
        : mockDetail(id);
      this.ui.detailStatus = 'ready';
      return;
    }
    if (!silent) {
      this.ui.detailStatus = 'loading';
      this.ui.editError = '';
    }
    try {
      const dto = await api<PresetDetailDto>(`/api/presets?id=${id}`);
      this.ui.detail = dto;
      this.ui.detailStatus = 'ready';
    } catch (e) {
      if (silent) {
        // deleted underneath us — drop the drawer
        if (e instanceof ApiError && e.status === 404) this.closeDetail();
        return;
      }
      this.ui.detailStatus = 'error';
      this.ui.editError = e instanceof ApiError ? e.key : 'network error';
    }
  }

  closeDetail() {
    this.ui.detail = null;
    this.ui.detailStatus = 'idle';
    this.ui.editError = '';
  }

  /** Returns the error key, or null on success. */
  async createFromTrain(trainId: string, name: string): Promise<string | null> {
    if (MOCK) return null;
    this.ui.busy = true;
    try {
      await api<PresetSummaryDto>('/api/presets', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ trainId, name }),
      });
      await this.refresh();
      return null;
    } catch (e) {
      return e instanceof ApiError ? e.key : 'network error';
    } finally {
      this.ui.busy = false;
    }
  }

  async rename(id: string, name: string) {
    if (MOCK) return;
    this.ui.busy = true;
    this.ui.editError = '';
    try {
      const dto = await api<PresetSummaryDto>('/api/presets', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id, name }),
      });
      if (this.ui.detail?.id === id) this.ui.detail = { ...this.ui.detail, name: dto.name };
      await this.refresh();
    } catch (e) {
      this.ui.editError = e instanceof ApiError ? e.key : 'network error';
    } finally {
      this.ui.busy = false;
    }
  }

  async editValue(id: string, edit: ValueEdit) {
    if (MOCK) return;
    this.ui.busy = true;
    this.ui.editError = '';
    try {
      const dto = await api<PresetDetailDto>('/api/presets', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id, ...edit }),
      });
      this.ui.detail = dto;
    } catch (e) {
      this.ui.editError = e instanceof ApiError ? e.key : 'network error';
      // resync the drawer — the input now shows a value the server rejected
      await this.open(id, true);
    } finally {
      this.ui.busy = false;
    }
  }

  /** Files a preset under a folder path; blank unfiles it. */
  async move(id: string, folder: string) {
    if (MOCK) {
      this.ui.list = this.ui.list.map((p) => (p.id === id ? { ...p, folder } : p));
      if (this.ui.detail?.id === id) this.ui.detail = { ...this.ui.detail, folder };
      return;
    }
    this.ui.busy = true;
    this.ui.editError = '';
    try {
      const dto = await api<PresetSummaryDto>('/api/presets', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id, folder }),
      });
      if (this.ui.detail?.id === id) this.ui.detail = { ...this.ui.detail, folder: dto.folder };
      await this.refresh();
    } catch (e) {
      this.ui.editError = e instanceof ApiError ? e.key : 'network error';
    } finally {
      this.ui.busy = false;
    }
  }

  /** Renames a preset folder by re-filing every preset inside it (and its children). */
  async renameFolder(from: string, to: string) {
    const affected = this.ui.list.filter(
      (p) => p.folder === from || p.folder.startsWith(from + '/'),
    );
    for (const preset of affected)
      await this.move(preset.id, to + preset.folder.slice(from.length));
  }

  async duplicate(id: string) {
    if (MOCK) {
      const source = this.ui.list.find((p) => p.id === id);
      if (source)
        this.ui.list = [...this.ui.list,
          { ...source, id: crypto.randomUUID(), name: `${source.name} copy`, source: 'web:mock' }];
      return;
    }
    this.ui.busy = true;
    try {
      await api<PresetSummaryDto>('/api/presets', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sourceId: id }),
      });
      await this.refresh();
    } catch (e) {
      this.ui.error = e instanceof ApiError ? e.key : 'network error';
    } finally {
      this.ui.busy = false;
    }
  }

  async remove(id: string) {
    if (MOCK) {
      this.ui.list = this.ui.list.filter((p) => p.id !== id);
      if (this.ui.detail?.id === id) this.closeDetail();
      return;
    }
    this.ui.busy = true;
    try {
      await api('/api/presets?id=' + id, { method: 'DELETE' });
      if (this.ui.detail?.id === id) this.closeDetail();
      await this.refresh();
    } catch (e) {
      this.ui.error = e instanceof ApiError ? e.key : 'network error';
    } finally {
      this.ui.busy = false;
    }
  }
}

// --- mock fabrication (VITE_MOCK=1) ---

function mockList(): PresetSummaryDto[] {
  const now = Date.now();
  return [
    { id: 'mock-preset-0', name: 'Aurora Loop', folder: 'Intercity', source: 'train:Aurora Express 1', createdMs: now - 86_400_000, updatedMs: now - 3_600_000, entries: 4 },
    { id: 'mock-preset-1', name: 'Freight Shuttle', folder: '', source: 'item:Stacode', createdMs: now - 400_000_000, updatedMs: now - 86_400_000, entries: 2 },
    { id: 'mock-preset-2', name: 'Night Service', folder: 'Intercity/Sleeper', source: 'train:Borealis Express 1', createdMs: now - 7_200_000, updatedMs: now - 7_200_000, entries: 6 },
    { id: 'mock-preset-3', name: 'Airport Shuttle', folder: 'Regional', source: 'web:mock', createdMs: now - 200_000, updatedMs: now - 200_000, entries: 3 },
  ];
}

function mockDetail(id: string): PresetDetailDto {
  const summary = mockList().find((p) => p.id === id) ?? mockList()[0];
  return {
    ...summary,
    cyclic: true,
    schedule: [
      {
        instruction: { id: 'create:destination', fields: { Text: 'Central 1' }, editable: [] },
        conditions: [
          [
            {
              id: 'create:delay',
              fields: { Value: 30, TimeUnit: 1 },
              editable: [
                { key: 'Value', type: 'int', min: 0, max: 10000 },
                { key: 'TimeUnit', type: 'int', min: 0, max: 2 },
              ],
            },
          ],
          [
            {
              id: 'createrailwaysnavigator:train_separation',
              fields: { Ticks: 1200, TrainFilter: 1, StationFilter: '' },
              editable: [
                { key: 'Ticks', type: 'int', min: 0, max: 72000 },
                { key: 'TrainFilter', type: 'int', min: 0, max: 3 },
                { key: 'StationFilter', type: 'string', max: 128 },
              ],
            },
          ],
        ],
      },
      {
        instruction: {
          id: 'create:throttle',
          fields: { Value: 75 },
          editable: [{ key: 'Value', type: 'int', min: 5, max: 100 }],
        },
        conditions: [],
      },
      {
        instruction: { id: 'create:destination', fields: { Text: 'Harbor 2' }, editable: [] },
        conditions: [
          [
            {
              id: 'realism:time_of_day_realistic',
              fields: { Hour: 8, Minute: 30 },
              editable: [
                { key: 'Hour', type: 'int', min: 0, max: 23 },
                { key: 'Minute', type: 'int', min: 0, max: 59 },
              ],
            },
          ],
        ],
      },
    ],
  };
}

export const presets = new PresetsStore();
