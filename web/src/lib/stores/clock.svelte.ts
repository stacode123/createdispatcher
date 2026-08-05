/**
 * Server-tick estimator. Anchored on client receipt time of each beacon (skew-proof — server
 * wall clocks are never trusted); effective TPS is measured because servers sag below 20.
 * renderTick lags 1.5 s behind so live interpolation almost always has two known samples.
 */
class ClockStore {
  private lastTick = 0;
  private receivedAt = 0;
  private tps = 20;
  private initialized = false;

  ui = $state({ dayTime: 0, rate: 1 });

  beacon(tick: number, receivedAt: number, dayTime?: number, rate?: number) {
    if (this.initialized && receivedAt > this.receivedAt && tick > this.lastTick) {
      const measured = (tick - this.lastTick) / ((receivedAt - this.receivedAt) / 1000);
      if (measured >= 1 && measured <= 40) this.tps = this.tps * 0.8 + measured * 0.2;
    }
    this.lastTick = tick;
    this.receivedAt = receivedAt;
    this.initialized = true;
    if (dayTime !== undefined) this.ui.dayTime = dayTime;
    if (rate !== undefined) this.ui.rate = rate;
  }

  estServerTick(): number {
    if (!this.initialized) return 0;
    return this.lastTick + ((Date.now() - this.receivedAt) / 1000) * this.tps;
  }

  renderTick(): number {
    return this.estServerTick() - 30;
  }

  estDayTime(): number {
    return this.ui.dayTime + Math.max(0, this.estServerTick() - this.lastTick) * this.ui.rate;
  }
}

export const clock = new ClockStore();
