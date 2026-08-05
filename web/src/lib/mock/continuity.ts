// Synchronous continuity check for the live interpolation pipeline. Runs the whole
// simulation in a plain loop (no rAF), so it works from the console or CDP even on a
// hidden tab: window.__continuityCheck() in mock/dev builds.
//
// Model: samples arrive every ~1s (with jitter) at 20 ticks apart; the render clock runs at
// 60 fps estimating server ticks exactly like ClockStore (beacon + elapsed × 20), lagging 30
// ticks. FAIL conditions: any frame-to-frame jump far beyond what the current speed explains,
// or a significant share of frames where a moving train barely advances (the old stutter).
import { buildFixture } from './fixtures';
import { build } from '../map/worker';
import { preparePlayback, trainSampleAt } from '../map/interpolate';
import { liveTrainPos, type WalkCache } from '../map/liveInterpolate';
import type { LiveSample } from '../stores/liveTrains.svelte';

export function runContinuityCheck(seconds = 60, trainCount = 20) {
  const fixture = buildFixture();
  const graph = build(fixture.graph);
  const prepared = preparePlayback(fixture.sim).slice(0, trainCount);

  const rings: LiveSample[][] = prepared.map(() => []);
  const caches: WalkCache[] = prepared.map(() => ({ key: -1, path: null }));
  const last: ({ x: number; z: number } | null)[] = prepared.map(() => null);
  const out = { x: 0, z: 0, speed: 0 };

  const FPS = 60;
  const DT = 1 / FPS;
  let simTick = 6000;
  let lastPumpWall = 0;
  let nextPumpWall = 0;
  let jumps = 0;
  let maxJump = 0;
  let stallFrames = 0;
  let movingFrames = 0;

  for (let step = 0; step < seconds * FPS; step++) {
    const wall = step * DT;
    if (wall >= nextPumpWall) {
      lastPumpWall = wall;
      nextPumpWall = wall + 1 + (((step * 7919) % 100) - 50) / 1000; // ±50ms network jitter
      simTick += 20;
      prepared.forEach((train, i) => {
        const s = trainSampleAt(graph, train, simTick);
        if (!s) return;
        const ring = rings[i];
        ring.push({ tick: simTick, graphId: fixture.graph.id, edge: s.edge, off: s.off, speed: s.speed, dim: 0 });
        if (ring.length > 12) ring.shift();
      });
    }
    const renderTick = simTick + (wall - lastPumpWall) * 20 - 30;
    prepared.forEach((train, i) => {
      const ring = rings[i];
      if (!ring.length) return;
      liveTrainPos(graph, ring, renderTick, caches[i], out);
      const previous = last[i];
      if (previous) {
        const moved = Math.hypot(out.x - previous.x, out.z - previous.z);
        const expected = Math.abs(out.speed) * 20 * DT; // blocks per frame at current speed
        if (moved > Math.max(4, expected * 3 + 0.5)) {
          jumps++;
          maxJump = Math.max(maxJump, moved);
        }
        if (Math.abs(out.speed) > 0.3) {
          movingFrames++;
          if (moved < expected * 0.2) stallFrames++;
        }
      }
      last[i] = { x: out.x, z: out.z };
    });
  }

  const stallPct = (100 * stallFrames) / Math.max(1, movingFrames);
  return {
    frames: seconds * FPS,
    trains: prepared.length,
    jumps,
    maxJump: Math.round(maxJump * 10) / 10,
    movingFrames,
    stallFrames,
    stallPct: Math.round(stallPct * 100) / 100,
    verdict: jumps === 0 && stallPct < 5 ? 'PASS' : 'FAIL',
  };
}
