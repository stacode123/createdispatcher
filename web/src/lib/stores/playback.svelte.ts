import type { SimRunDto } from '../api/types';
import { preparePlayback, type PlaybackTrain } from '../map/interpolate';

/**
 * Playback clock. Hot fields (t, playing, trains) are plain — the rAF loop reads them directly;
 * the `ui` runes mirror updates at ~10 Hz for Svelte components.
 */
class PlaybackStore {
  t = 0;
  playing = false;
  speedMult = 20;
  trains: PlaybackTrain[] = [];
  meta = { start: 0, rate: 1, ticks: 0, stride: 40 };
  /** Graph the loaded run's edge ids belong to ('' = whatever is loaded first). */
  graphId = '';
  private uiAccumulator = 0;

  /** Restart from the top instead of stopping at the end. */
  loop = false;

  ui = $state({ loaded: false, playing: false, speedMult: 20, t: 0, ticks: 0, loop: false });

  load(run: SimRunDto, graphId = '') {
    this.trains = preparePlayback(run);
    this.meta = run.meta;
    this.graphId = graphId;
    this.t = 0;
    this.playing = false;
    this.ui.loaded = true;
    this.ui.ticks = run.meta.ticks;
    this.syncUi();
  }

  unload() {
    this.trains = [];
    this.playing = false;
    this.t = 0;
    this.graphId = '';
    this.ui.loaded = false;
    this.syncUi();
  }

  toggle() {
    if (!this.ui.loaded) return;
    // pressing play at the very end restarts rather than doing nothing
    if (!this.playing && this.t >= this.meta.ticks) this.t = 0;
    this.playing = !this.playing;
    this.syncUi();
  }

  toggleLoop() {
    this.loop = !this.loop;
    this.syncUi();
  }

  setSpeed(mult: number) {
    this.speedMult = mult;
    this.syncUi();
  }

  seek(tick: number) {
    this.t = Math.min(this.meta.ticks, Math.max(0, tick));
    this.syncUi();
  }

  /** Called from the rAF loop. */
  advance(dtMs: number) {
    if (!this.playing) return;
    this.t += (dtMs / 1000) * 20 * this.speedMult;
    if (this.t >= this.meta.ticks) {
      if (this.loop) {
        this.t = 0;
      } else {
        this.t = this.meta.ticks;
        this.playing = false;
      }
      this.syncUi();
      return;
    }
    this.uiAccumulator += dtMs;
    if (this.uiAccumulator >= 100) {
      this.uiAccumulator = 0;
      this.ui.t = this.t;
      this.ui.playing = this.playing;
    }
  }

  syncUi() {
    this.ui.playing = this.playing;
    this.ui.speedMult = this.speedMult;
    this.ui.t = this.t;
    this.ui.loop = this.loop;
  }
}

export const playback = new PlaybackStore();
