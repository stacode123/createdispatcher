<script lang="ts">
  /**
   * Time-distance diagram, actual vs plan — a port of the in-game
   * TimeDistanceDiagramWindow: X = game time (pan/zoom, ~70 px grid ladder
   * 5 m→24 h), Y = the whole corridor, station gridlines + gutter labels,
   * polylines clipped in data space. Actual runs are solid 2.25 px, the plan
   * overlay dashed 1.25 px at 55 % in the same per-train hue, and the clock
   * store draws the "now" line. Dirty-flag rendering — no rAF loop.
   */
  import { onMount } from 'svelte';
  import { corridor } from '../stores/corridor.svelte';
  import { clock } from '../stores/clock.svelte';
  import { liveTrains } from '../stores/liveTrains.svelte';
  import { colorFor } from '../map/palette';
  import { paint, theme } from '../stores/theme.svelte';
  import { fmtTime } from './timeFormat';
  import type { CorridorLineDto, PlanCalibrationDto } from '../api/types';

  const LABEL_WIDTH = 88;
  const AXIS_HEIGHT = 16;
  const GRID_MINUTES = [5, 10, 30, 60, 180, 360, 720, 1440];

  let canvas: HTMLCanvasElement;
  let viewStart = 0;
  let viewSpan = 36_000;
  let initialized = false;
  let dragging = false;
  let dragMoved = 0;
  let lastRev = -1;
  let hover = $state<{ x: number; y: number; lines: string[] } | null>(null);

  interface DrawnLine {
    id: string;
    name: string;
    plan: boolean;
    /** Screen-space segments for hover/click hit-testing. */
    segs: { x1: number; y1: number; x2: number; y2: number }[];
  }
  let drawn: DrawnLine[] = [];

  function plotW(w: number) {
    return w - LABEL_WIDTH - 6;
  }
  function plotH(h: number) {
    return h - AXIS_HEIGHT - 4;
  }
  function toX(tick: number, w: number) {
    return LABEL_WIDTH + ((tick - viewStart) / viewSpan) * plotW(w);
  }
  function toY(pos: number, h: number, length: number) {
    return 2 + plotH(h) - (pos / Math.max(1e-6, length)) * plotH(h);
  }
  function tickAt(px: number, w: number) {
    return viewStart + ((px - LABEL_WIDTH) / plotW(w)) * viewSpan;
  }

  function rate(): number {
    return corridor.plan?.dayTimeRate ?? clock.ui.rate;
  }

  function timeLabel(tick: number): string {
    const r = rate();
    const now = clock.estServerTick();
    if (r <= 0) {
      const seconds = Math.round((tick - now) / 20);
      const sign = seconds < 0 ? '-' : '+';
      const abs = Math.abs(seconds);
      return `${sign}${Math.floor(abs / 60)}:${String(abs % 60).padStart(2, '0')}`;
    }
    // wrap into one day — fmtTime mishandles negative day times (JS modulo)
    const dayTime = clock.estDayTime() + (tick - now) * r;
    return fmtTime(((dayTime % 24000) + 24000) % 24000, 1, 0);
  }

  function gridStep(w: number): number {
    const r = rate();
    const ticksPerMinute = r > 0 ? 1000 / 60 / r : 20 * 60;
    for (const minutes of GRID_MINUTES) {
      const step = Math.max(1, Math.round(minutes * ticksPerMinute));
      if ((step / viewSpan) * plotW(w) >= 70) return step;
    }
    return Math.max(1, Math.round(GRID_MINUTES[GRID_MINUTES.length - 1] * ticksPerMinute));
  }

  function draw() {
    if (!canvas) return;
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    if (w === 0 || h === 0) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
    }
    const ctx = canvas.getContext('2d')!;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = paint.diagramBg;
    ctx.fillRect(0, 0, w, h);
    drawn = [];

    const actual = corridor.actual;
    if (!actual) {
      ctx.fillStyle = paint.diagramAxisText;
      ctx.font = '11px ui-monospace, monospace';
      ctx.fillText(corridor.ui.status === 'error' ? corridor.ui.error : 'pick two stations…', LABEL_WIDTH, 24);
      return;
    }
    const length = actual.length;
    if (!initialized) {
      initialized = true;
      const now = clock.estServerTick();
      viewStart = now - viewSpan * 0.72;
    }

    // time grid
    ctx.font = '10px ui-monospace, monospace';
    const step = gridStep(w);
    const firstLine = Math.ceil(viewStart / step) * step;
    let lastLabelX = -Infinity;
    for (let tick = firstLine; tick <= viewStart + viewSpan; tick += step) {
      const x = Math.round(toX(tick, w));
      if (x < LABEL_WIDTH || x > w - 4) continue;
      ctx.fillStyle = paint.diagramGrid;
      ctx.fillRect(x, 2, 1, plotH(h));
      const label = timeLabel(tick);
      const lw = ctx.measureText(label).width;
      if (x - lw / 2 > lastLabelX + 8) {
        lastLabelX = x + lw / 2;
        ctx.fillStyle = paint.diagramAxisText;
        ctx.fillText(label, x - lw / 2, h - 4);
      }
    }

    // station gridlines + gutter labels (positions ascend; label rows must not overlap)
    ctx.font = '11px ui-monospace, monospace';
    let lastLabelY = Infinity;
    for (const [name, pos] of actual.stations) {
      const y = Math.round(toY(pos, h, length));
      ctx.fillStyle = paint.diagramStationLine;
      ctx.fillRect(LABEL_WIDTH, y, plotW(w), 1);
      if (lastLabelY - y < 11) continue;
      lastLabelY = y;
      ctx.fillStyle = paint.diagramStationText;
      let label = name;
      while (label.length > 1 && ctx.measureText(label).width > LABEL_WIDTH - 8) label = label.slice(0, -1);
      ctx.fillText(label, LABEL_WIDTH - 6 - ctx.measureText(label).width, y + 4);
    }

    // clip to the plot for the data
    ctx.save();
    ctx.beginPath();
    ctx.rect(LABEL_WIDTH, 0, plotW(w), plotH(h) + 4);
    ctx.clip();
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';

    for (const line of actual.lines)
      strokeLine(ctx, line.id, line.name, line.segs, 0, false, w, h, length);
    const plan = corridor.plan;
    if (plan && !plan.pending) {
      // Measured drift calibration: stretch plan time by the observed rate
      // and shade a 1-sigma band that widens with prediction horizon. Each
      // train uses its own estimate when it has one — quiet-route trains get
      // tight bands — falling back to the graph-wide estimate otherwise.
      const graphCal = plan.cal && plan.cal.n >= 8 ? plan.cal : null;
      const calOf = (line: CorridorLineDto) => (line.cal && line.cal.n >= 8 ? line.cal : graphCal);
      // the selected train's band pops; everyone else's recedes while one is picked
      const selected = liveTrains.ui.selected;
      for (const line of plan.lines) {
        const cal = calOf(line);
        if (!cal || line.id === selected) continue;
        bandLine(ctx, line.id, line.segs, plan.baseTick, cal, w, h, length, selected ? 0.05 : 0.09, false);
      }
      const sel = selected ? plan.lines.find((l) => l.id === selected) : undefined;
      const selCal = sel ? calOf(sel) : null;
      if (sel && selCal) bandLine(ctx, sel.id, sel.segs, plan.baseTick, selCal, w, h, length, 0.26, true);
      for (const line of plan.lines)
        strokeLine(ctx, line.id, line.name, line.segs, plan.baseTick, true, w, h, length, calOf(line));
    }

    // now line
    const nowX = toX(clock.estServerTick(), w);
    if (nowX >= LABEL_WIDTH && nowX <= w - 4) {
      ctx.globalAlpha = 0.8;
      ctx.strokeStyle = paint.diagramNow;
      ctx.lineWidth = 1;
      ctx.setLineDash([]);
      ctx.beginPath();
      ctx.moveTo(nowX, 2);
      ctx.lineTo(nowX, plotH(h) + 2);
      ctx.stroke();
      ctx.globalAlpha = 1;
      ctx.fillStyle = paint.diagramNow;
      ctx.font = '10px ui-monospace, monospace';
      ctx.fillText('now', Math.min(nowX + 4, w - 30), 12);
    }
    ctx.restore();
  }

  /** Calibrated plan tick: sim-relative time stretched by the drift rate. */
  function calTick(rel: number, baseTick: number, cal: PlanCalibrationDto | null): number {
    return baseTick + (cal ? rel * (1 + cal.stretch) : rel);
  }

  /** 1-sigma uncertainty band around a plan line, widening with horizon. */
  function bandLine(
    ctx: CanvasRenderingContext2D,
    id: string,
    segs: [number, number][][],
    baseTick: number,
    cal: PlanCalibrationDto,
    w: number,
    h: number,
    length: number,
    alpha: number,
    outline: boolean,
  ) {
    const viewEnd = viewStart + viewSpan;
    ctx.fillStyle = colorFor(id);
    ctx.globalAlpha = alpha;
    for (const seg of segs) {
      if (seg.length < 2) continue;
      const t0 = calTick(seg[0][0], baseTick, cal);
      const tn = calTick(seg[seg.length - 1][0], baseTick, cal);
      if (tn < viewStart || t0 > viewEnd) continue;
      ctx.beginPath();
      for (let i = 0; i < seg.length; i++) {
        const delta = (cal.sigmaPerMin * seg[i][0]) / 1200;
        const x = toX(calTick(seg[i][0], baseTick, cal) - delta, w);
        const y = toY(seg[i][1], h, length);
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      for (let i = seg.length - 1; i >= 0; i--) {
        const delta = (cal.sigmaPerMin * seg[i][0]) / 1200;
        const x = toX(calTick(seg[i][0], baseTick, cal) + delta, w);
        const y = toY(seg[i][1], h, length);
        ctx.lineTo(x, y);
      }
      ctx.closePath();
      ctx.fill();
      if (outline) {
        ctx.globalAlpha = 0.45;
        ctx.strokeStyle = colorFor(id);
        ctx.lineWidth = 1;
        ctx.setLineDash([]);
        ctx.stroke();
        ctx.globalAlpha = alpha;
      }
    }
    ctx.globalAlpha = 1;
  }

  function strokeLine(
    ctx: CanvasRenderingContext2D,
    id: string,
    name: string,
    segs: [number, number][][],
    baseTick: number,
    plan: boolean,
    w: number,
    h: number,
    length: number,
    cal: PlanCalibrationDto | null = null,
  ) {
    ctx.strokeStyle = colorFor(id);
    ctx.lineWidth = plan ? 1.25 : 2.25;
    ctx.setLineDash(plan ? [5, 4] : []);
    ctx.globalAlpha = plan ? 0.55 : 1;
    const record: DrawnLine = { id, name, plan, segs: [] };
    const viewEnd = viewStart + viewSpan;
    ctx.beginPath();
    for (const seg of segs) {
      for (let i = 1; i < seg.length; i++) {
        let t1 = calTick(seg[i - 1][0], baseTick, plan ? cal : null);
        let p1 = seg[i - 1][1];
        let t2 = calTick(seg[i][0], baseTick, plan ? cal : null);
        let p2 = seg[i][1];
        if (t2 < viewStart || t1 > viewEnd) continue;
        if (t1 < viewStart && t2 > t1) {
          p1 += ((p2 - p1) * (viewStart - t1)) / (t2 - t1);
          t1 = viewStart;
        }
        if (t2 > viewEnd && t2 > t1) {
          p2 -= ((p2 - p1) * (t2 - viewEnd)) / (t2 - t1);
          t2 = viewEnd;
        }
        const x1 = toX(t1, w);
        const y1 = toY(p1, h, length);
        const x2 = toX(t2, w);
        const y2 = toY(p2, h, length);
        ctx.moveTo(x1, y1);
        ctx.lineTo(x2, y2);
        record.segs.push({ x1, y1, x2, y2 });
      }
    }
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.globalAlpha = 1;
    if (record.segs.length) drawn.push(record);
  }

  function onWheel(e: WheelEvent) {
    e.preventDefault();
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const px = e.clientX - rect.left;
    const anchor = tickAt(px, canvas.clientWidth);
    const factor = Math.pow(1.25, Math.sign(e.deltaY));
    viewSpan = Math.max(2000, Math.min(600_000, viewSpan * factor));
    viewStart = anchor - (anchor - viewStart) * factor;
    clampView();
    draw();
  }

  function clampView() {
    const now = clock.estServerTick();
    viewStart = Math.max(now - 400_000, Math.min(now + 60_000, viewStart));
  }

  function pointerDown(e: PointerEvent) {
    dragging = true;
    dragMoved = 0;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  }

  function pointerMove(e: PointerEvent) {
    if (dragging) {
      dragMoved += Math.abs(e.movementX) + Math.abs(e.movementY);
      viewStart -= (e.movementX / plotW(canvas.clientWidth)) * viewSpan;
      clampView();
      hover = null;
      draw();
      return;
    }
    updateHover(e.offsetX, e.offsetY);
  }

  function pointerUp(e: PointerEvent) {
    const wasClick = dragging && dragMoved <= 4;
    dragging = false;
    if (!wasClick) return;
    // a click on a train's line selects it on the map (same as clicking its dot)
    const line = nearestLine(e.offsetX, e.offsetY);
    if (line) liveTrains.select(line.id);
  }

  function nearestLine(px: number, py: number): DrawnLine | null {
    let best: DrawnLine | null = null;
    let bestDist = 25; // 5px
    for (const line of drawn)
      for (const seg of line.segs) {
        const d = segDistSq(px, py, seg.x1, seg.y1, seg.x2, seg.y2);
        if (d < bestDist) {
          bestDist = d;
          best = line;
        }
      }
    return best;
  }

  function updateHover(px: number, py: number) {
    const best = nearestLine(px, py);
    hover = best
      ? {
          x: px,
          y: py,
          lines: [best.name + (best.plan ? ' · plan' : ''), timeLabel(tickAt(px, canvas.clientWidth))],
        }
      : null;
  }

  function segDistSq(px: number, py: number, ax: number, ay: number, bx: number, by: number) {
    const dx = bx - ax;
    const dy = by - ay;
    const lenSq = dx * dx + dy * dy;
    const t = lenSq === 0 ? 0 : Math.min(1, Math.max(0, ((px - ax) * dx + (py - ay) * dy) / lenSq));
    const ex = px - (ax + t * dx);
    const ey = py - (ay + t * dy);
    return ex * ex + ey * ey;
  }

  onMount(() => {
    const observer = new ResizeObserver(() => draw());
    observer.observe(canvas);
    canvas.addEventListener('wheel', onWheel, { passive: false });
    // the now-line and incoming polls keep the view alive without a rAF loop
    const timer = setInterval(() => {
      if (corridor.ui.rev !== lastRev) lastRev = corridor.ui.rev;
      draw();
    }, 1000);
    draw();
    return () => {
      observer.disconnect();
      canvas.removeEventListener('wheel', onWheel);
      clearInterval(timer);
    };
  });

  $effect(() => {
    void corridor.ui.rev;
    void theme.mode;
    void corridor.ui.status;
    void liveTrains.ui.selected;
    draw();
  });
</script>

<div class="diagram-wrap">
  <canvas
    bind:this={canvas}
    onpointerdown={pointerDown}
    onpointermove={pointerMove}
    onpointerup={pointerUp}
    onpointercancel={pointerUp}
    onpointerleave={() => (hover = null)}
  ></canvas>
  {#if hover}
    <div class="tooltip mono" style="left: {hover.x + 14}px; top: {hover.y - 8}px">
      {#each hover.lines as line (line)}<div>{line}</div>{/each}
    </div>
  {/if}
</div>

<style>
  .diagram-wrap {
    position: relative;
    width: 100%;
    height: 100%;
    min-height: 0;
    overflow: hidden;
  }
  canvas {
    width: 100%;
    height: 100%;
    display: block;
    touch-action: none;
    cursor: ew-resize;
  }
  .diagram-wrap:has(.tooltip) canvas {
    cursor: pointer;
  }
  .tooltip {
    position: absolute;
    pointer-events: none;
    background: var(--panel-raised);
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 4px 8px;
    font-size: 11px;
    white-space: nowrap;
    z-index: 5;
  }
</style>
