// Per-frame layer: trains, notification badges, station labels, HUD. A few hundred primitives — ~1-2 ms.
import type { Camera } from './camera';
import type { LoadedGraph } from './geometry';
import { paint } from '../stores/theme.svelte';

export interface TrainDot {
  x: number;
  z: number;
  speed: number;
  color: string;
  name: string;
  selected?: boolean;
  /** Screen position, filled during draw for hit-testing. */
  px?: number;
  py?: number;
  id?: string;
}

export interface MapBadge {
  x: number;
  z: number;
  color: string;
  id: string;
  px?: number;
  py?: number;
}

export function drawDynamic(
  ctx: CanvasRenderingContext2D,
  graphs: readonly LoadedGraph[],
  cam: Camera,
  w: number,
  h: number,
  dimName: string,
  trains: TrainDot[],
  badges: MapBadge[],
  pulse: number,
  showLabels: boolean,
  hud: string[],
  hudTop = 18,
) {
  if (showLabels && cam.scale >= 1) {
    ctx.font = '11px ui-monospace, monospace';
    ctx.fillStyle = paint.stationLabel;
    const occupied = new Set<number>();
    const viewMinX = cam.toWorldX(-100, w);
    const viewMaxX = cam.toWorldX(w + 100, w);
    const viewMinZ = cam.toWorldZ(-20, h);
    const viewMaxZ = cam.toWorldZ(h + 20, h);
    for (const g of graphs) {
      const dim = g.dims.indexOf(dimName);
      if (dim < 0) continue;
      const box = g.bbox[dim];
      if (!box || box.maxX < viewMinX || box.minX > viewMaxX || box.maxZ < viewMinZ || box.minZ > viewMaxZ)
        continue;
      for (const st of g.stations) {
        if (st.dim !== dim) continue;
        const px = cam.toScreenX(st.x, w);
        const py = cam.toScreenZ(st.z, h);
        if (px < -100 || px > w + 100 || py < -20 || py > h + 20) continue;
        const key = ((px >> 6) & 0xffff) * 65536 + ((py >> 6) & 0xffff);
        if (occupied.has(key)) continue;
        occupied.add(key);
        ctx.fillText(st.name, px + 7, py - 5);
      }
    }
  }

  const nameZoom = cam.scale >= 0.35;
  for (const train of trains) {
    const px = cam.toScreenX(train.x, w);
    const py = cam.toScreenZ(train.z, h);
    train.px = px;
    train.py = py;
    if (px < -20 || px > w + 20 || py < -20 || py > h + 20) continue;
    if (train.selected) {
      ctx.strokeStyle = paint.selectRing;
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(px, py, 7, 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.fillStyle = train.color;
    ctx.beginPath();
    ctx.arc(px, py, 4.5, 0, Math.PI * 2);
    ctx.fill();
    if (nameZoom || train.selected) {
      ctx.fillStyle = paint.trainLabel;
      ctx.font = '10px ui-monospace, monospace';
      ctx.fillText(train.name, px + 9, py + 3);
    }
  }

  for (const badge of badges) {
    const px = cam.toScreenX(badge.x, w);
    const py = cam.toScreenZ(badge.z, h);
    badge.px = px;
    badge.py = py;
    if (px < -20 || px > w + 20 || py < -20 || py > h + 20) continue;
    const size = 6 * pulse;
    diamond(ctx, px, py, size + 2.5, paint.badgeOutline);
    diamond(ctx, px, py, size, badge.color);
  }

  ctx.font = '11px ui-monospace, monospace';
  ctx.fillStyle = paint.hud;
  ctx.textAlign = 'right';
  hud.forEach((line, i) => ctx.fillText(line, w - 10, hudTop + i * 14));
  ctx.textAlign = 'left';
}

export interface RouteOverlay {
  edges: number[];
  color: string;
  dash?: boolean;
}

/** Route overlays (detour taken-vs-direct): full-geometry strokes over the listed edges. */
export function drawRoutes(
  ctx: CanvasRenderingContext2D,
  g: LoadedGraph,
  cam: Camera,
  w: number,
  h: number,
  dim: number,
  routes: RouteOverlay[],
) {
  ctx.lineJoin = 'round';
  ctx.lineCap = 'round';
  ctx.globalAlpha = 0.85;
  for (const route of routes) {
    ctx.strokeStyle = route.color;
    ctx.lineWidth = 3;
    ctx.setLineDash(route.dash ? [7, 5] : []);
    ctx.beginPath();
    for (const edge of route.edges) {
      if (edge < 0 || edge >= g.edgePtCnt.length || g.dim[edge] !== dim) continue;
      const first = g.edgePtOff[edge];
      const count = g.edgePtCnt[edge];
      if (count < 2) continue;
      ctx.moveTo(cam.toScreenX(g.pts[first * 2], w), cam.toScreenZ(g.pts[first * 2 + 1], h));
      for (let i = 1; i < count; i++)
        ctx.lineTo(cam.toScreenX(g.pts[(first + i) * 2], w), cam.toScreenZ(g.pts[(first + i) * 2 + 1], h));
    }
    ctx.stroke();
  }
  ctx.setLineDash([]);
  ctx.globalAlpha = 1;
}

function diamond(ctx: CanvasRenderingContext2D, x: number, y: number, r: number, color: string) {
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(x, y - r);
  ctx.lineTo(x + r, y);
  ctx.lineTo(x, y + r);
  ctx.lineTo(x - r, y);
  ctx.closePath();
  ctx.fill();
}
