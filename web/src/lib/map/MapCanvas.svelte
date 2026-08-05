<script lang="ts">
  import { onMount } from 'svelte';
  import { mapCamera, mapView } from './camera';
  import { StaticLayer } from './staticLayer';
  import { drawDynamic, drawRoutes, type MapBadge, type RouteOverlay, type TrainDot } from './dynamicLayer';
  import type { RouteSource } from '../api/types';
  import { forVisibleRuns, type LoadedGraph } from './geometry';
  import { graphStore } from '../stores/graphs.svelte';
  import { playback } from '../stores/playback.svelte';
  import { liveTrains } from '../stores/liveTrains.svelte';
  import { clock } from '../stores/clock.svelte';
  import { mapPrefs } from '../stores/mapPrefs.svelte';
  import { mapFocus } from '../stores/mapFocus.svelte';
  import { notifications, KIND_COLOR } from '../stores/notifications.svelte';
  import { replays } from '../stores/replays.svelte';
  import { trainPosAt } from './interpolate';
  import { liveTrainPos, type WalkCache } from './liveInterpolate';
  import { fmtTime } from '../diagram/timeFormat';
  import { colorFor } from './palette';
  import { paint, theme } from '../stores/theme.svelte';
  import { corridor } from '../stores/corridor.svelte';
  import { sims, CONFLICT_COLORS } from '../planner/sims.svelte';

  let { source = 'none' }: { source?: 'live' | 'playback' | 'replay' | 'none' } = $props();

  /**
   * Playback and replay views lay a transport bar across the bottom of this canvas, with
   * its clock at the right end — exactly where the fit button sits. Lift the button above
   * the bar whenever one is showing. The conditions mirror what those views render on.
   */
  const transportShown = $derived(
    (source === 'playback' && playback.ui.loaded) || (source === 'replay' && replays.ui.meta != null),
  );

  let canvas: HTMLCanvasElement;
  const cam = mapCamera;
  const layer = new StaticLayer();
  const scratch = { x: 0, z: 0, speed: 0 };
  const walkCaches = new Map<string, WalkCache>();
  let lastWheel = 0;
  let dragging = false;
  let dragMoved = 0;
  let lastDots: TrainDot[] = [];
  let lastBadges: MapBadge[] = [];
  let focusSeq = mapFocus.seq;
  let tween: { fx: number; fz: number; fs: number; tx: number; tz: number; ts: number; t0: number } | null = null;
  let hover = $state<{ x: number; y: number; lines: string[]; kind: 'train' | 'station' | 'edge' | 'badge' } | null>(null);

  function dimName(): string {
    const dims = graphStore.dims;
    return mapPrefs.dim && dims.includes(mapPrefs.dim) ? mapPrefs.dim : (dims[0] ?? '');
  }

  onMount(() => {
    const ctx = canvas.getContext('2d')!;
    let raf = 0;
    let last = 0;
    let frames = 0;
    let fps = 0;
    let fpsAt = performance.now();

    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      lastWheel = performance.now();
      const rect = canvas.getBoundingClientRect();
      cam.zoomAt(e.clientX - rect.left, e.clientY - rect.top, Math.pow(1.2, -Math.sign(e.deltaY)), rect.width, rect.height);
    };
    canvas.addEventListener('wheel', onWheel, { passive: false });

    const loop = (now: number) => {
      raf = requestAnimationFrame(loop);
      const dt = last ? Math.min(100, now - last) : 16;
      last = now;
      if (source === 'playback') playback.advance(dt);
      else if (source === 'replay') replays.advance(dt);

      const w = canvas.clientWidth;
      const h = canvas.clientHeight;
      if (w === 0 || h === 0) return;
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
        canvas.width = Math.round(w * dpr);
        canvas.height = Math.round(h * dpr);
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, w, h);

      const graphs = graphStore.graphs;
      if (!graphs.length) {
        frames = 0;
        return;
      }
      let dim = dimName();
      if (!mapView.fitted || mapView.lastFitDim !== dim) {
        mapView.fitted = true;
        smartFit(w, h, dim);
      }

      if (mapFocus.seq !== focusSeq) {
        focusSeq = mapFocus.seq;
        if (mapFocus.dim && mapFocus.dim !== dim && graphStore.dims.includes(mapFocus.dim)) {
          mapPrefs.dim = mapFocus.dim;
          dim = mapFocus.dim;
        }
        tween = { fx: cam.x, fz: cam.z, fs: cam.scale, tx: mapFocus.x, tz: mapFocus.z,
                  ts: Math.max(cam.scale, 0.8), t0: now };
      }
      if (tween) {
        const p = Math.min(1, (now - tween.t0) / 400);
        const e = 1 - Math.pow(1 - p, 3);
        cam.x = tween.fx + (tween.tx - tween.fx) * e;
        cam.z = tween.fz + (tween.tz - tween.fz) * e;
        cam.scale = tween.fs + (tween.ts - tween.fs) * e;
        if (p >= 1) tween = null;
      }

      const settled = now - lastWheel > 150;
      layer.maybeRaster(graphs, cam, w, h, dim, graphStore.generation, settled, {
        stations: mapPrefs.showStations,
        signals: mapPrefs.showSignals,
      });
      layer.blit(ctx, cam, w, h);

      // Detour route overlays: replay always shows its event's routes; live shows them for the
      // highlighted DETOUR notification. Orange solid = route taken, green dashed = direct.
      let routes: RouteOverlay[] = [];
      let routesGraph = null as ReturnType<typeof graphStore.byId.get> | null;
      if (source === 'replay' && replays.ui.meta) {
        routesGraph = graphStore.byId.get(replays.ui.meta.graphId) ?? null;
        if (routesGraph) routes = routesFrom(replays.ui.meta.eventData as RouteSource | undefined, routesGraph);
      } else if (source === 'live' && notifications.ui.highlight) {
        const n =
          notifications.ui.active.find((a) => a.id === notifications.ui.highlight) ??
          notifications.ui.resolved.find((a) => a.id === notifications.ui.highlight);
        if (n?.kind === 'DETOUR' && n.graphId) {
          routesGraph = graphStore.byId.get(n.graphId) ?? null;
          if (routesGraph) routes = routesFrom(n.data as RouteSource, routesGraph);
        }
      }
      if (routes.length && routesGraph) {
        const routeDim = routesGraph.dims.indexOf(dim);
        if (routeDim >= 0) drawRoutes(ctx, routesGraph, cam, w, h, routeDim, routes);
      }

      // Corridor route (diagram dock open): the picked A→B path in station cyan,
      // so "why did it go THAT way" is visible at a glance.
      let corridorDrawn = false;
      if (source === 'live' && corridor.ui.open && corridor.actual?.path?.length) {
        const cg = graphStore.byId.get(corridor.actual.graphId);
        if (cg && cg.version === corridor.actual.graphVersion) {
          const cdim = cg.dims.indexOf(dim);
          if (cdim >= 0) {
            drawRoutes(ctx, cg, cam, w, h, cdim, [{ edges: corridor.actual.path, color: paint.corridorRoute }]);
            corridorDrawn = true;
          }
        }
      }

      const dots: TrainDot[] = [];
      if (!mapPrefs.showTrains) {
        // layer toggled off — skip dot building entirely (hit-testing goes quiet with it)
      } else if (source === 'playback' && playback.trains.length) {
        const g = (playback.graphId ? graphStore.byId.get(playback.graphId) : null) ?? graphs[0];
        for (const train of playback.trains) {
          if (trainPosAt(g, train, playback.t, scratch))
            dots.push({ x: scratch.x, z: scratch.z, speed: scratch.speed, color: train.color, name: train.name });
        }
      } else if (source === 'live') {
        const renderTick = clock.renderTick();
        const selected = liveTrains.ui.selected;
        for (const [id, ring] of liveTrains.rings) {
          if (!ring.length) continue;
          const newest = ring[ring.length - 1];
          const g = graphStore.byId.get(newest.graphId);
          if (!g) continue;
          if (liveTrains.frameVersions[newest.graphId] !== g.version) continue; // stale edge ids
          if (g.dims.indexOf(dim) !== newest.dim) continue;
          let cache = walkCaches.get(id);
          if (!cache) walkCaches.set(id, (cache = { key: -1, path: null }));
          liveTrainPos(g, ring, renderTick, cache, scratch);
          dots.push({
            x: scratch.x,
            z: scratch.z,
            speed: scratch.speed,
            color: colorFor(id),
            name: liveTrains.nameById.get(id) ?? '',
            selected: id === selected,
            id,
          });
        }
        if (liveTrains.ui.follow && selected) {
          const dot = dots.find((d) => d.id === selected);
          if (dot) {
            cam.x = dot.x;
            cam.z = dot.z;
          }
        }
      }

      frames++;
      if (now - fpsAt >= 500) {
        fps = Math.round((frames * 1000) / (now - fpsAt));
        frames = 0;
        fpsAt = now;
      }
      // layer toggled off — skip badge building, which takes hit-testing with it
      const badges: MapBadge[] = [];
      if (!mapPrefs.showConflicts) {
        // nothing to draw
      } else if (source === 'live') {
        for (const n of notifications.ui.active) {
          if (n.dim !== dim) continue;
          badges.push({ x: n.x, z: n.z, color: KIND_COLOR[n.kind], id: n.id });
        }
      } else if (source === 'playback' && sims.ui.phase === 'done') {
        // sim conflicts, badged at their graph location; click seeks the transport there
        const g = (playback.graphId ? graphStore.byId.get(playback.graphId) : null) ?? graphs[0];
        const dimIdx = g ? g.dims.indexOf(dim) : -1;
        for (const conflict of sims.ui.conflicts) {
          if (conflict.dim !== dimIdx) continue;
          badges.push({ x: conflict.x, z: conflict.z,
            color: CONFLICT_COLORS[conflict.type] ?? '#ff5555', id: `c${conflict.index}` });
        }
      } else if (source === 'replay') {
        const meta = replays.ui.meta;
        if (meta) {
          const g = graphStore.byId.get(meta.graphId);
          if (g && g.version === meta.graphVersion) {
            for (const train of replays.trains) {
              if (!train.samples.length) continue;
              liveTrainPos(g, train.samples, replays.t, train.cache, scratch);
              dots.push({
                x: scratch.x,
                z: scratch.z,
                speed: scratch.speed,
                color: colorFor(train.id),
                name: train.name,
                selected: train.involved,
                id: train.id,
              });
            }
          }
          if (meta.dim === dim) badges.push({ x: meta.x, z: meta.z, color: KIND_COLOR[meta.kind], id: meta.notificationId });
        }
      }

      const hud = [`${fps} fps`, `${cam.scale.toFixed(2)} px/block`];
      if (source === 'live') {
        hud.push(`${liveTrains.ui.positionCount} trains`);
        if (clock.ui.dayTime > 0) hud.push(fmtTime(clock.estDayTime(), 1, 0));
      } else if (playback.ui.loaded && source === 'playback') {
        hud.push(fmtTime(playback.meta.start, playback.meta.rate, playback.t));
      } else if (source === 'replay' && replays.ui.meta) {
        hud.push('replay ' + fmtTime(0, 0, Math.max(0, Math.round(replays.t - replays.ui.meta.startTick))));
      }
      if (routes.length) hud.push('━ taken   ┅ direct');
      if (corridorDrawn) hud.push('━ corridor route');
      const pulse = 1 + 0.25 * Math.sin(now / 280);
      drawDynamic(ctx, graphs, cam, w, h, dim, dots, badges, pulse,
        mapPrefs.showLabels && mapPrefs.showStations, hud, 46);
      lastDots = dots;
      lastBadges = badges;
    };
    raf = requestAnimationFrame(loop);

    return () => {
      cancelAnimationFrame(raf);
      canvas.removeEventListener('wheel', onWheel);
    };
  });

  /**
   * Fit where the network MASS is: only graphs with a meaningful share of the biggest one's
   * edges join the union — a stray fragment far away must not banish the camera to empty
   * space at max zoom-out. Fragments stay reachable by pan/zoom or the fit button.
   */
  function smartFit(w: number, h: number, dim: string) {
    mapView.lastFitDim = dim;
    let top = 0;
    for (const g of graphStore.graphs)
      if (g.dims.indexOf(dim) >= 0) top = Math.max(top, g.from.length);
    const threshold = Math.max(60, top / 20);

    let minX = Infinity;
    let minZ = Infinity;
    let maxX = -Infinity;
    let maxZ = -Infinity;
    let found = false;
    const consider = (onlyBig: boolean) => {
      for (const g of graphStore.graphs) {
        const idx = g.dims.indexOf(dim);
        if (idx < 0) continue;
        if (onlyBig && g.from.length < threshold) continue;
        const b = g.bbox[idx];
        if (!b || b.minX > b.maxX) continue;
        minX = Math.min(minX, b.minX);
        minZ = Math.min(minZ, b.minZ);
        maxX = Math.max(maxX, b.maxX);
        maxZ = Math.max(maxZ, b.maxZ);
        found = true;
      }
    };
    consider(true);
    if (!found) consider(false);
    if (found) cam.fit({ minX, minZ, maxX, maxZ }, w, h);
  }

  function refit() {
    if (!canvas) return;
    tween = null;
    liveTrains.ui.follow = false;
    smartFit(canvas.clientWidth, canvas.clientHeight, dimName());
  }

  function routesFrom(data: RouteSource | undefined, g: NonNullable<ReturnType<typeof graphStore.byId.get>>): RouteOverlay[] {
    if (!data || data.routesGraphVersion !== g.version) return [];
    const out: RouteOverlay[] = [];
    if (data.routedEdges?.length) out.push({ edges: data.routedEdges, color: paint.routeTaken });
    if (data.directEdges?.length) out.push({ edges: data.directEdges, color: paint.routeDirect, dash: true });
    return out;
  }

  function pointerDown(e: PointerEvent) {
    dragging = true;
    dragMoved = 0;
    tween = null;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  }

  function pointerMove(e: PointerEvent) {
    if (dragging) {
      dragMoved += Math.abs(e.movementX) + Math.abs(e.movementY);
      if (dragMoved > 4 && liveTrains.ui.follow) liveTrains.ui.follow = false;
      cam.panBy(e.movementX, e.movementY);
      hover = null;
    } else {
      updateHover(e.offsetX, e.offsetY);
    }
  }

  function pointerUp(e: PointerEvent) {
    const wasClick = dragging && dragMoved <= 4;
    dragging = false;
    if (wasClick && source === 'playback') {
      const badge = hitBadge(e.offsetX, e.offsetY);
      if (badge) sims.focusConflictBadge(badge);
      return;
    }
    if (!wasClick || source !== 'live') return;
    if (corridor.ui.picking) {
      const station = hitStation(e.offsetX, e.offsetY);
      if (station) {
        void corridor.pickStation(station.name, station.graphId);
        return;
      }
    }
    const badge = hitBadge(e.offsetX, e.offsetY);
    if (badge) {
      const n = notifications.ui.active.find((a) => a.id === badge);
      if (n) notifications.focus(n);
      return;
    }
    // select only — follow is the explicit toggle in the info panel, never an invisible
    // side effect that fights the fit button and tab switches
    const hit = hitTrain(e.offsetX, e.offsetY);
    liveTrains.select(hit);
  }

  function hitBadge(px: number, py: number): string | null {
    let best: string | null = null;
    let bestDist = 144;
    for (const badge of lastBadges) {
      if (badge.px == null || badge.py == null) continue;
      const d = (badge.px - px) ** 2 + (badge.py - py) ** 2;
      if (d < bestDist) {
        bestDist = d;
        best = badge.id;
      }
    }
    return best;
  }

  /** Nearest station within ~12px, for corridor endpoint picking. */
  function hitStation(px: number, py: number): { name: string; graphId: string } | null {
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    const dim = dimName();
    const wx = cam.toWorldX(px, w);
    const wz = cam.toWorldZ(py, h);
    const reach = 16 / cam.scale;
    for (const g of graphStore.graphs) {
      const idx = g.dims.indexOf(dim);
      if (idx < 0) continue;
      const box = g.bbox[idx];
      if (!box || box.maxX < wx - reach || box.minX > wx + reach || box.maxZ < wz - reach || box.minZ > wz + reach)
        continue;
      for (const st of g.stations) {
        if (st.dim !== idx) continue;
        const d = ((st.x - wx) * cam.scale) ** 2 + ((st.z - wz) * cam.scale) ** 2;
        if (d < 144) return { name: st.name, graphId: g.id };
      }
    }
    return null;
  }

  function hitTrain(px: number, py: number): string | null {
    let best: string | null = null;
    let bestDist = 100;
    for (const dot of lastDots) {
      if (dot.id == null || dot.px == null || dot.py == null) continue;
      const d = (dot.px - px) ** 2 + (dot.py - py) ** 2;
      if (d < bestDist) {
        bestDist = d;
        best = dot.id;
      }
    }
    return best;
  }

  function updateHover(px: number, py: number) {
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    const dim = dimName();
    const badgeId = hitBadge(px, py);
    if (badgeId) {
      const n = notifications.ui.active.find((a) => a.id === badgeId);
      if (n) {
        hover = { x: px, y: py, lines: [n.kind.replace('_', ' ').toLowerCase(), n.message], kind: 'badge' };
        return;
      }
      const conflict = source === 'playback'
        ? sims.ui.conflicts.find((c) => `c${c.index}` === badgeId) : undefined;
      if (conflict) {
        hover = { x: px, y: py, kind: 'badge',
          lines: [`${conflict.typeName} conflict`, conflict.resource,
            conflict.trains.map((t) => sims.nameOf(t)).join(', ')] };
        return;
      }
    }
    const trainId = hitTrain(px, py);
    if (trainId) {
      const dot = lastDots.find((d) => d.id === trainId)!;
      hover = { x: px, y: py, lines: [dot.name, `${Math.round(Math.abs(dot.speed) * 72)} km/h`], kind: 'train' };
      return;
    }
    const wx = cam.toWorldX(px, w);
    const wz = cam.toWorldZ(py, h);
    const reach = 16 / cam.scale;
    // stations within 12px
    for (const g of graphStore.graphs) {
      const idx = g.dims.indexOf(dim);
      if (idx < 0) continue;
      const box = g.bbox[idx];
      if (!box || box.maxX < wx - reach || box.minX > wx + reach || box.maxZ < wz - reach || box.minZ > wz + reach)
        continue;
      for (const st of g.stations) {
        if (st.dim !== idx) continue;
        const d = ((st.x - wx) * cam.scale) ** 2 + ((st.z - wz) * cam.scale) ** 2;
        if (d < 144) {
          hover = { x: px, y: py, lines: [st.name, 'station'], kind: 'station' };
          return;
        }
      }
    }
    // nearest edge within 8px
    const r = 8 / cam.scale;
    let bestLines: string[] | null = null;
    let bestDist = 64;
    for (const g of graphStore.graphs) {
      const idx = g.dims.indexOf(dim);
      if (idx < 0) continue;
      const gb = g.bbox[idx];
      if (!gb || gb.maxX < wx - r || gb.minX > wx + r || gb.maxZ < wz - r || gb.minZ > wz + r) continue;
      forVisibleRuns(g, wx - r, wz - r, wx + r, wz + r, (edge, ptStart, ptCount) => {
        if (!g.canonical[edge] || g.dim[edge] !== idx) return;
        for (let i = 0; i < ptCount - 1; i++) {
          const d = segDistSq(g.pts, ptStart + i, wx, wz) * cam.scale * cam.scale;
          if (d < bestDist) {
            bestDist = d;
            const cap = g.cap[edge] > 0 ? ` · ≤${Math.round(g.cap[edge])} km/h` : '';
            bestLines = [`track · ${Math.round(g.len[edge])} m${signalLabel(g, edge)}${cap}`];
          }
        }
      });
    }
    hover = bestLines ? { x: px, y: py, lines: bestLines, kind: 'edge' } : null;
  }

  /**
   * Entry signals of BOTH directed twins — only the canonical one is ever the hovered
   * edge, so a signal that only governs the reverse direction would otherwise read as
   * "no signal here".
   */
  function signalLabel(g: LoadedGraph, edge: number): string {
    const twin = g.opp[edge];
    const here = g.sig[edge];
    const back = twin >= 0 ? g.sig[twin] : 0;
    if (!here && !back) return '';
    const name = (kind: number) => (kind === 1 ? 'entry' : 'chain');
    if (!here || !back) return ` · ${name(here || back)} signal`;
    return here === back ? ` · ${name(here)} signal (both ways)` : ` · ${name(here)} + ${name(back)} signal`;
  }

  function segDistSq(pts: Float32Array, i: number, wx: number, wz: number): number {
    const ax = pts[i * 2];
    const az = pts[i * 2 + 1];
    const bx = pts[(i + 1) * 2];
    const bz = pts[(i + 1) * 2 + 1];
    const dx = bx - ax;
    const dz = bz - az;
    const lenSq = dx * dx + dz * dz;
    const t = lenSq === 0 ? 0 : Math.min(1, Math.max(0, ((wx - ax) * dx + (wz - az) * dz) / lenSq));
    const ex = wx - (ax + t * dx);
    const ez = wz - (az + t * dz);
    return ex * ex + ez * ez;
  }
