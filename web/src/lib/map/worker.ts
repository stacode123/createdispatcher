/// <reference lib="webworker" />
// Graph build pipeline: RailGraphDto JSON -> transferable typed arrays + LOD tiers + spatial grid.
// Runs off the main thread so a 100k-point parse never blocks rendering.
import type { RailGraphDto } from '../api/types';
import { cellKey, type LoadedGraph, type StationView } from './geometry';
import { douglasPeucker } from './lod';

const GRID_CELL = 256;
const LOD_EPSILONS = [0.75, 3.0];

// Guard: this module is also imported on the main thread (mock continuity check) — only
// register the message handler when actually running inside a worker.
if (typeof document === 'undefined') {
  self.onmessage = (event: MessageEvent<RailGraphDto>) => {
    handleLoad(event.data);
  };
}

function handleLoad(dto: RailGraphDto) {
  const built = build(dto);
  const transfers: Transferable[] = [
    built.pts.buffer,
    built.edgePtOff.buffer,
    built.edgePtCnt.buffer,
    built.arc.buffer,
    built.from.buffer,
    built.to.buffer,
    built.opp.buffer,
    built.len.buffer,
    built.sig.buffer,
    built.dim.buffer,
    built.cap.buffer,
    built.canonical.buffer,
    built.adjOff.buffer,
    built.adjList.buffer,
    ...built.tiers.flatMap((tier) => [tier.pts.buffer, tier.off.buffer, tier.cnt.buffer]),
  ];
  (self as unknown as Worker).postMessage(built, transfers);
};

