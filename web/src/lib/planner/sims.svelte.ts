/**
 * Planner sim state (W5): the draft assignment list (preset → train, plus
 * removals), the run flow against /api/sims (queue → progress over the `sim`
 * SSE event → gz result), playback loading, and the conflict / root-cause /
 * diagram panel data. Assignments live entirely in the browser — the POST
 * carries the full list, the server keeps no session state.
 */
import { MOCK, api, ApiError } from '../api/http';
import type { PlanDto, SimDiagramDto, SimEventDto, SimResultDto } from '../api/types';
import { graphStore } from '../stores/graphs.svelte';
import { liveTrains } from '../stores/liveTrains.svelte';
import { playback } from '../stores/playback.svelte';
import { clock } from '../stores/clock.svelte';
import { focusMap } from '../stores/mapFocus.svelte';
import { colorFor } from '../map/palette';
import { posAlongEdge } from '../map/geometry';

export interface AssignmentDraft {
  presetId: string;
  presetName: string;
}

export interface ConflictRow {
  index: number;
  type: number;
  typeName: string;
  start: number;
  end: number;
  count: number;
  x: number;
  z: number;
  dim: number;
  resource: string;
  trains: number[];
  nonDet: boolean;
}

export interface RootCauseRow {
  index: number;
  root: number;
  kind: string;
  detail: string;
  stranded: number[];
  since: number;
  x: number;
  z: number;
  dim: number;
}

export const CONFLICT_TYPES = ['section', 'deadlock', 'headway', 'platform'];
export const CONFLICT_COLORS = ['#ff6040', '#dd2222', '#ffc020', '#c080ff'];

const CAUSE_LABELS: Record<string, string> = {
  NO_MATCHING_STATION: 'no matching station',
  NO_PATH: 'no route',
  FINISHED_PARKED: 'finished & parked',
  OBSTACLE: 'obstacle',
  SCHEDULE_HOLD: 'schedule hold',
  DEADLOCK: 'deadlock',
  SIGNAL_UNKNOWN: 'signal wait',
};

export function causeLabel(kind: string): string {
  return CAUSE_LABELS[kind] ?? kind.toLowerCase().replace(/_/g, ' ');
}

/**
 * Whether a roster state means "running a live schedule" — which is exactly what the
 * clear-timetable mode takes off the network. Note IDLE counts: it means the train has a
 * schedule but no current destination (dwelling at a station under its wait conditions,
 * or unable to route), NOT that it is unscheduled. The trains that survive a clear are
 * the ones the snapshotter turns into static obstacles: paused, completed, schedule-less
 * (manual) and derailed. A schedule that fails to compile also becomes an obstacle — the
 * browser cannot know that, so this preview is a close approximation, not a promise.
 */
export function hasLiveSchedule(state: string): boolean {
  return state === 'RUNNING' || state === 'IDLE';
}

/**
 * Live HH:MM mask for the start-time field: digits only, colon fixed after the
 * hour. Typing never has to guess the format — the field always reads xx:xx.
 */
export function maskStartTime(raw: string): string {
  const digits = raw.replace(/\D/g, '').slice(0, 4);
  return digits.length <= 2 ? digits : digits.slice(0, 2) + ':' + digits.slice(2);
}

/**
 * Settle a partial entry into a full, clamped "HH:MM" (blank stays blank = now).
 * Minutes fill left-to-right the way the mask shows them, so "8" -> 08:00 and
 * "083" -> 08:30. Runs on blur AND before a run, so an un-blurred field can't
 * silently fall back to "now".
 */
export function normalizeStartTime(raw: string): string {
  const digits = raw.replace(/\D/g, '').slice(0, 4);
  if (!digits) return '';
  const hour = Math.min(23, +digits.slice(0, 2));
  const minute = digits.length > 2 ? Math.min(59, +digits.slice(2).padEnd(2, '0')) : 0;
  return String(hour).padStart(2, '0') + ':' + String(minute).padStart(2, '0');
}

type Phase = 'idle' | 'queued' | 'preparing' | 'running' | 'loading' | 'done' | 'failed' | 'cancelled';

