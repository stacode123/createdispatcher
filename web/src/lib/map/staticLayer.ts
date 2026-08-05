// Offscreen raster of the static world (track, stations, signals), 2x-viewport margin.
// Re-rasterized only on zoom settle / pan escape / graph or dimension change; blitted per frame.
// A naive per-frame stroke of 100k points costs 10-30 ms — the bitmap blit costs ~0.3 ms.
//
// Scale reality: big servers carry THOUSANDS of fragment graphs. Every per-graph loop here
// first rejects by bbox-vs-view, and fragments smaller than ~2.5 px collapse into a single
// dot — the grid scan only ever runs for graphs that meaningfully intersect the raster.
import { forVisibleRuns, type LoadedGraph } from './geometry';
import type { Camera } from './camera';
import { paint } from '../stores/theme.svelte';

export class StaticLayer {
  private canvas = document.createElement('canvas');
  private ctx = this.canvas.getContext('2d')!;
  private rx = 0;
  private rz = 0;
  private rscale = 0;
  private rw = 0;
  private rh = 0;
  private rkey = '';
  private rgen = -1;

  /** Re-raster when needed; a scale-only mismatch waits for the zoom to settle (scaled blit meanwhile). */
  maybeRaster(
    graphs: readonly LoadedGraph[],
    cam: Camera,
    w: number,
    h: number,
    dimName: string,
    gen: number,
    settled: boolean,
    layers: { stations: boolean; signals: boolean },
  ) {
    // paint.rev in the key: a theme switch has to re-raster, nothing else would notice
    const key = dimName + '|' + (layers.stations ? 1 : 0) + (layers.signals ? 1 : 0) + '|' + paint.rev;
    const structural =
      this.rgen !== gen || this.rkey !== key || this.rw !== w || this.rh !== h || this.rscale === 0;
    const panEscaped =
      !structural &&
      (Math.abs(cam.x - this.rx) * this.rscale > w / 2 || Math.abs(cam.z - this.rz) * this.rscale > h / 2);
    const zoomChanged = !structural && this.rscale !== cam.scale;
    if (structural || panEscaped || (zoomChanged && settled))
      this.raster(graphs, cam, w, h, dimName, key, gen, layers);
  }

  private tierFor(scale: number): number {
    return scale >= 1.5 ? 0 : scale >= 0.4 ? 1 : scale >= 0.1 ? 2 : 3;
  }

  private raster(graphs: readonly LoadedGraph[], cam: Camera, w: number, h: number, dimName: string,
                 key: string, gen: number, layers: { stations: boolean; signals: boolean }) {
    this.canvas.width = w * 2;
    this.canvas.height = h * 2;
    const ctx = this.ctx;
    ctx.clearRect(0, 0, w * 2, h * 2);

    const minX = cam.x - w / cam.scale;
    const maxX = cam.x + w / cam.scale;
    const minZ = cam.z - h / cam.scale;
    const maxZ = cam.z + h / cam.scale;
    const sx = (wx: number) => (wx - cam.x) * cam.scale + w;
    const sz = (wz: number) => (wz - cam.z) * cam.scale + h;
    const tier = this.tierFor(cam.scale);

    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    ctx.lineWidth = Math.min(2.5, 1 + cam.scale * 0.15);

    for (const g of graphs) {
      const dim = g.dims.indexOf(dimName);
      if (dim < 0) continue;
      const box = g.bbox[dim];
      if (!box || box.minX > box.maxX) continue;
      if (box.maxX < minX || box.minX > maxX || box.maxZ < minZ || box.minZ > maxZ) continue;

      const spanPx = Math.max(box.maxX - box.minX, box.maxZ - box.minZ) * cam.scale;
      if (spanPx < 2.5) {
        // sub-pixel fragment — one dot, no grid scan
        ctx.fillStyle = paint.track;
        ctx.fillRect(sx((box.minX + box.maxX) / 2) - 1, sz((box.minZ + box.maxZ) / 2) - 1, 2, 2);
        continue;
      }

      const visible = new Set<number>();
      forVisibleRuns(g, minX, minZ, maxX, maxZ, (edge) => {
        if (g.canonical[edge] && g.dim[edge] === dim) visible.add(edge);
      });

      ctx.strokeStyle = paint.track;
      ctx.beginPath();
      for (const edge of visible) this.trace(ctx, g, edge, tier, sx, sz);
      ctx.stroke();

      if (layers.stations) {
        ctx.fillStyle = paint.station;
        for (const st of g.stations) {
          if (st.dim !== dim || st.x < minX || st.x > maxX || st.z < minZ || st.z > maxZ) continue;
          ctx.fillRect(sx(st.x) - 3, sz(st.z) - 3, 6, 6);
        }
      }

      if (layers.signals && cam.scale >= 0.4) {
        // `sig` is the entry signal of a DIRECTED edge, but only the canonical twin is
        // ever drawn — so a signal governing the reverse direction has to be picked up
        // from g.opp and drawn at the far end of this polyline, or it goes missing.
        for (const edge of visible) {
          const first = g.edgePtOff[edge];
          const last = first + g.edgePtCnt[edge] - 1;
          if (g.sig[edge]) this.signalDot(ctx, g, first, Math.min(last, first + 1), g.sig[edge], sx, sz);
          const twin = g.opp[edge];
          if (twin >= 0 && g.sig[twin])
            this.signalDot(ctx, g, last, Math.max(first, last - 1), g.sig[twin], sx, sz);
        }
      }
    }

    this.rx = cam.x;
    this.rz = cam.z;
    this.rscale = cam.scale;
    this.rw = w;
    this.rh = h;
    this.rkey = key;
    this.rgen = gen;
  }

