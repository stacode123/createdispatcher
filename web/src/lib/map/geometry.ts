/**
 * Typed-array graph representation built by the worker from a RailGraphDto.
 * Everything here is per-graph-version; only UUIDs may leave this structure.
 */
export interface LoadedGraph {
  id: string;
  version: number;
  dims: string[];
  /** x,z pairs in blocks; all edge shapes concatenated. */
  pts: Float32Array;
  /** First point index (into pts/2) per edge. */
  edgePtOff: Uint32Array;
  edgePtCnt: Uint32Array;
  /** Edge-local cumulative arc length per point, blocks. */
  arc: Float32Array;
  from: Int32Array;
  to: Int32Array;
  opp: Int32Array;
  len: Float32Array;
  sig: Uint8Array;
  dim: Uint8Array;
  /** Speed cap km/h, 0 = uncapped. */
  cap: Float32Array;
  /** 1 when this edge is the drawn twin (opp < 0 || id < opp). */
  canonical: Uint8Array;
  /** CSR node -> outgoing edge ids. */
  adjOff: Uint32Array;
  adjList: Uint32Array;
  nodeCount: number;
  /** LOD tiers 1..2: per-edge subsets of point indices (tier 0 = full pts, tier 3 = chords). */
  tiers: { pts: Uint32Array; off: Uint32Array; cnt: Uint32Array }[];
  /** cellKey -> runs of (edgeId, ptStart, ptCount) triples. */
  grid: Map<number, Uint32Array>;
  gridCell: number;
  stations: StationView[];
  bbox: { minX: number; minZ: number; maxX: number; maxZ: number }[];
}

export interface StationView {
  id: string;
  name: string;
  edge: number;
  off: number;
  x: number;
  z: number;
  dim: number;
}

export function cellKey(cx: number, cz: number): number {
  return (cx + 32768) * 65536 + (cz + 32768);
}

/** (edge, offset in blocks) -> world x,z via the arc-length table (real curve geometry). */
export function posAlongEdge(g: LoadedGraph, edge: number, offBlocks: number, out: [number, number]) {
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

/** Iterate grid runs intersecting the world-rect (expanded by one cell). */
export function forVisibleRuns(
  g: LoadedGraph,
  minX: number,
  minZ: number,
  maxX: number,
  maxZ: number,
  cb: (edge: number, ptStart: number, ptCount: number) => void,
) {
  const cell = g.gridCell;
  const c0x = Math.floor(minX / cell) - 1;
  const c1x = Math.floor(maxX / cell) + 1;
  const c0z = Math.floor(minZ / cell) - 1;
  const c1z = Math.floor(maxZ / cell) + 1;
  for (let cx = c0x; cx <= c1x; cx++) {
    for (let cz = c0z; cz <= c1z; cz++) {
      const runs = g.grid.get(cellKey(cx, cz));
      if (!runs) continue;
      for (let i = 0; i < runs.length; i += 3) cb(runs[i], runs[i + 1], runs[i + 2]);
    }
  }
}
