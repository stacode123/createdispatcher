/**
 * Resizable panel sizes, in pixels, persisted per browser. How much room the map deserves
 * versus a corridor diagram or the train roster is a per-person, per-screen judgement — a
 * long corridor squeezed into a fixed 180px dock is unreadable — so the splitters remember
 * what you dragged them to, like the folder collapse state does.
 */
const STORAGE_KEY = 'realism.layout';

export interface Bounds {
  min: number;
  max: number;
  fallback: number;
}

/** Bounds are also the reset targets: double-clicking a splitter returns to `fallback`. */
export const BOUNDS = {
  liveDock: { min: 120, max: 900, fallback: 260 },
  plannerDock: { min: 120, max: 900, fallback: 220 },
  presetCol: { min: 180, max: 620, fallback: 280 },
  trainCol: { min: 200, max: 640, fallback: 320 },
} satisfies Record<string, Bounds>;

export type LayoutKey = keyof typeof BOUNDS;

function clamp(key: LayoutKey, value: number): number {
  const bounds = BOUNDS[key];
  if (!Number.isFinite(value)) return bounds.fallback;
  return Math.max(bounds.min, Math.min(bounds.max, Math.round(value)));
}

function load(): Record<LayoutKey, number> {
  const sizes = {} as Record<LayoutKey, number>;
  let stored: Partial<Record<LayoutKey, number>> = {};
  try {
    stored = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}');
  } catch {
    /* unreadable or private mode — defaults are fine */
  }
  for (const key of Object.keys(BOUNDS) as LayoutKey[])
    sizes[key] = clamp(key, stored[key] ?? BOUNDS[key].fallback);
  return sizes;
}

const sizes = $state(load());
let saveTimer: ReturnType<typeof setTimeout> | null = null;

export const layout = {
  get sizes() {
    return sizes;
  },
  /**
   * A size that also fits the window: a panel dragged wide on a big screen must not swallow
   * a small one, and a stored value from another monitor must not strand the map at 0px.
   */
  fit(key: LayoutKey, available: number): number {
    return Math.max(BOUNDS[key].min, Math.min(sizes[key], Math.round(available)));
  },
  set(key: LayoutKey, value: number) {
    sizes[key] = clamp(key, value);
    if (saveTimer) clearTimeout(saveTimer);
    // during a drag this fires per frame — write once the pointer settles
    saveTimer = setTimeout(() => {
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(sizes));
      } catch {
        /* private mode — the size just won't survive the tab */
      }
    }, 400);
  },
  reset(key: LayoutKey) {
    layout.set(key, BOUNDS[key].fallback);
  },
};