  /**
   * Dot for the signal at point `at`, nudged to the side of the track the direction of
   * travel it governs looks down (`at` -> `ahead`). The two signals of a both-ways
   * boundary share a node, so without the nudge one would sit exactly on top of the other.
   */
  private signalDot(
    ctx: CanvasRenderingContext2D,
    g: LoadedGraph,
    at: number,
    ahead: number,
    kind: number,
    sx: (x: number) => number,
    sz: (z: number) => number,
  ) {
    const px = sx(g.pts[at * 2]);
    const py = sz(g.pts[at * 2 + 1]);
    const dx = sx(g.pts[ahead * 2]) - px;
    const dy = sz(g.pts[ahead * 2 + 1]) - py;
    const d = Math.hypot(dx, dy);
    const ox = d > 0 ? (-dy / d) * 2.5 : 0;
    const oy = d > 0 ? (dx / d) * 2.5 : 0;
    ctx.fillStyle = kind === 1 ? paint.sigEntry : paint.sigChain;
    ctx.fillRect(px + ox - 1.5, py + oy - 1.5, 3, 3);
  }

  private trace(
    ctx: CanvasRenderingContext2D,
    g: LoadedGraph,
    edge: number,
    tier: number,
    sx: (x: number) => number,
    sz: (z: number) => number,
  ) {
    const first = g.edgePtOff[edge];
    const count = g.edgePtCnt[edge];
    if (tier === 3 || count < 2) {
      const last = first + count - 1;
      ctx.moveTo(sx(g.pts[first * 2]), sz(g.pts[first * 2 + 1]));
      ctx.lineTo(sx(g.pts[last * 2]), sz(g.pts[last * 2 + 1]));
      return;
    }
    if (tier === 0) {
      ctx.moveTo(sx(g.pts[first * 2]), sz(g.pts[first * 2 + 1]));
      for (let i = 1; i < count; i++) ctx.lineTo(sx(g.pts[(first + i) * 2]), sz(g.pts[(first + i) * 2 + 1]));
      return;
    }
    const t = g.tiers[tier - 1];
    const n = t.cnt[edge];
    if (n < 2) {
      const last = first + count - 1;
      ctx.moveTo(sx(g.pts[first * 2]), sz(g.pts[first * 2 + 1]));
      ctx.lineTo(sx(g.pts[last * 2]), sz(g.pts[last * 2 + 1]));
      return;
    }
    const off = t.off[edge];
    ctx.moveTo(sx(g.pts[t.pts[off] * 2]), sz(g.pts[t.pts[off] * 2 + 1]));
    for (let i = 1; i < n; i++) {
      const p = t.pts[off + i] * 2;
      ctx.lineTo(sx(g.pts[p]), sz(g.pts[p + 1]));
    }
  }

  /** Blit the raster under the current camera (scaled while a zoom is in flight). */
  blit(ctx: CanvasRenderingContext2D, cam: Camera, w: number, h: number) {
    if (this.rscale === 0) return;
    const s = cam.scale / this.rscale;
    const worldLeft = this.rx - this.rw / this.rscale;
    const worldTop = this.rz - this.rh / this.rscale;
    const dx = (worldLeft - cam.x) * cam.scale + w / 2;
    const dy = (worldTop - cam.z) * cam.scale + h / 2;
    ctx.drawImage(this.canvas, dx, dy, this.rw * 2 * s, this.rh * 2 * s);
  }
}
