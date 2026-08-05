/**
 * Day-aware time formatting — exact port of SimTimeFormat.java:
 * hour = (dayTime/1000 + 6) % 24, day rollover at +6000, frozen clock falls back to elapsed real time.
 */
export function fmtTime(startDayTime: number, rate: number, tick: number): string {
  if (rate <= 0) {
    const seconds = Math.floor(tick / 20);
    return `+${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
  }
  const dayTime = startDayTime + Math.round(tick * rate);
  const hour = (Math.floor(dayTime / 1000) + 6) % 24;
  const minute = Math.floor(((dayTime % 1000) * 60) / 1000);
  const hm = `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
  const day = Math.floor((dayTime + 6000) / 24000);
  const startDay = Math.floor((startDayTime + 6000) / 24000);
  return day > startDay ? `D+${day - startDay} ${hm}` : hm;
}

export function fmtDuration(rate: number, ticks: number): string {
  if (rate <= 0) {
    const seconds = Math.floor(ticks / 20);
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
  }
  const minutes = Math.round((ticks * rate * 60) / 1000);
  if (minutes < 1) return '<1m';
  if (minutes < 60) return `${minutes}m`;
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}