</script>

<div class="wrap">
  <canvas
    bind:this={canvas}
    class:clickable={hover?.kind === 'train' || hover?.kind === 'badge'
      || (hover?.kind === 'station' && corridor.ui.picking != null)}
    onpointerdown={pointerDown}
    onpointermove={pointerMove}
    onpointerup={pointerUp}
    onpointercancel={pointerUp}
    onpointerleave={() => (hover = null)}
  ></canvas>
  <div class="controls mono">
    <button
      class:off={!mapPrefs.showTrains}
      onclick={() => (mapPrefs.showTrains = !mapPrefs.showTrains)}
      title="toggle train visibility"
    >trains</button>
    <button
      class:off={!mapPrefs.showStations}
      onclick={() => (mapPrefs.showStations = !mapPrefs.showStations)}
      title="toggle station visibility"
    >stations</button>
    <button
      class:off={!mapPrefs.showSignals}
      onclick={() => (mapPrefs.showSignals = !mapPrefs.showSignals)}
      title="toggle signal visibility"
    >signals</button>
    <button
      class:off={!mapPrefs.showConflicts}
      onclick={() => (mapPrefs.showConflicts = !mapPrefs.showConflicts)}
      title="toggle conflict and alert markers"
    >conflicts</button>
    <button
      class:on={mapPrefs.legendOpen}
      onclick={() => (mapPrefs.legendOpen = !mapPrefs.legendOpen)}
      title="legend"
    >?</button>
  </div>
  {#if mapPrefs.legendOpen}
    <!-- keyed on the theme: the swatches read `paint`, which is a plain object by design -->
    {#key theme.mode}
      <div class="legend panel mono">
        <div><span class="sw line" style="background: {paint.track}"></span> track</div>
        <div><span class="sw box" style="background: {paint.station}"></span> station</div>
        <div><span class="sw box" style="background: {paint.sigEntry}"></span> entry signal</div>
        <div><span class="sw box" style="background: {paint.sigChain}"></span> chain signal</div>
        <div><span class="sw dot" style="background: {paint.trains[1]}"></span> train</div>
        <div><span class="sw dia" style="background: {KIND_COLOR.SIGNAL_WAIT}"></span> signal wait</div>
        <div><span class="sw dia" style="background: {KIND_COLOR.DEADLOCK}"></span> deadlock</div>
        <div><span class="sw dia" style="background: {KIND_COLOR.DETOUR}"></span> detour</div>
        <div><span class="sw line" style="background: {paint.routeTaken}"></span> route taken</div>
        <div><span class="sw line dashed" style="background: {paint.routeDirect}"></span> direct route</div>
        <div><span class="sw line" style="background: {paint.corridorRoute}"></span> corridor route</div>
      </div>
    {/key}
  {/if}
  <button
    class="fit mono"
    class:raised={transportShown}
    onclick={refit}
    title="fit the view to the network"
  >⌖ fit</button>
  {#if hover}
    <div class="tooltip mono" style="left: {hover.x + 14}px; top: {hover.y + 10}px">
      {#each hover.lines as line (line)}<div>{line}</div>{/each}
    </div>
  {/if}
</div>

<style>
  .wrap {
    position: relative;
    width: 100%;
    height: 100%;
  }
  canvas {
    width: 100%;
    height: 100%;
    display: block;
    touch-action: none;
    cursor: grab;
  }
  canvas.clickable {
    cursor: pointer;
  }
  canvas:active {
    cursor: grabbing;
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
  .fit {
    position: absolute;
    right: 12px;
    bottom: 12px;
    font-size: 11px;
    padding: 3px 8px;
    background: var(--panel);
    z-index: 4;
  }
  /* clears the transport bar: it sits 12px up and measures 42px, so 62px leaves 8px of air */
  .fit.raised {
    bottom: 62px;
  }
  .controls {
    position: absolute;
    top: 12px;
    right: 12px;
    display: flex;
    gap: 4px;
    z-index: 5;
  }
  .controls button {
    font-size: 10px;
    padding: 2px 7px;
    background: var(--panel);
  }
  .controls button.off {
    opacity: 0.45;
    text-decoration: line-through;
  }
  .controls button.on {
    color: var(--station);
    border-color: var(--station);
  }
  .legend {
    position: absolute;
    top: 44px;
    right: 12px;
    z-index: 6;
    display: grid;
    gap: 4px;
    padding: 8px 10px;
    font-size: 11px;
  }
  .legend .sw {
    display: inline-block;
    vertical-align: middle;
    margin-right: 6px;
  }
  .legend .line {
    width: 16px;
    height: 2px;
  }
  .legend .line.dashed {
    background-image: linear-gradient(90deg, currentColor 0, currentColor 0);
    -webkit-mask: repeating-linear-gradient(90deg, #000 0 4px, transparent 4px 7px);
    mask: repeating-linear-gradient(90deg, #000 0 4px, transparent 4px 7px);
  }
  .legend .box {
    width: 7px;
    height: 7px;
  }
  .legend .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
  }
  .legend .dia {
    width: 8px;
    height: 8px;
    transform: rotate(45deg);
  }
</style>