class SimsStore {
  ui = $state({
    assignments: {} as Record<string, AssignmentDraft>,
    /** Manual removals — the exception list while running against live traffic. */
    removals: [] as string[],
    /**
     * Clear the timetable: every scheduled train leaves the network unless assigned or
     * kept. The planner default — you are designing a timetable, not watching the one
     * already running. Unscheduled trains stay put; they physically block track.
     */
    removeScheduled: true,
    /** Scheduled trains spared from the clear, unassigned. */
    keeps: [] as string[],
    /** Click-to-assign fallback: armed preset waits for a train click. */
    armed: null as { presetId: string; name: string } | null,
    /** Pointer-DnD ghost chip; set once the 6px/150ms threshold passes. */
    drag: null as { presetId: string; name: string; x: number; y: number } | null,
    horizonHours: 12,
    /** Blank = server default headway. */
    headwaySeconds: '',
    /** Blank = start now; else "HH:MM" in-game wall clock. */
    startTime: '',
    /** The saved plan this draft came from ('' = unsaved). */
    planId: '',
    planName: '',
    /** Train names remembered from a loaded plan, for rows no longer in the roster. */
    planTrainNames: {} as Record<string, string>,
    phase: 'idle' as Phase,
    error: '',
    simId: '',
    graphId: '',
    progressTicks: 0,
    horizonTicks: 0,
    queuePos: 0,
    ticksSimulated: 0,
    truncated: false,
    /** Names of live trains this run took off the network. */
    removed: [] as string[],
    conflicts: [] as ConflictRow[],
    rootCauses: [] as RootCauseRow[],
    issues: [] as [string, string, string][],
    excluded: [] as [string, string, string][],
    /** Per-train-index metadata for panel rendering (name, color, assigned). */
    trainNames: [] as string[],
    tab: 'conflicts' as 'conflicts' | 'causes' | 'diagram',
    /** Which panel row is highlighted (focus follows). */
    focused: '',
    // diagram tab
    diagramFrom: '',
    diagramTo: '',
    diagramStatus: 'idle' as 'idle' | 'loading' | 'ready' | 'error',
    diagramError: '',
    groups: [] as string[],
    rev: 0,
  });

  result: SimResultDto | null = null;
  diagram: SimDiagramDto | null = null;
  private mockTimer: ReturnType<typeof setInterval> | null = null;
  private statusTimer: ReturnType<typeof setInterval> | null = null;
  private groupsFor = '';

  // --- assignment drafting ---

  assign(trainId: string, presetId: string, presetName: string) {
    this.ui.assignments = { ...this.ui.assignments, [trainId]: { presetId, presetName } };
    this.ui.removals = this.ui.removals.filter((id) => id !== trainId);
    this.ui.keeps = this.ui.keeps.filter((id) => id !== trainId);
    this.ui.armed = null;
  }

  unassign(trainId: string) {
    const next = { ...this.ui.assignments };
    delete next[trainId];
    this.ui.assignments = next;
  }

  /**
   * The per-train exception toggle. Its meaning follows the mode: clearing the
   * timetable, it keeps a train running its own schedule; running against live
   * traffic, it takes a train off the network.
   */
  toggleException(trainId: string) {
    if (this.ui.removeScheduled) {
      this.ui.keeps = this.ui.keeps.includes(trainId)
        ? this.ui.keeps.filter((id) => id !== trainId)
        : [...this.ui.keeps, trainId];
      if (this.ui.keeps.includes(trainId)) this.unassign(trainId);
      return;
    }
    if (this.ui.removals.includes(trainId)) {
      this.ui.removals = this.ui.removals.filter((id) => id !== trainId);
    } else {
      this.ui.removals = [...this.ui.removals, trainId];
      this.unassign(trainId);
    }
  }

  /** True when this train carries the current mode's exception flag. */
  isException(trainId: string): boolean {
    return this.ui.removeScheduled
      ? this.ui.keeps.includes(trainId)
      : this.ui.removals.includes(trainId);
  }

  setMode(removeScheduled: boolean) {
    this.ui.removeScheduled = removeScheduled;
  }