export function build(dto: RailGraphDto): LoadedGraph {
  const edgeCount = dto.edges.length;
  let totalPts = 0;
  for (const edge of dto.edges) totalPts += edge.shape.length;

  const pts = new Float32Array(totalPts * 2);
  const edgePtOff = new Uint32Array(edgeCount);
  const edgePtCnt = new Uint32Array(edgeCount);
  const arc = new Float32Array(totalPts);
  const from = new Int32Array(edgeCount);
  const to = new Int32Array(edgeCount);
  const opp = new Int32Array(edgeCount);
  const len = new Float32Array(edgeCount);
  const sig = new Uint8Array(edgeCount);
  const dim = new Uint8Array(edgeCount);
  const cap = new Float32Array(edgeCount);
  const canonical = new Uint8Array(edgeCount);

  let cursor = 0;
  for (let e = 0; e < edgeCount; e++) {
    const edge = dto.edges[e];
    edgePtOff[e] = cursor;
    edgePtCnt[e] = edge.shape.length;
    let acc = 0;
    for (let i = 0; i < edge.shape.length; i++) {
      const x = edge.shape[i][0] / 10;
      const z = edge.shape[i][1] / 10;
      pts[(cursor + i) * 2] = x;
      pts[(cursor + i) * 2 + 1] = z;
      if (i > 0) {
        const dx = x - pts[(cursor + i - 1) * 2];
        const dz = z - pts[(cursor + i - 1) * 2 + 1];
        acc += Math.hypot(dx, dz);
      }
      arc[cursor + i] = acc;
    }
    cursor += edge.shape.length;
    from[e] = edge.from;
    to[e] = edge.to;
    opp[e] = edge.opp;
    len[e] = edge.len;
    sig[e] = edge.sig;
    dim[e] = edge.dim;
    cap[e] = edge.cap ?? 0;
    // inter-dimensional edges are never drawn or gridded
    canonical[e] = !edge.xd && (edge.opp < 0 || e < edge.opp) ? 1 : 0;
  }

  // Adjacency CSR: node -> outgoing edge ids.
  const nodeCount = dto.nodes.length;
  const adjOff = new Uint32Array(nodeCount + 1);
  for (let e = 0; e < edgeCount; e++) adjOff[from[e] + 1]++;
  for (let n = 0; n < nodeCount; n++) adjOff[n + 1] += adjOff[n];
  const adjList = new Uint32Array(edgeCount);
  const fill = adjOff.slice(0, nodeCount);
  for (let e = 0; e < edgeCount; e++) adjList[fill[from[e]]++] = e;

  // LOD tiers over canonical edges (non-canonical are never drawn).
  const tiers = LOD_EPSILONS.map((epsilon) => {
    const kept: number[] = [];
    const off = new Uint32Array(edgeCount);
    const cnt = new Uint32Array(edgeCount);
    for (let e = 0; e < edgeCount; e++) {
      if (!canonical[e]) continue;
      off[e] = kept.length;
      const indices = douglasPeucker(pts, edgePtOff[e], edgePtCnt[e], epsilon);
      for (const local of indices) kept.push(edgePtOff[e] + local);
      cnt[e] = indices.length;
    }
    return { pts: new Uint32Array(kept), off, cnt };
  });

  // Spatial grid over canonical edges: runs of consecutive points per cell,
  // overlapping one point on cell change so boundary segments are never lost.
  const gridLists = new Map<number, number[]>();
  for (let e = 0; e < edgeCount; e++) {
    if (!canonical[e]) continue;
    const first = edgePtOff[e];
    const count = edgePtCnt[e];
    let runStart = 0;
    let runCell = cellKey(Math.floor(pts[first * 2] / GRID_CELL), Math.floor(pts[first * 2 + 1] / GRID_CELL));
    for (let i = 1; i < count; i++) {
      const cell = cellKey(
        Math.floor(pts[(first + i) * 2] / GRID_CELL),
        Math.floor(pts[(first + i) * 2 + 1] / GRID_CELL),
      );
      if (cell !== runCell) {
        pushRun(gridLists, runCell, e, first + runStart, i - runStart + 1);
        runStart = i;
        runCell = cell;
      }
    }
    pushRun(gridLists, runCell, e, first + runStart, count - runStart);
  }
  const grid = new Map<number, Uint32Array>();
  for (const [key, list] of gridLists) grid.set(key, new Uint32Array(list));

  // Stations (deduped by uuid, positioned via the arc table) + per-dim bbox.
  const stations: StationView[] = [];
  const seenStations = new Set<string>();
  const bbox = dto.dimensions.map(() => ({ minX: Infinity, minZ: Infinity, maxX: -Infinity, maxZ: -Infinity }));
  const scratch: [number, number] = [0, 0];
  const graphView = {
    pts,
    edgePtOff,
    edgePtCnt,
    arc,
    len,
  } as unknown as LoadedGraph;
  for (let e = 0; e < edgeCount; e++) {
    if (!canonical[e]) continue;
    const box = bbox[dim[e]];
    for (let i = 0; i < edgePtCnt[e]; i++) {
      const x = pts[(edgePtOff[e] + i) * 2];
      const z = pts[(edgePtOff[e] + i) * 2 + 1];
      if (x < box.minX) box.minX = x;
      if (x > box.maxX) box.maxX = x;
      if (z < box.minZ) box.minZ = z;
      if (z > box.maxZ) box.maxZ = z;
    }
    for (const st of dto.edges[e].stations ?? []) {
      if (seenStations.has(st.id)) continue;
      seenStations.add(st.id);
      posAlongEdgeLocal(graphView, e, st.off, scratch);
      stations.push({ id: st.id, name: st.name, edge: e, off: st.off, x: scratch[0], z: scratch[1], dim: dim[e] });
    }
  }

  return {
    id: dto.id,
    version: dto.version,
    dims: dto.dimensions,
    pts,
    edgePtOff,
    edgePtCnt,
    arc,
    from,
    to,
    opp,
    len,
    sig,
    dim,
    cap,
    canonical,
    adjOff,
    adjList,
    nodeCount,
    tiers,
    grid,
    gridCell: GRID_CELL,
    stations,
    bbox,
  };
}

function pushRun(lists: Map<number, number[]>, cell: number, edge: number, ptStart: number, ptCount: number) {
  if (ptCount < 2) return;
  let list = lists.get(cell);
  if (!list) lists.set(cell, (list = []));
  list.push(edge, ptStart, ptCount);
}

// Local copy of geometry.posAlongEdge working on the partial view (avoids circular structure).
function posAlongEdgeLocal(g: LoadedGraph, edge: number, offBlocks: number, out: [number, number]) {
  const first = g.edgePtOff[edge];
  const count = g.edgePtCnt[edge];
  const last = first + count - 1;
  const totalArc = g.arc[last];
  if (totalArc <= 0 || count < 2) {
    out[0] = g.pts[first * 2];
    out[1] = g.pts[first * 2 + 1];
    return;
  }
  const target = Math.min(1, Math.max(0, offBlocks / g.len[edge])) * totalArc;
  let lo = first;
  let hi = last;
  while (lo + 1 < hi) {
    const mid = (lo + hi) >> 1;
    if (g.arc[mid] < target) lo = mid;
    else hi = mid;
  }
  const span = g.arc[hi] - g.arc[lo];
  const f = span > 0 ? (target - g.arc[lo]) / span : 0;
  out[0] = g.pts[lo * 2] + (g.pts[hi * 2] - g.pts[lo * 2]) * f;
  out[1] = g.pts[lo * 2 + 1] + (g.pts[hi * 2 + 1] - g.pts[lo * 2 + 1]) * f;
}
