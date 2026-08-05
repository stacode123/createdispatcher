// Mock "live" mode: synthesizes 1 Hz SSE-like frames from the fixture sim and pushes them
// through the REAL live path (clock beacon → sample rings → bracket interpolation), so the
// live rendering pipeline is testable without a Minecraft server.
import { buildFixture } from './fixtures';
import { preparePlayback, trainSampleAt, type PlaybackTrain } from '../map/interpolate';
import { posAlongEdge } from '../map/geometry';
import { graphStore } from '../stores/graphs.svelte';
import { liveTrains } from '../stores/liveTrains.svelte';
import { clock } from '../stores/clock.svelte';
import { notifications } from '../stores/notifications.svelte';
import { replays } from '../stores/replays.svelte';
import type { ReplayData, RosterEntry, TrainsEvent, WebNotification } from '../api/types';

let timer: ReturnType<typeof setInterval> | null = null;
let prepared: PlaybackTrain[] = [];
let graphId = '';
let tick = 0;
let frame = 0;

export async function startMockLive() {
  if (timer) return;
  const fixture = buildFixture();
  graphId = fixture.graph.id;
  if (graphStore.status === 'empty') await graphStore.load([fixture.graph]);
  prepared = preparePlayback(fixture.sim);

  const roster: RosterEntry[] = prepared.map((train, i) => ({
    id: `mock-${i}`,
    name: train.name,
    graphId,
    length: 30,
    carriages: 4,
    doubleEnded: true,
    state: 'RUNNING',
    scheduleTitle: 'Fixture line',
    destination: '',
    currentStation: '',
    owner: i % 3 === 0 ? '' : `driver${(i % 4) + 1}`,
  }));
  liveTrains.setRoster(roster, 1);

  tick = 6000;
  frame = 0;
  const graph = graphStore.byId.get(graphId);
  if (!graph) return;
  timer = setInterval(() => {
    tick += 19 + Math.round(Math.random() * 2); // ~20 ticks with TPS jitter
    frame++;
    const p: TrainsEvent['p'] = [];
    prepared.forEach((train, i) => {
      const sample = trainSampleAt(graph, train, tick);
      if (sample) p.push([`mock-${i}`, graphId, sample.edge, sample.off, sample.speed, 0]);
    });
    clock.beacon(tick, Date.now(), tick % 24000, 1);
    liveTrains.applyTrains({
      tick,
      wallMs: Date.now(),
      dayTime: tick % 24000,
      rate: 1,
      full: frame % 10 === 1,
      g: { [graphId]: graph.version },
      p,
    });
    pumpScenario(graph);
  }, 1000);
}

// Cycling demo scenarios so the notification UI (panel, toasts, badges, focus) is testable
// without staging real deadlocks in-game.
const scratchPos: [number, number] = [0, 0];

function fakeNotification(
  graph: NonNullable<ReturnType<typeof graphStore.byId.get>>,
  kind: WebNotification['kind'],
  severity: WebNotification['severity'],
  trainIdx: number[],
  message: string,
  ageTicks: number,
): WebNotification | null {
  const sample = trainSampleAt(graph, prepared[trainIdx[0]], tick);
  if (!sample) return null;
  posAlongEdge(graph, sample.edge, sample.off, scratchPos);
  return {
    id: kind.toLowerCase() + ':mock-' + trainIdx.join('-'),
    kind,
    severity,
    state: 'ACTIVE',
    message,
    trains: trainIdx.map((i) => ({ id: `mock-${i}`, name: prepared[i].name })),
    graphId,
    x: scratchPos[0],
    z: scratchPos[1],
    dim: 'minecraft:overworld',
    sinceTick: tick - ageTicks,
    updatedTick: tick,
    data: {},
  };
}

function pumpScenario(graph: NonNullable<ReturnType<typeof graphStore.byId.get>>) {
  const phase = frame % 50;
  const apply = (n: WebNotification | null) => n && notifications.apply(n);
  if (phase === 8)
    apply(fakeNotification(graph, 'SIGNAL_WAIT', 'WARN', [2],
        `${prepared[2].name} has been waiting at a signal for 2m 10s`, 2600));
  if (phase === 18)
    apply(fakeNotification(graph, 'SIGNAL_WAIT', 'CRITICAL', [2],
        `${prepared[2].name} has been waiting at a signal for 8m 40s`, 10400));
  if (phase === 12)
    apply(fakeNotification(graph, 'DEADLOCK', 'CRITICAL', [4, 5],
        `Deadlock: ${prepared[4].name} ⇄ ${prepared[5].name} (2 trains locked)`, 600));
  if (phase === 20) {
    const detour = fakeNotification(graph, 'DETOUR', 'WARN', [7],
        `${prepared[7].name} is detouring: 3120 b routed vs 1450 b direct (2.2×) — direct route blocked by ${prepared[6].name}`, 0);
    if (detour) {
      detour.data = fakeRoutes(graph);
      notifications.apply(detour);
      fabricateReplay(graph, detour.data);
    }
  }
  if (phase === 28) resolveFake('signal_wait:mock-2');
  if (phase === 40) resolveFake('deadlock:mock-4-5');
  if (phase === 44) resolveFake('detour:mock-7');
}

function resolveFake(id: string) {
  const active = notifications.ui.active.find((n) => n.id === id);
  if (active) notifications.apply({ ...active, state: 'RESOLVED', resolvedTick: tick, updatedTick: tick });
}

/** Two overlapping slices of train 7's path stand in for taken-vs-direct route overlays. */
function fakeRoutes(graph: NonNullable<ReturnType<typeof graphStore.byId.get>>): Record<string, unknown> {
  const path = Array.from(prepared[7].path);
  return {
    ratio: 2.2,
    routesGraphVersion: graph.version,
    routedEdges: path.slice(10, 52),
    directEdges: path.slice(10, 24),
  };
}

/** Register a fabricated replay of the fake detour so the Replay tab is testable offline. */
function fabricateReplay(
  graph: NonNullable<ReturnType<typeof graphStore.byId.get>>,
  eventData: Record<string, unknown>,
) {
  const start = Math.max(0, tick - 120 * 20);
  const end = tick + 60 * 20;
  const sampleTrain = (idx: number) => {
    const rows: [number, number, number, number][] = [];
    for (let t = start; t <= end; t += 20) {
      const sample = trainSampleAt(graph, prepared[idx], t);
      if (sample) rows.push([t, sample.edge, Math.round(sample.off * 10) / 10, sample.speed]);
    }
    return rows;
  };
  const at = trainSampleAt(graph, prepared[7], tick);
  if (!at) return;
  posAlongEdge(graph, at.edge, at.off, scratchPos);
  const data: ReplayData = {
    id: 'mock-replay-' + frame,
    notificationId: 'detour:mock-7',
    kind: 'DETOUR',
    message: `${prepared[7].name} is detouring: 3120 b routed vs 1450 b direct (2.2×) — direct route blocked by ${prepared[6].name}`,
    graphId,
    graphVersion: graph.version,
    dim: 'minecraft:overworld',
    x: scratchPos[0],
    z: scratchPos[1],
    startTick: start,
    eventTick: tick,
    endTick: end,
    cadenceTicks: 20,
    involved: ['mock-7'],
    eventData,
    trains: [6, 7, 8].map((idx) => ({ id: `mock-${idx}`, name: prepared[idx].name, s: sampleTrain(idx) })),
  };
  replays.registerMock(data);
}

export function stopMockLive() {
  if (timer) clearInterval(timer);
  timer = null;
  liveTrains.clear();
}
