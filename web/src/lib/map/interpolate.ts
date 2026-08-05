// Playback interpolation — the sim_debug.html trainPos/path-cursor math, upgraded to
// arc-table positions (real curves instead of straight chords).
import type { SimRunDto } from '../api/types';
import { posAlongEdge, type LoadedGraph } from './geometry';

const TRAIN_COLORS = ['#E0B040', '#60A0FF', '#FF6090', '#80E0D0', '#C080FF', '#FFA060', '#B0D060', '#DD8888'];
const PHANTOM = '#40FF70';
const OBSTACLE = '#707078';

export interface PlaybackTrain {
  name: string;
  color: string;
  obstacle: boolean;
  ticks: Float64Array;
  edges: Int32Array;
  offs: Float32Array;
  speeds: Float32Array;
  path: Int32Array;
  /** Per-sample cursor into path (sim_debug's `pc` precompute). */
  pc: Int32Array;
}

export function preparePlayback(run: SimRunDto): PlaybackTrain[] {
  return run.trains.map((train, index) => {
    const n = train.s.length;
    const ticks = new Float64Array(n);
    const edges = new Int32Array(n);
    const offs = new Float32Array(n);
    const speeds = new Float32Array(n);
    const path = Int32Array.from(train.path);
    const pc = new Int32Array(n);
    let cursor = 0;
    for (let i = 0; i < n; i++) {
      ticks[i] = train.s[i][0];
      edges[i] = train.s[i][1];
      offs[i] = train.s[i][2];
      speeds[i] = train.s[i][3];
      while (cursor < path.length && path[cursor] !== edges[i]) cursor++;
      if (cursor >= path.length) cursor = Math.max(0, pc[Math.max(0, i - 1)]);
      pc[i] = cursor;
    }
    return {
      name: train.n,
      color: train.p ? PHANTOM : train.o ? OBSTACLE : TRAIN_COLORS[index % TRAIN_COLORS.length],
      obstacle: !!train.o,
      ticks,
      edges,
      offs,
      speeds,
      path,
      pc,
    };
  });
}

const posA: [number, number] = [0, 0];
const posB: [number, number] = [0, 0];

/** Position at a tick; false when the train has no samples. */
export function trainPosAt(
  g: LoadedGraph,
  train: PlaybackTrain,
  tick: number,
  out: { x: number; z: number; speed: number },
): boolean {
  const n = train.ticks.length;
  if (n === 0) return false;
  if (tick <= train.ticks[0]) return sampleAt(g, train, 0, out);
  if (tick >= train.ticks[n - 1]) return sampleAt(g, train, n - 1, out);

  let lo = 0;
  let hi = n - 1;
  while (lo + 1 < hi) {
    const mid = (lo + hi) >> 1;
    if (train.ticks[mid] <= tick) lo = mid;
    else hi = mid;
  }
  const span = train.ticks[hi] - train.ticks[lo];
  if (span <= 0) return sampleAt(g, train, hi, out);
  const f = (tick - train.ticks[lo]) / span;
  out.speed = train.speeds[lo] + (train.speeds[hi] - train.speeds[lo]) * f;

  const e0 = train.edges[lo];
  const e1 = train.edges[hi];
  const o0 = train.offs[lo];
  const o1 = train.offs[hi];

  if (e0 === e1) {
    posAlongEdge(g, e0, o0 + (o1 - o0) * f, posA);
    out.x = posA[0];
    out.z = posA[1];
    return true;
  }

  // Walk the traversed path between the two samples.
  const c0 = train.pc[lo];
  const c1 = train.pc[hi];
  let walkable = c1 > c0 && train.path[c0] === e0 && train.path[c1] === e1;
  if (walkable) {
    for (let k = c0; k < c1 && walkable; k++)
      if (train.path[k + 1] === g.opp[train.path[k]]) walkable = false; // reversal — chord fallback
  }
  if (!walkable) {
    posAlongEdge(g, e0, o0, posA);
    posAlongEdge(g, e1, o1, posB);
    out.x = posA[0] + (posB[0] - posA[0]) * f;
    out.z = posA[1] + (posB[1] - posA[1]) * f;
    return true;
  }

  let total = g.len[e0] - o0;
  for (let k = c0 + 1; k < c1; k++) total += g.len[train.path[k]];
  total += o1;
  let target = f * total;

  if (target <= g.len[e0] - o0) {
    posAlongEdge(g, e0, o0 + target, posA);
  } else {
    target -= g.len[e0] - o0;
    let k = c0 + 1;
    while (k < c1 && target > g.len[train.path[k]]) {
      target -= g.len[train.path[k]];
      k++;
    }
    posAlongEdge(g, train.path[k], k === c1 ? Math.min(target, o1) : target, posA);
  }
  out.x = posA[0];
  out.z = posA[1];
  return true;
}

function sampleAt(
  g: LoadedGraph,
  train: PlaybackTrain,
  index: number,
  out: { x: number; z: number; speed: number },
): boolean {
  posAlongEdge(g, train.edges[index], train.offs[index], posA);
  out.x = posA[0];
  out.z = posA[1];
  out.speed = train.speeds[index];
  return true;
}

/**
 * (edge, offset, speed) at a tick along a playback train — the mock live pump uses this to
 * synthesize 1 Hz "live" frames from the dense fixture samples.
 */
export function trainSampleAt(
  g: LoadedGraph,
  train: PlaybackTrain,
  tick: number,
): { edge: number; off: number; speed: number } | null {
  const n = train.ticks.length;
  if (n === 0) return null;
  if (tick <= train.ticks[0]) return { edge: train.edges[0], off: train.offs[0], speed: train.speeds[0] };
  if (tick >= train.ticks[n - 1])
    return { edge: train.edges[n - 1], off: train.offs[n - 1], speed: train.speeds[n - 1] };

  let lo = 0;
  let hi = n - 1;
  while (lo + 1 < hi) {
    const mid = (lo + hi) >> 1;
    if (train.ticks[mid] <= tick) lo = mid;
    else hi = mid;
  }
  const span = train.ticks[hi] - train.ticks[lo];
  const f = span <= 0 ? 1 : (tick - train.ticks[lo]) / span;
  const speed = train.speeds[lo] + (train.speeds[hi] - train.speeds[lo]) * f;
  const e0 = train.edges[lo];
  const e1 = train.edges[hi];
  const o0 = train.offs[lo];
  const o1 = train.offs[hi];
  if (e0 === e1) return { edge: e0, off: o0 + (o1 - o0) * f, speed };

  const c0 = train.pc[lo];
  const c1 = train.pc[hi];
  let walkable = c1 > c0 && train.path[c0] === e0 && train.path[c1] === e1;
  if (walkable) {
    for (let k = c0; k < c1 && walkable; k++)
      if (train.path[k + 1] === g.opp[train.path[k]]) walkable = false;
  }
  if (!walkable) return f < 0.5 ? { edge: e0, off: o0, speed } : { edge: e1, off: o1, speed };

  let total = g.len[e0] - o0;
  for (let k = c0 + 1; k < c1; k++) total += g.len[train.path[k]];
  total += o1;
  let target = f * total;
  if (target <= g.len[e0] - o0) return { edge: e0, off: o0 + target, speed };
  target -= g.len[e0] - o0;
  let k = c0 + 1;
  while (k < c1 && target > g.len[train.path[k]]) {
    target -= g.len[train.path[k]];
    k++;
  }
  return { edge: train.path[k], off: k === c1 ? Math.min(target, o1) : target, speed };
}
