// Deterministic synthetic network + sim run for the W0 map-engine demo (VITE_MOCK=1).
// ~25 lines × 200 edges × ~20 shape points ≈ 100k canonical polyline points — the target
// scale the renderer must hold 60 fps at.
import type { EdgeDto, RailGraphDto, SimRunDto, SimTrainDto } from '../api/types';

const LINES = 25;
const EDGES_PER_LINE = 200;
const PT_SPACING = 6;
const STRIDE = 40;
const HORIZON = 48000;
const DWELL_TICKS = 300;

function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a += 0x6d2b79f5;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function lineName(line: number): string {
  return 'Line ' + String.fromCharCode(65 + (line % 26));
}

export function buildFixture(): { graph: RailGraphDto; sim: SimRunDto } {
  const rand = mulberry32(0xc0ffee);
  const nodes: RailGraphDto['nodes'] = [];
  const edges: EdgeDto[] = [];
  const lineForwardEdges: number[][] = [];

  for (let line = 0; line < LINES; line++) {
    const forward: number[] = [];
    let x = (rand() - 0.5) * 16000;
    let z = (rand() - 0.5) * 16000;
    let heading = rand() * Math.PI * 2;
    let curvature = 0;
    let prevNode = nodes.length;
    nodes.push([Math.round(x * 10), 640, Math.round(z * 10), 0, 1]);

    for (let e = 0; e < EDGES_PER_LINE; e++) {
      const length = 60 + rand() * 120;
      const steps = Math.max(2, Math.round(length / PT_SPACING));
      const shape: [number, number][] = [[Math.round(x * 10), Math.round(z * 10)]];
      for (let s = 0; s < steps; s++) {
        curvature += (rand() - 0.5) * 0.004;
        curvature = Math.max(-0.02, Math.min(0.02, curvature * 0.98));
        heading += curvature * PT_SPACING;
        x += Math.cos(heading) * (length / steps);
        z += Math.sin(heading) * (length / steps);
        shape.push([Math.round(x * 10), Math.round(z * 10)]);
      }
      const nextNode = nodes.length;
      nodes.push([Math.round(x * 10), 640, Math.round(z * 10), 0, e === EDGES_PER_LINE - 1 ? 1 : 0]);

      const forwardId = edges.length;
      const stations =
        e % 12 === 6
          ? [{ id: `st-${line}-${e}`, name: `${lineName(line)} ${Math.floor(e / 12) + 1}`, off: length / 2, ap: true }]
          : [];
      edges.push({
        from: prevNode,
        to: nextNode,
        opp: forwardId + 1,
        len: length,
        sig: e % 3 === 0 ? 1 : 0,
        dim: 0,
        shape,
        stations,
      });
      edges.push({
        from: nextNode,
        to: prevNode,
        opp: forwardId,
        len: length,
        // real networks routinely signal one direction only; the reverse twin is never
        // the drawn edge, so keep the mock covering that case
        sig: e % 5 === 2 ? 2 : e % 3 === 0 ? 1 : 0,
        dim: 0,
        shape: [...shape].reverse(),
        stations: stations.map((st) => ({ ...st, off: length - st.off })),
      });
      forward.push(forwardId);
      prevNode = nextNode;
    }
    lineForwardEdges.push(forward);
  }

  const graph: RailGraphDto = {
    id: 'fixture',
    version: 1,
    dimensions: ['minecraft:overworld'],
    nodes,
    edges,
  };
  return { graph, sim: buildSim(graph, lineForwardEdges, rand) };
}

function buildSim(graph: RailGraphDto, lines: number[][], rand: () => number): SimRunDto {
  const trains: SimTrainDto[] = [];
  lines.forEach((forward, line) => {
    const count = 2 + (line % 2);
    for (let k = 0; k < count; k++) {
      const back = [...forward].reverse().map((id) => graph.edges[id].opp);
      trains.push(runTrain(graph, [...forward, ...back], `${lineName(line)} Express ${k + 1}`, k * 6000, rand));
    }
  });
  return { meta: { start: 0, rate: 1, ticks: HORIZON, stride: STRIDE }, trains };
}

/** Simple kinematic walk along a path: accelerate, brake into stations, dwell, continue. */
function runTrain(
  graph: RailGraphDto,
  path: number[],
  name: string,
  startTick: number,
  rand: () => number,
): SimTrainDto {
  const ACC = 0.015;
  const VMAX = 0.9 + rand() * 0.4;

  const entry: number[] = [];
  let total = 0;
  for (const id of path) {
    entry.push(total);
    total += graph.edges[id].len;
  }
  const stops: { dist: number; name: string }[] = [];
  path.forEach((id, i) => {
    for (const st of graph.edges[id].stations ?? []) stops.push({ dist: entry[i] + st.off, name: st.name });
  });
  stops.sort((a, b) => a.dist - b.dist);

  const samples: [number, number, number, number][] = [];
  const visits: [number, number, string][] = [];
  let cursor = 0;
  const push = (tick: number, dist: number, speed: number) => {
    while (cursor < path.length - 1 && dist > entry[cursor] + graph.edges[path[cursor]].len) cursor++;
    samples.push([tick, path[cursor], dist - entry[cursor], speed]);
  };

  let dist = 0;
  let speed = 0;
  let tick = startTick;
  let stopIdx = 0;
  push(tick, dist, speed);
  while (dist < total - 1 && tick < HORIZON) {
    const nextStop = stopIdx < stops.length ? stops[stopIdx].dist : total - 0.5;
    const remaining = nextStop - dist;
    if (remaining <= Math.max(speed, 0.1)) {
      dist = nextStop;
      push(tick, dist, 0);
      if (stopIdx < stops.length) {
        visits.push([tick, tick + DWELL_TICKS, stops[stopIdx].name]);
        tick += DWELL_TICKS;
        push(tick, dist, 0);
        stopIdx++;
        speed = 0;
        continue;
      }
      break;
    }
    speed = Math.min(VMAX, speed + ACC, Math.sqrt(2 * ACC * remaining));
    dist += speed;
    tick++;
    if (tick % STRIDE === 0) push(tick, dist, speed);
  }
  push(Math.min(tick, HORIZON), dist, 0);

  return { n: name, len: 30, ts: VMAX, end: 'PARKED', s: samples, path, v: visits };
}
