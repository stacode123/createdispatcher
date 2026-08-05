import { api, MOCK } from '../api/http';
import type { ReplayData, ReplayIndexEntry } from '../api/types';
import type { LiveSample } from './liveTrains.svelte';
import type { WalkCache } from '../map/liveInterpolate';
import { focusMap } from './mapFocus.svelte';

export interface ReplayTrain {
  id: string;
  name: string;
  involved: boolean;
  samples: LiveSample[];
  cache: WalkCache;
}

/**
 * Replay browsing + transport. The loaded replay's trains are plain (rAF hot path, rendered
 * through the same ring-bracket interpolation as live trains); list/transport state is runes.
 */
class ReplaysStore {
  /** Hot playback clock (rAF); mirrored into ui at ~10 Hz. */
  t = 0;
  playing = false;
  speedMult = 5;
  /** Restart from the top instead of stopping at the end. */
  loop = false;
  trains: ReplayTrain[] = [];
  private uiAccumulator = 0;
  private mockData = new Map<string, ReplayData>();

  ui = $state({
    index: [] as ReplayIndexEntry[],
    byNotification: {} as Record<string, string>,
    loadedId: null as string | null,
    meta: null as ReplayData | null,
    loading: false,
    t: 0,
    playing: false,
    speedMult: 5,
    loop: false,
  });

  async refreshIndex() {
    if (MOCK) return;
    try {
      const response = await api<{ replays: ReplayIndexEntry[] }>('/api/replays');
      this.ui.index = response.replays;
      const map: Record<string, string> = {};
      for (const entry of response.replays) map[entry.notificationId] = entry.id;
      this.ui.byNotification = map;
    } catch {
      /* offline */
    }
  }

  noteFinalized(id: string, notificationId: string) {
    this.ui.byNotification = { ...this.ui.byNotification, [notificationId]: id };
    this.refreshIndex();
  }

  registerMock(data: ReplayData) {
    this.mockData.set(data.id, data);
    this.ui.index = [
      {
        id: data.id,
        notificationId: data.notificationId,
        kind: data.kind,
        message: data.message,
        createdMs: Date.now(),
        graphId: data.graphId,
        graphVersion: data.graphVersion,
        dim: data.dim,
        durationTicks: data.endTick - data.startTick,
        trains: data.trains.length,
      },
      ...this.ui.index.filter((e) => e.id !== data.id),
    ];
    this.ui.byNotification = { ...this.ui.byNotification, [data.notificationId]: data.id };
  }

  async load(id: string) {
    if (this.ui.loadedId === id && this.ui.meta) return;
    this.ui.loading = true;
    try {
      const data = this.mockData.get(id) ?? (await api<ReplayData>(`/api/replays/${id}`));
      this.trains = data.trains.map((train) => ({
        id: train.id,
        name: train.name,
        involved: data.involved.includes(train.id),
        samples: train.s.map(([tick, edge, off, speed]) => ({
          tick,
          graphId: data.graphId,
          edge,
          off,
          speed,
          dim: 0,
        })),
        cache: { key: -1, path: null },
      }));
      this.ui.meta = data;
      this.ui.loadedId = id;
      this.t = data.startTick;
      this.playing = true;
      this.syncUi();
      focusMap(data.x, data.z, data.dim);
    } finally {
      this.ui.loading = false;
    }
  }

  /** Raise tick within the window (older servers without eventTick: assume a 60 s tail). */
  eventTick(): number {
    const meta = this.ui.meta;
    return meta ? (meta.eventTick ?? meta.endTick - 60 * 20) : 0;
  }

  toggle() {
    const meta = this.ui.meta;
    if (!meta) return;
    // pressing play at the very end rewinds rather than doing nothing
    if (!this.playing && this.t >= meta.endTick) this.t = meta.startTick;
    this.playing = !this.playing;
    this.syncUi();
  }

  toggleLoop() {
    this.loop = !this.loop;
    this.syncUi();
  }

  setSpeed(mult: number) {
    this.speedMult = mult;
    this.syncUi();
  }

  seek(tick: number) {
    const meta = this.ui.meta;
    if (!meta) return;
    this.t = Math.min(meta.endTick, Math.max(meta.startTick, tick));
    this.syncUi();
  }

  /** Called from the rAF loop while the replay view is active. */
  advance(dtMs: number) {
    const meta = this.ui.meta;
    if (!meta || !this.playing) return;
    this.t += (dtMs / 1000) * 20 * this.speedMult;
    if (this.t >= meta.endTick) {
      if (this.loop) {
        this.t = meta.startTick;
      } else {
        this.t = meta.endTick;
        this.playing = false;
      }
      this.syncUi();
      return;
    }
    this.uiAccumulator += dtMs;
    if (this.uiAccumulator >= 100) {
      this.uiAccumulator = 0;
      this.ui.t = this.t;
      this.ui.playing = this.playing;
    }
  }

  syncUi() {
    this.ui.t = this.t;
    this.ui.playing = this.playing;
    this.ui.speedMult = this.speedMult;
    this.ui.loop = this.loop;
  }
}

export const replays = new ReplaysStore();