  clearDraft() {
    this.ui.assignments = {};
    this.ui.removals = [];
    this.ui.keeps = [];
    this.ui.armed = null;
    this.ui.planId = '';
    this.ui.planName = '';
    this.ui.planTrainNames = {};
  }

  arm(presetId: string, name: string) {
    this.ui.armed = this.ui.armed?.presetId === presetId ? null : { presetId, name };
  }

  // --- saved plans ---

  /** The current draft as a plan body (names carried so it still reads if ids vanish). */
  exportDraft(name: string, id?: string): Record<string, unknown> {
    const nameOf = (trainId: string) =>
      liveTrains.ui.roster.find((t) => t.id === trainId)?.name
      ?? this.ui.planTrainNames[trainId] ?? '';
    return {
      ...(id ? { id } : {}),
      name,
      graphId: this.targetGraphId(),
      removeScheduled: this.ui.removeScheduled,
      horizonHours: this.ui.horizonHours,
      headwaySeconds: this.ui.headwaySeconds.trim() === ''
        ? -1 : Math.max(0, Math.round(+this.ui.headwaySeconds)),
      startTime: this.ui.startTime,
      assignments: Object.entries(this.ui.assignments).map(([trainId, draft]) => ({
        trainId,
        trainName: nameOf(trainId),
        presetId: draft.presetId,
        presetName: draft.presetName,
      })),
      keeps: this.ui.keeps.map((trainId) => ({ trainId, trainName: nameOf(trainId) })),
      removals: this.ui.removals.map((trainId) => ({ trainId, trainName: nameOf(trainId) })),
    };
  }

  /** Replaces the draft with a saved plan's contents. */
  applyPlan(plan: PlanDto) {
    const assignments: Record<string, AssignmentDraft> = {};
    const names: Record<string, string> = {};
    for (const row of plan.assignments) {
      assignments[row.trainId] = { presetId: row.presetId, presetName: row.presetName };
      names[row.trainId] = row.trainName;
    }
    for (const row of [...plan.keeps, ...plan.removals]) names[row.trainId] = row.trainName;
    this.ui.assignments = assignments;
    this.ui.planTrainNames = names;
    this.ui.keeps = plan.keeps.map((row) => row.trainId);
    this.ui.removals = plan.removals.map((row) => row.trainId);
    this.ui.removeScheduled = plan.removeScheduled;
    this.ui.horizonHours = plan.horizonHours;
    this.ui.headwaySeconds = plan.headwaySeconds < 0 ? '' : String(plan.headwaySeconds);
    this.ui.startTime = normalizeStartTime(plan.startTime);
    this.ui.planId = plan.id;
    this.ui.planName = plan.name;
    this.ui.armed = null;
  }

  /** Draft rows whose train is not in the live roster — a plan gone stale. */
  get staleRows(): number {
    const live = new Set(liveTrains.ui.roster.map((t) => t.id));
    let stale = 0;
    for (const trainId of [...Object.keys(this.ui.assignments), ...this.ui.keeps, ...this.ui.removals])
      if (!live.has(trainId)) stale++;
    return stale;
  }

  cancelDrag() {
    this.ui.drag = null;
  }

  get draftCount(): number {
    return Object.keys(this.ui.assignments).length;
  }

  get exceptionCount(): number {
    return this.ui.removeScheduled ? this.ui.keeps.length : this.ui.removals.length;
  }

  /** The network the sim runs on: the assigned/removed trains' graph, else the biggest loaded. */
  targetGraphId(): string {
    const touched = [...Object.keys(this.ui.assignments), ...this.ui.keeps, ...this.ui.removals];
    for (const trainId of touched) {
      const entry = liveTrains.ui.roster.find((t) => t.id === trainId);
      if (entry?.graphId) return entry.graphId;
    }
    let biggest = '';
    let top = -1;
    for (const g of graphStore.graphs)
      if (g.from.length > top) {
        top = g.from.length;
        biggest = g.id;
      }
    return biggest;
  }

