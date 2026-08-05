/**
 * Corridor diagram state: A→B picking, actual-lines polling (10 s, browser-cache ETags do the
 * dedup) and plan-overlay polling (15 s, 3 s while the projection sim is pending). Line data
 * lives in plain fields read by the dirty-flag canvas; `ui.rev` bumps announce new data.
 */
import { MOCK, api, ApiError } from '../api/http';
import type { CorridorActualDto, CorridorPlanDto, StationGroupsDto } from '../api/types';
import { graphStore } from './graphs.svelte';
import { clock } from './clock.svelte';

const ACTUAL_POLL_MS = 10_000;
const PLAN_POLL_MS = 15_000;
const PLAN_PENDING_POLL_MS = 3_000;

class CorridorStore {
  ui = $state({
    open: false,
    /** Which picker the next map station click fills. */
    picking: null as 'from' | 'to' | null,
    graphId: '',
    from: '',
    to: '',
    status: 'idle' as 'idle' | 'loading' | 'ready' | 'error',
    error: '',
    planState: 'none' as 'none' | 'pending' | 'ready',
    planStale: false,
    /** Server station-group names for the picked graph (datalist source). */
    groups: [] as string[],
    /** Data revision — the canvas redraws when this moves. */
    rev: 0,
  });

  actual: CorridorActualDto | null = null;
  plan: CorridorPlanDto | null = null;

  private actualTimer: ReturnType<typeof setInterval> | null = null;
  private planTimer: ReturnType<typeof setTimeout> | null = null;
  private groupsFor = '';
  private groupsDto: StationGroupsDto | null = null;

  toggle() {
    this.ui.open = !this.ui.open;
    if (!this.ui.open) {
      this.ui.picking = null;
      this.stopPolling();
    } else if (this.ui.graphId && this.ui.from && this.ui.to) {
      this.startPolling();
    } else if (!this.ui.from) {
      this.ui.picking = 'from';
    }
  }

  arm(which: 'from' | 'to') {
    this.ui.picking = this.ui.picking === which ? null : which;
  }

  /** A station click on the map while a picker is armed. */
  async pickStation(platformName: string, graphId: string) {
    const which = this.ui.picking;
    if (!which) return;
    if (this.ui.graphId && this.ui.graphId !== graphId) {
      // The other endpoint lives on a different network — restart from this one.
      this.ui.from = '';
      this.ui.to = '';
      this.stopPolling();
    }
    this.ui.graphId = graphId;
    await this.ensureGroups(graphId);
    const group = this.groupOfPlatform(platformName);
    this.setEndpoint(which, group ?? platformName);
    this.ui.picking = which === 'from' && !this.ui.to ? 'to' : null;
  }

  setEndpoint(which: 'from' | 'to', group: string) {
    if (which === 'from') this.ui.from = group;
    else this.ui.to = group;
    if (!this.ui.graphId && group) {
      // typed before any map pick — assume the biggest loaded network
      let biggest = '';
      let top = -1;
      for (const g of graphStore.graphs)
        if (g.from.length > top) {
          top = g.from.length;
          biggest = g.id;
        }
      this.ui.graphId = biggest;
      if (biggest) void this.ensureGroups(biggest);
    }
    this.restartIfComplete();
  }

  swap() {
    const from = this.ui.from;
    this.ui.from = this.ui.to;
    this.ui.to = from;
    this.restartIfComplete();
  }

  /** Called when the live view unmounts. */
  close() {
    this.ui.open = false;
    this.ui.picking = null;
    this.stopPolling();
  }

  private restartIfComplete() {
    this.actual = null;
    this.plan = null;
    this.ui.planState = 'none';
    this.ui.rev++;
    if (this.ui.open && this.ui.graphId && this.ui.from && this.ui.to
        && this.ui.from.toLowerCase() !== this.ui.to.toLowerCase())
      this.startPolling();
    else this.stopPolling();
  }

  private async ensureGroups(graphId: string) {
    if (this.groupsFor === graphId && this.groupsDto) return;
    if (MOCK) {
      const names = new Set<string>();
      for (const g of graphStore.graphs)
        if (g.id === graphId)
          for (const st of g.stations) names.add(st.name.replace(/\s+\d+[a-z]?$/i, ''));
      this.groupsDto = null;
      this.groupsFor = graphId;
      this.ui.groups = [...names].sort();
      return;
    }
    try {
      const dto = await api<StationGroupsDto>(`/api/stations?graph=${graphId}`);
      this.groupsDto = dto;
      this.groupsFor = graphId;
      this.ui.groups = dto.groups.map((g) => g.name);
    } catch {
      this.groupsDto = null;
      this.ui.groups = [];
    }
  }

  private groupOfPlatform(platformName: string): string | null {
    if (MOCK) return platformName.replace(/\s+\d+[a-z]?$/i, '');
    if (!this.groupsDto) return null;
    for (const group of this.groupsDto.groups)
      for (const platform of group.platforms)
        if (platform.name === platformName) return group.name;
    return null;
  }

  private startPolling() {
    this.stopPolling();
    this.ui.status = 'loading';
    this.ui.error = '';
    void this.pollActual();
    void this.pollPlan();
    this.actualTimer = setInterval(() => void this.pollActual(), ACTUAL_POLL_MS);
  }

