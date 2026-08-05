<script lang="ts">
  /**
   * Time-distance diagram of a FINISHED planner sim (W5): X = sim time
   * (0 → ticksSimulated, pan/zoom), Y = the picked A→B corridor. Same grid
   * ladder and hit-testing as the live DiagramCanvas, minus actual/plan
   * duality — every line is a simulated run. The playback transport is the
   * cursor: a vertical line tracks playback.t, and clicking anywhere seeks.
   */
  import { onMount } from 'svelte';
  import { sims } from './sims.svelte';
  import { playback } from '../stores/playback.svelte';
  import { colorFor } from '../map/palette';
  import { paint, theme } from '../stores/theme.svelte';
  import { fmtTime } from '../diagram/timeFormat';

  const LABEL_WIDTH = 88;
  const AXIS_HEIGHT = 16;
  const GRID_MINUTES = [5, 10, 30, 60, 180, 360, 720, 1440];

  let canvas: HTMLCanvasElement;
  let viewStart = 0;
  let viewSpan = 48_000;
  let fittedFor = '';
  let dragging = false;
  let dragMoved = 0;
  let hover = $state<{ x: number; y: number; lines: string[] } | null>(null);

  interface DrawnLine {
    id: string;
    name: string;
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

  function gridStep(w: number): number {
    const rate = sims.diagram?.rate ?? 1;
    const ticksPerMinute = rate > 0 ? 1000 / 60 / rate : 20 * 60;
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

    const diagram = sims.diagram;
    if (!diagram) {
      ctx.fillStyle = paint.diagramAxisText;
      ctx.font = '11px ui-monospace, monospace';
      ctx.fillText(
        sims.ui.diagramStatus === 'error' ? sims.ui.diagramError
          : sims.ui.diagramStatus === 'loading' ? 'building corridor…'
          : 'pick two stations…',
        LABEL_WIDTH, 24,
      );
      return;
    }
    if (fittedFor !== diagram.from + '|' + diagram.to + '|' + diagram.simId) {
      fittedFor = diagram.from + '|' + diagram.to + '|' + diagram.simId;
      viewStart = 0;
      viewSpan = Math.max(2000, diagram.ticks);
    }
    const length = diagram.length;

    // time grid — sim-relative ticks labeled as in-game wall clock
    ctx.font = '10px ui-monospace, monospace';
    const step = gridStep(w);
    const firstLine = Math.ceil(viewStart / step) * step;
    let lastLabelX = -Infinity;
    for (let tick = firstLine; tick <= viewStart + viewSpan; tick += step) {
      const x = Math.round(toX(tick, w));
      if (x < LABEL_WIDTH || x > w - 4) continue;
      ctx.fillStyle = paint.diagramGrid;
      ctx.fillRect(x, 2, 1, plotH(h));
      const label = fmtTime(diagram.start, diagram.rate, tick);
      const lw = ctx.measureText(label).width;
      if (x - lw / 2 > lastLabelX + 8) {
        lastLabelX = x + lw / 2;
        ctx.fillStyle = paint.diagramAxisText;
        ctx.fillText(label, x - lw / 2, h - 4);
      }
    }

    // station gridlines + gutter labels
    ctx.font = '11px ui-monospace, monospace';
    let lastLabelY = Infinity;
    for (const [name, pos] of diagram.stations) {
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

    ctx.save();
    ctx.beginPath();
    ctx.rect(LABEL_WIDTH, 0, plotW(w), plotH(h) + 4);
    ctx.clip();
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';

    const viewEnd = viewStart + viewSpan;
    for (const line of diagram.lines) {
      ctx.strokeStyle = colorFor(line.id);
      ctx.lineWidth = 1.75;
      const record: DrawnLine = { id: line.id, name: line.name, segs: [] };
      ctx.beginPath();
      for (const seg of line.segs) {
        for (let i = 1; i < seg.length; i++) {
          let t1 = seg[i - 1][0];
          let p1 = seg[i - 1][1];
          let t2 = seg[i][0];
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
      if (record.segs.length) drawn.push(record);
    }

    // conflict markers inside the view (time position only — cheap orientation aid)
    for (const conflict of sims.ui.conflicts) {
      if (conflict.end < viewStart || conflict.start > viewEnd) continue;
      const x = toX(conflict.start, w);
      ctx.fillStyle = paint.diagramConflict;
      ctx.globalAlpha = 0.5;
      ctx.fillRect(x, 2, Math.max(1, toX(conflict.end, w) - x), 3);
      ctx.globalAlpha = 1;
    }

    // playback cursor
    const cursorX = toX(playback.ui.t, w);
    if (cursorX >= LABEL_WIDTH && cursorX <= w - 4) {
      ctx.globalAlpha = 0.8;
      ctx.strokeStyle = paint.diagramNow;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(cursorX, 2);
      ctx.lineTo(cursorX, plotH(h) + 2);
      ctx.stroke();
      ctx.globalAlpha = 1;
    }
    ctx.restore();
  }

  function onWheel(e: WheelEvent) {
    e.preventDefault();
    if (!canvas || !sims.diagram) return;
    const rect = canvas.getBoundingClientRect();
    const anchor = tickAt(e.clientX - rect.left, canvas.clientWidth);
    const factor = Math.pow(1.25, Math.sign(e.deltaY));
    viewSpan = Math.max(2000, Math.min(Math.max(4000, sims.diagram.ticks * 1.5), viewSpan * factor));
    viewStart = anchor - (anchor - viewStart) * factor;
    clampView();
    draw();
  }

  function clampView() {
    const max = sims.diagram?.ticks ?? 48000;
    viewStart = Math.max(-viewSpan * 0.2, Math.min(max - viewSpan * 0.2, viewStart));
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
    // click = seek the playback transport to that moment
    const tick = tickAt(e.offsetX, canvas.clientWidth);
    if (Number.isFinite(tick)) {
      playback.seek(Math.round(tick));
      draw();
    }
  }

  function updateHover(px: number, py: number) {
    let best: DrawnLine | null = null;
    let bestDist = 25;
    for (const line of drawn)
      for (const seg of line.segs) {
        const d = segDistSq(px, py, seg.x1, seg.y1, seg.x2, seg.y2);
        if (d < bestDist) {
          bestDist = d;
          best = line;
        }
      }
    const diagram = sims.diagram;
    hover = best && diagram
      ? { x: px, y: py, lines: [best.name, fmtTime(diagram.start, diagram.rate, tickAt(px, canvas.clientWidth))] }
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
    // the playback cursor moves ~10 Hz through ui.t; a modest repaint keeps it visible
    const timer = setInterval(() => draw(), 250);
    draw();
    return () => {
      observer.disconnect();
      canvas.removeEventListener('wheel', onWheel);
      clearInterval(timer);
    };
  });

  $effect(() => {
    void sims.ui.rev;
    void theme.mode;
    void sims.ui.diagramStatus;
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