  /** All touched trains must live on one network — the sim snapshots a single graph. */
  crossGraphConflict(): boolean {
    const graphs = new Set<string>();
    for (const trainId of [...Object.keys(this.ui.assignments), ...this.ui.keeps, ...this.ui.removals]) {
      const entry = liveTrains.ui.roster.find((t) => t.id === trainId);
      if (entry?.graphId) graphs.add(entry.graphId);
    }
    return graphs.size > 1;
  }

  // --- run flow ---

  /** "HH:MM" → absolute dayTime of the next occurrence, or null for "now". */
  private startDayTime(): number | null {
    const text = normalizeStartTime(this.ui.startTime);
    if (!text) return null;
    const hour = +text.slice(0, 2);
    const minute = +text.slice(3);
    const target = ((hour + 18) % 24) * 1000 + Math.round((minute / 60) * 1000);
    const now = clock.estDayTime();
    let candidate = Math.floor(now / 24000) * 24000 + target;
    while (candidate < now) candidate += 24000;
    return candidate;
  }

  async run() {
    if (this.ui.phase === 'queued' || this.ui.phase === 'preparing' || this.ui.phase === 'running')
      return;
    this.ui.error = '';
    // settle the field even if it never lost focus, so what runs is what is shown
    this.ui.startTime = normalizeStartTime(this.ui.startTime);
    if (this.ui.removeScheduled && !this.draftCount && !this.ui.keeps.length) {
      this.ui.phase = 'failed';
      this.ui.error = 'nothing to run — assign a preset to a train, or keep one';
      return;
    }
    if (this.crossGraphConflict()) {
      this.ui.phase = 'failed';
      this.ui.error = 'assigned trains span multiple networks — one network per sim';
      return;
    }
    const graphId = this.targetGraphId();
    if (!graphId) {
      this.ui.phase = 'failed';
      this.ui.error = 'no network loaded';
      return;
    }
    this.ui.graphId = graphId;
    this.ui.focused = '';
    if (MOCK) {
      this.mockRun();
      return;
    }
    const headway = this.ui.headwaySeconds.trim();
    const body = {
      graphId,
      assignments: Object.entries(this.ui.assignments).map(([trainId, draft]) => ({
        trainId,
        presetId: draft.presetId,
        valueOverrides: [],
      })),
      removals: this.ui.removals,
      keeps: this.ui.keeps,
      removeScheduled: this.ui.removeScheduled,
      startDayTime: this.startDayTime(),
      horizonHours: this.ui.horizonHours,
      headwaySeconds: headway === '' ? null : Math.max(0, Math.round(+headway)),
    };
    this.ui.phase = 'queued';
    this.ui.progressTicks = 0;
    try {
      const response = await api<{ simId: string }>('/api/sims', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      this.ui.simId = response.simId;
      // The run can be OVER before this response lands — on a small network the
      // engine finishes in milliseconds, and every `sim` event it broadcast
      // named an id this store did not know yet, so onSse dropped them all and
      // the dock sat at "queued" forever. Reconcile once against the job's real
      // state, then keep a slow poll as the safety net for events lost to an
      // SSE reconnect.
      void this.syncStatus();
      this.startStatusWatchdog();
    } catch (e) {
      this.ui.phase = 'failed';
      this.ui.error = e instanceof ApiError
        ? (e.key === 'cooldown' ? 'cooldown — wait a moment between runs'
          : e.key === 'queue_full' ? 'sim queue is full — try again shortly'
          : e.key + (e.detail ? `: ${e.detail}` : ''))
        : 'network error';
    }
  }

  async cancel() {
    if (MOCK) {
      if (this.mockTimer) clearInterval(this.mockTimer);
      this.mockTimer = null;
      this.ui.phase = 'cancelled';
      return;
    }
    if (!this.ui.simId) return;
    try {
      await api(`/api/sims/${this.ui.simId}`, { method: 'DELETE' });
    } catch {
      /* stale id — the SSE event settles the state */
    }
  }

  /** Pulls the job's authoritative state and feeds it through the SSE path. */
  private async syncStatus() {
    if (MOCK || !this.ui.simId) return;
    const id = this.ui.simId;
    try {
      this.onSse(await api<SimEventDto>(`/api/sims/${id}`));
    } catch (e) {
      if (id !== this.ui.simId) return;
      // 404 = evicted from the result cache; anything else is transient and the
      // next poll (or an SSE event) settles it.
      if (e instanceof ApiError && e.status === 404) {
        this.ui.phase = 'failed';
        this.ui.error = 'the server no longer has this run — start a new one';
        this.stopStatusWatchdog();
      }
    }
  }

  private startStatusWatchdog() {
    this.stopStatusWatchdog();
    this.statusTimer = setInterval(() => {
      if (this.ui.phase === 'queued' || this.ui.phase === 'preparing' || this.ui.phase === 'running')
        void this.syncStatus();
      else this.stopStatusWatchdog();
    }, 3000);
  }

  private stopStatusWatchdog() {
    if (this.statusTimer) clearInterval(this.statusTimer);
    this.statusTimer = null;
  }

  onSse(event: SimEventDto) {
    if (event.simId !== this.ui.simId) return;
    this.ui.progressTicks = event.progressTicks;
    this.ui.horizonTicks = event.horizonTicks;
    this.ui.queuePos = event.queuePos;
    switch (event.state) {
      case 'queued':
        this.ui.phase = 'queued';
        break;
      case 'preparing':
        this.ui.phase = 'preparing';
        break;
      case 'running':
        this.ui.phase = 'running';
        break;
      case 'done':
        if (this.ui.phase !== 'loading' && this.ui.phase !== 'done') {
          this.ui.phase = 'loading';
          this.ui.issues = event.issues ?? [];
          void this.loadResult();
        }
        break;
      case 'failed':
        this.ui.phase = 'failed';
        this.ui.error = event.error ?? 'sim failed';
        break;
      case 'cancelled':
        this.ui.phase = 'cancelled';
        break;
    }
  }

  private async loadResult() {
    try {
      const dto = await api<SimResultDto>(`/api/sims/${this.ui.simId}/result`);
      this.applyResult(dto);
    } catch (e) {
      this.ui.phase = 'failed';
      this.ui.error = e instanceof ApiError ? e.key : 'network error';
    }
  }

  private applyResult(dto: SimResultDto) {
    this.stopStatusWatchdog();
    this.result = dto;
    this.ui.ticksSimulated = dto.meta.ticks;
    this.ui.truncated = dto.meta.truncated;
    this.ui.removed = dto.removed ?? [];
    this.ui.trainNames = dto.trains.map((t) => t.n);
    this.ui.excluded = dto.excluded ?? [];
    // The result carries the same issue triples the SSE event did — the result wins.
    if (dto.overrideIssues?.length || !this.ui.issues.length)
      this.ui.issues = dto.overrideIssues ?? [];
    this.ui.conflicts = (dto.conflicts ?? []).map((c, index) => ({
      index,
      type: c[0],
      typeName: CONFLICT_TYPES[c[0]] ?? String(c[0]),
      start: c[1],
      end: c[2],
      count: c[3],
      x: c[4],
      z: c[5],
      dim: c[6],
      resource: c[7],
      trains: c[8],
      nonDet: !!c[9],
    }));
    this.ui.rootCauses = (dto.rootCauses ?? []).map((r, index) => ({
      index,
      root: r[0],
      kind: r[1],
      detail: r[2],
      stranded: r[3],
      since: r[4],
      x: r[5],
      z: r[6],
      dim: r[7],
    }));
    playback.load(dto, dto.meta.graphId || this.ui.graphId);
    playback.playing = true;
    playback.syncUi();
    this.diagram = null;
    this.ui.diagramStatus = 'idle';
    this.ui.phase = 'done';
    this.ui.rev++;
    if (this.ui.tab === 'diagram') void this.loadDiagram();
  }

  colorOf(trainIndex: number): string {
    return playback.trains[trainIndex]?.color ?? colorFor(String(trainIndex));
  }

  nameOf(trainIndex: number): string {
    return this.ui.trainNames[trainIndex] ?? `#${trainIndex}`;
  }

  /** Map dimension name for a sim-result dimension index. */
  private dimName(dim: number): string {
    const g = graphStore.byId.get(this.ui.graphId);
    return g?.dims[dim] ?? g?.dims[0] ?? '';
  }

  focusConflict(row: ConflictRow) {
    this.ui.focused = `c${row.index}`;
    focusMap(row.x, row.z, this.dimName(row.dim));
    playback.seek(Math.max(0, row.start - 100));
    playback.playing = true;
    playback.syncUi();
  }

  focusCause(row: RootCauseRow) {
    this.ui.focused = `r${row.index}`;
    focusMap(row.x, row.z, this.dimName(row.dim));
    playback.seek(Math.max(0, row.since - 100));
    playback.syncUi();
  }

  focusConflictBadge(id: string) {
    const row = this.ui.conflicts.find((c) => `c${c.index}` === id);
    if (row) {
      this.ui.tab = 'conflicts';
      this.focusConflict(row);
    }
  }

  reset() {
    if (this.mockTimer) clearInterval(this.mockTimer);
    this.mockTimer = null;
    this.stopStatusWatchdog();
    this.result = null;
    this.diagram = null;
    this.ui.phase = 'idle';
    this.ui.error = '';
    this.ui.simId = '';
    this.ui.conflicts = [];
    this.ui.rootCauses = [];
    this.ui.issues = [];
    this.ui.excluded = [];
    this.ui.removed = [];
    this.ui.focused = '';
    this.ui.diagramStatus = 'idle';
    playback.unload();
  }

  // --- diagram tab ---

  async ensureGroups() {
    const graphId = this.ui.graphId || this.targetGraphId();
    if (!graphId || this.groupsFor === graphId) return;
    if (MOCK) {
      const names = new Set<string>();
      for (const g of graphStore.graphs)
        if (g.id === graphId)
          for (const st of g.stations) names.add(st.name.replace(/\s+\d+[a-z]?$/i, ''));
      this.groupsFor = graphId;
      this.ui.groups = [...names].sort();
      return;
    }
    try {
      const dto = await api<{ groups: { name: string }[] }>(`/api/stations?graph=${graphId}`);
      this.groupsFor = graphId;
      this.ui.groups = dto.groups.map((g) => g.name);
    } catch {
      this.ui.groups = [];
    }
  }

  setDiagramEndpoint(which: 'from' | 'to', value: string) {
    if (which === 'from') this.ui.diagramFrom = value;
    else this.ui.diagramTo = value;
    void this.loadDiagram();
  }

  async loadDiagram() {
    const from = this.ui.diagramFrom.trim();
    const to = this.ui.diagramTo.trim();
    if (!from || !to || from.toLowerCase() === to.toLowerCase() || this.ui.phase !== 'done') return;
    this.ui.diagramStatus = 'loading';
    this.ui.diagramError = '';
    if (MOCK) {
      this.diagram = mockDiagram(from, to, this.ui.ticksSimulated, this.ui.trainNames);
      this.ui.diagramStatus = 'ready';
      this.ui.rev++;
      return;
    }
    try {
      const dto = await api<SimDiagramDto>(
        `/api/sims/${this.ui.simId}/diagram?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
      );
      if (from !== this.ui.diagramFrom.trim() || to !== this.ui.diagramTo.trim()) return;
      this.diagram = dto;
      this.ui.diagramStatus = 'ready';
      this.ui.rev++;
    } catch (e) {
      this.ui.diagramStatus = 'error';
      this.ui.diagramError = e instanceof ApiError
        ? (e.key === 'route_not_found' ? 'no route between those stations' : e.key)
        : 'network error';
    }
  }

  // --- mock flow (VITE_MOCK=1) ---

  private mockRun() {
    this.ui.phase = 'queued';
    this.ui.simId = 'mock-sim';
    this.ui.progressTicks = 0;
    this.ui.horizonTicks = 48000;
    let progress = 0;
    if (this.mockTimer) clearInterval(this.mockTimer);
    this.mockTimer = setInterval(() => {
      progress += 12000;
      if (progress < 48000) {
        this.ui.phase = 'running';
        this.ui.progressTicks = progress;
        return;
      }
      clearInterval(this.mockTimer!);
      this.mockTimer = null;
      void import('../mock/fixtures').then(({ buildFixture }) => {
        const fixture = buildFixture();
        this.applyResult(mockResult(fixture.sim, this.ui.assignments,
          this.ui.removals, this.ui.keeps, this.ui.removeScheduled));
      });
    }, 600);
  }
}

// --- mock fabrication ---

import type { SimRunDto } from '../api/types';

function mockResult(
  sim: SimRunDto,
  assignments: Record<string, AssignmentDraft>,
  removals: string[],
  keeps: string[],
  removeScheduled: boolean,
): SimResultDto {
  // Mirror the server's rule: explicit removals always go; clearing the timetable
  // additionally drops every scheduled train that is neither assigned nor kept.
  // In the fixture, every 7th train stands idle — those are the unscheduled ones.
  const idIsScheduled = (index: number) => index % 7 !== 3;
  const spared = new Set([...keeps, ...Object.keys(assignments)]);
  const dropped = new Set(removals);
  const removedNames: string[] = [];
  const trains = sim.trains
    .map((t, i) => ({ ...t, id: `mock-${i}`, a: assignments[`mock-${i}`] ? 1 : 0 }))
    .filter((t, i) => {
      const drop = dropped.has(t.id)
        || (removeScheduled && idIsScheduled(i) && !spared.has(t.id));
      if (drop) removedNames.push(t.n);
      return !drop;
    });
  const graph = graphStore.byId.get('fixture');
  const at = (trainIdx: number, sampleIdx: number): [number, number, number] => {
    const t = trains[Math.min(trainIdx, trains.length - 1)];
    const s = t.s[Math.min(sampleIdx, t.s.length - 1)];
    if (!graph) return [s[0], 0, 0];
    const out: [number, number] = [0, 0];
    posAlongEdge(graph, s[1], s[2], out);
    return [s[0], Math.round(out[0]), Math.round(out[1])];
  };
  const c1 = at(2, 40);
  const c2 = at(5, 80);
  const c3 = at(8, 120);
  return {
    meta: { ...sim.meta, graphId: 'fixture', graphVersion: 1, baseTick: 0, truncated: false },
    removed: removedNames,
    trains,
    excluded: [['Depot Shunter', 'dispatcher.sim.exclude.no_schedule', '']],
    overrideIssues: [],
    conflicts: [
      [0, c1[0], c1[0] + 900, 1, c1[1], c1[2], 0, 'section §12', [2, 3], 0],
      [2, c2[0], c2[0] + 300, 3, c2[1], c2[2], 0, 'section §48', [5, 6], 0],
      [3, c3[0], c3[0] + 1200, 1, c3[1], c3[2], 0, 'Line C 2', [8, 9], 1],
    ],
    rootCauses: [
      [4, 'SCHEDULE_HOLD', 'Line A 3', [5, 6], c1[0], c1[1], c1[2], 0],
      [11, 'NO_PATH', 'Harbor 9', [], c2[0], c2[1], c2[2], 0],
    ],
  };
}

function mockDiagram(from: string, to: string, ticks: number, names: string[]): SimDiagramDto {
  const length = 4200;
  const lines = names.slice(0, 6).map((name, i) => {
    const seg: [number, number][] = [];
    const period = 12000 + i * 2200;
    for (let t = 0; t <= ticks; t += 200) {
      const wave = Math.sin(((t + i * 2800) / period) * Math.PI * 2);
      seg.push([t, (0.5 + Math.sign(wave) * Math.min(1, Math.abs(wave) * 1.25) * 0.5) * (length - 270) + 120]);
    }
    return { id: `mock-${i}`, name, segs: [seg] };
  });
  return {
    simId: 'mock-sim', graphId: 'fixture', graphVersion: 1, from, to, length,
    start: 0, rate: 1, ticks,
    stations: [[from, 120], ['Midfield', length * 0.35], ['Junction West', length * 0.62], [to, length - 150]],
    lines, dropped: 0,
  };
}

export const sims = new SimsStore();
