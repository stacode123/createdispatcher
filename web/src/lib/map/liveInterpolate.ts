// Live position interpolation over a short ring of samples, walking edge adjacency so trains
// follow the track between updates instead of cutting corners.
//
// Why a ring and not just prev/curr: the render clock lags ~1.5 sample intervals, so at the
// moment a new frame arrives the render tick is still INSIDE the previous bracket. With only
// two samples the bracket shifts out from under the clock — position clamps forward (jump),
// freezes until the clock re-enters the window, then moves again. The ring keeps enough
// history that the render tick always finds its true bracket.
import { posAlongEdge, type LoadedGraph } from './geometry';
import type { LiveSample } from '../stores/liveTrains.svelte';

const pA: [number, number] = [0, 0];
const pB: [number, number] = [0, 0];
const SNAP_BLOCKS = 200;
const MAX_HOPS = 4;
/** Bracket gaps longer than this are dwell/delta gaps — hold position, don't creep across. */
const GAP_TICKS = 60;

/** Per-train memo of the adjacency walk between a sample pair (stable for a whole second). */
export interface WalkCache {
  key: number;
  path: number[] | null;
}

export function liveTrainPos(
  g: LoadedGraph,
  samples: readonly LiveSample[],
  renderTick: number,
  cache: WalkCache,
  out: { x: number; z: number; speed: number },
) {
  const n = samples.length;
  const newest = samples[n - 1];
  if (n === 1 || renderTick >= newest.tick) {
    sampleAt(g, newest, out);
    return;
  }
  if (renderTick <= samples[0].tick) {
    sampleAt(g, samples[0], out);
    return;
  }

  // Bracket search from the end — rings are tiny and the render tick trails near the newest.
  let hi = n - 1;
  while (hi > 1 && samples[hi - 1].tick > renderTick) hi--;
  const a = samples[hi - 1];
  const b = samples[hi];

  // Dwell/delta gap or graph hop: the train truthfully stood still (or teleported) — hold at
  // the earlier sample; the bracket advances past it once the clock reaches the later one.
  if (b.tick - a.tick > GAP_TICKS || a.graphId !== b.graphId) {
    sampleAt(g, a, out);
    return;
  }

  const f = (renderTick - a.tick) / (b.tick - a.tick);
  out.speed = a.speed + (b.speed - a.speed) * f;

  if (a.edge === b.edge) {
    posAlongEdge(g, a.edge, a.off + (b.off - a.off) * f, pA);
    out.x = pA[0];
    out.z = pA[1];
    return;
  }

  const key = a.edge * 0x100000 + b.edge;
  if (cache.key !== key) {
    cache.key = key;
    cache.path = findWalk(g, a.edge, b.edge);
  }
  const walk = cache.path;
  if (!walk) {
    // no short forward walk (reversal/portal) — chord lerp, snap when far apart
    posAlongEdge(g, a.edge, a.off, pA);
    posAlongEdge(g, b.edge, b.off, pB);
    const dx = pB[0] - pA[0];
    const dz = pB[1] - pA[1];
    if (dx * dx + dz * dz > SNAP_BLOCKS * SNAP_BLOCKS) {
      out.x = pB[0];
      out.z = pB[1];
    } else {
      out.x = pA[0] + dx * f;
      out.z = pA[1] + dz * f;
    }
    return;
  }

  let total = g.len[a.edge] - a.off;
  for (let i = 1; i < walk.length - 1; i++) total += g.len[walk[i]];
  total += b.off;
  let target = f * total;
  if (target <= g.len[a.edge] - a.off) {
    posAlongEdge(g, a.edge, a.off + target, pA);
  } else {
    target -= g.len[a.edge] - a.off;
    let i = 1;
    while (i < walk.length - 1 && target > g.len[walk[i]]) {
      target -= g.len[walk[i]];
      i++;
    }
    posAlongEdge(g, walk[i], i === walk.length - 1 ? Math.min(target, b.off) : target, pA);
  }
  out.x = pA[0];
  out.z = pA[1];
}

function sampleAt(g: LoadedGraph, s: LiveSample, out: { x: number; z: number; speed: number }) {
  posAlongEdge(g, s.edge, s.off, pA);
  out.x = pA[0];
  out.z = pA[1];
  out.speed = s.speed;
}

/** DFS ≤ {@link MAX_HOPS} forward hops from `fromEdge` to `toEdge`, never reversing onto a twin. */
function findWalk(g: LoadedGraph, fromEdge: number, toEdge: number): number[] | null {
  const path: number[] = [fromEdge];
  return dfs(g, fromEdge, toEdge, path, 0) ? path : null;
}

function dfs(g: LoadedGraph, edge: number, target: number, path: number[], depth: number): boolean {
  if (depth >= MAX_HOPS) return false;
  const node = g.to[edge];
  for (let i = g.adjOff[node]; i < g.adjOff[node + 1]; i++) {
    const next = g.adjList[i];
    if (next === g.opp[edge]) continue;
    path.push(next);
    if (next === target || dfs(g, next, target, path, depth + 1)) return true;
    path.pop();
  }
  return false;
}