  private stopPolling() {
    if (this.actualTimer) clearInterval(this.actualTimer);
    if (this.planTimer) clearTimeout(this.planTimer);
    this.actualTimer = null;
    this.planTimer = null;
  }

  private async pollActual() {
    const { graphId, from, to } = this.ui;
    if (MOCK) {
      this.actual = mockActual(from, to);
      this.ui.status = 'ready';
      this.ui.rev++;
      return;
    }
    try {
      const dto = await api<CorridorActualDto>(
        `/api/corridor/actual?graph=${graphId}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
      );
      if (graphId !== this.ui.graphId || from !== this.ui.from || to !== this.ui.to) return;
      this.actual = dto;
      this.ui.status = 'ready';
      this.ui.error = '';
      this.ui.rev++;
    } catch (e) {
      if (this.ui.status !== 'ready' || (e instanceof ApiError && e.status === 404)) {
        this.ui.status = 'error';
        this.ui.error = e instanceof ApiError && e.key === 'route_not_found'
          ? 'no route between those stations'
          : e instanceof ApiError ? e.key : 'network error';
      }
    }
  }

  private async pollPlan() {
    const { graphId, from, to } = this.ui;
    if (MOCK) {
      this.plan = mockPlan(from, to);
      this.ui.planState = 'ready';
      this.ui.planStale = false;
      this.ui.rev++;
      this.planTimer = setTimeout(() => void this.pollPlan(), PLAN_POLL_MS);
      return;
    }
    let nextMs = PLAN_POLL_MS;
    try {
      const dto = await api<CorridorPlanDto>(
        `/api/corridor/plan?graph=${graphId}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
      );
      if (graphId !== this.ui.graphId || from !== this.ui.from || to !== this.ui.to) return;
      if (dto.pending) {
        this.ui.planState = this.plan ? this.ui.planState : 'pending';
        nextMs = PLAN_PENDING_POLL_MS;
      } else {
        this.plan = dto;
        this.ui.planState = 'ready';
        this.ui.planStale = dto.stale;
        this.ui.rev++;
      }
    } catch {
      /* plan overlay is best-effort — keep whatever we had */
    }
    this.planTimer = setTimeout(() => void this.pollPlan(), nextMs);
  }
}

// --- mock fabrication (VITE_MOCK=1): a believable corridor so the canvas demos offline ---

const MOCK_LENGTH = 4200;
const MOCK_TRAINS = ['Aurora Express 1', 'Aurora Express 2', 'Borealis Express 1', 'Cinder Express 1'];

function mockStations(from: string, to: string): [string, number][] {
  return [
    [from, 120],
    ['Midfield', MOCK_LENGTH * 0.35],
    ['Junction West', MOCK_LENGTH * 0.62],
    [to, MOCK_LENGTH - 150],
  ];
}

function mockPos(train: number, tick: number): number {
  const period = 14000 + train * 2600;
  const phase = train * 3400;
  const wave = Math.sin(((tick + phase) / period) * Math.PI * 2);
  // flatten near the extremes — terminal dwells
  const flat = Math.sign(wave) * Math.min(1, Math.abs(wave) * 1.25);
  return (0.5 + flat * 0.5) * (MOCK_LENGTH - 270) + 120;
}

function mockLine(train: number, fromTick: number, toTick: number, base = 0): [number, number][][] {
  const seg: [number, number][] = [];
  for (let t = fromTick; t <= toTick; t += 200) seg.push([t - base, Math.round(mockPos(train, t) * 10) / 10]);
  return [seg];
}

function mockActual(from: string, to: string): CorridorActualDto {
  const now = Math.round(clock.estServerTick());
  return {
    graphId: 'fixture', graphVersion: 1, from, to,
    length: MOCK_LENGTH, nowTick: now, sinceTick: 0,
    path: [],
    stations: mockStations(from, to),
    lines: MOCK_TRAINS.map((name, i) => ({
      id: `mock-train-${i}`, name, segs: mockLine(i, now - 48_000, now),
    })),
    dropped: 0,
  };
}

function mockPlan(from: string, to: string): CorridorPlanDto {
  const now = Math.round(clock.estServerTick());
  return {
    graphId: 'fixture', graphVersion: 1, from, to,
    baseTick: now, startDayTime: clock.ui.dayTime, dayTimeRate: clock.ui.rate || 1,
    ticksSimulated: 24_000, truncated: false, ageSeconds: 12, stale: false,
    cal: { stretch: 0.14, sigmaPerMin: 160, n: 42 },
    lines: MOCK_TRAINS.map((name, i) => ({
      id: `mock-train-${i}`, name,
      // train 0 has proven itself consistent, train 2 chaotic; others fall back
      cal: i === 0
        ? { stretch: 0.04, sigmaPerMin: 60, n: 22 }
        : i === 2
          ? { stretch: 0.3, sigmaPerMin: 340, n: 15 }
          : null,
      // the "plan" diverges slightly from what the trains actually did
      segs: mockLine(i, now, now + 24_000, now).map((seg) =>
        seg.map(([t, p]) => [t, Math.min(MOCK_LENGTH, p + 60 + i * 25)] as [number, number])),
    })),
    dropped: 0,
  };
}

export const corridor = new CorridorStore();
