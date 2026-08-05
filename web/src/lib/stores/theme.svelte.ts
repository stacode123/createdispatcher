/**
 * Light/dark theme (W6). CSS gets it through a `data-theme` attribute on <html> and the
 * token overrides in theme.css; the canvases cannot read CSS variables per frame, so they
 * read `paint` — one plain object, mutated in place on a theme switch, so every module that
 * imported it keeps seeing the current colors without a re-import or a rune subscription.
 *
 * `paint.rev` bumps on every switch: the map's static raster keys on it and re-rasterizes,
 * which a pure CSS variable swap could never trigger.
 */
export type ThemeMode = 'dark' | 'light';

export interface Paint {
  /** Theme name, used as part of raster cache keys. */
  name: ThemeMode;
  rev: number;
  // map
  bg: string;
  track: string;
  station: string;
  sigEntry: string;
  sigChain: string;
  stationLabel: string;
  trainLabel: string;
  selectRing: string;
  badgeOutline: string;
  hud: string;
  // diagrams
  diagramBg: string;
  diagramGrid: string;
  diagramStationLine: string;
  diagramStationText: string;
  diagramAxisText: string;
  diagramNow: string;
  diagramConflict: string;
  /** Route overlays: the route a detoured train took, the direct one, the picked corridor. */
  routeTaken: string;
  routeDirect: string;
  corridorRoute: string;
  /** Per-train hue palette — tuned for contrast against each theme's background. */
  trains: string[];
}

/** The in-game GraphMapScreen palette; the dark theme is the design's home ground. */
const DARK: Omit<Paint, 'rev'> = {
  name: 'dark',
  bg: '#10101c',
  track: '#8a8a96',
  station: '#55ddee',
  sigEntry: '#ff5555',
  sigChain: '#ffa030',
  stationLabel: '#7fe0f0',
  trainLabel: '#d0d0e0',
  selectRing: '#ffffff',
  badgeOutline: '#10101c',
  hud: '#9090a0',
  diagramBg: '#10101c',
  diagramGrid: '#262636',
  diagramStationLine: '#34344a',
  diagramStationText: '#55ddee',
  diagramAxisText: '#9090a0',
  diagramNow: '#40ff70',
  diagramConflict: '#ff5555',
  routeTaken: '#ffa030',
  routeDirect: '#40ff70',
  corridorRoute: '#55ddee',
  trains: ['#E0B040', '#60A0FF', '#FF6090', '#80E0D0', '#C080FF', '#FFA060', '#B0D060', '#DD8888'],
};

/**
 * The same map read on paper-white: every hue darkened until it carries its meaning against
 * a light background (the dark palette's pastels vanish there), with the hue ORDER of the
 * train colors preserved so a train keeps its identity across a theme switch.
 */
const LIGHT: Omit<Paint, 'rev'> = {
  name: 'light',
  bg: '#f4f5f8',
  track: '#616676',
  station: '#0b7d94',
  sigEntry: '#cc2222',
  sigChain: '#b45f00',
  stationLabel: '#0b7d94',
  trainLabel: '#232633',
  selectRing: '#232633',
  badgeOutline: '#3a3f52',
  hud: '#5a6072',
  diagramBg: '#ffffff',
  diagramGrid: '#e7eaf2',
  diagramStationLine: '#ccd2e0',
  diagramStationText: '#0b7d94',
  diagramAxisText: '#5a6072',
  diagramNow: '#1a8f3c',
  diagramConflict: '#cc2222',
  routeTaken: '#b45f00',
  routeDirect: '#1a8f3c',
  corridorRoute: '#0b7d94',
  trains: ['#9a6b00', '#1f5fd0', '#c2185b', '#00796b', '#7b3fbf', '#c05600', '#4f7a1f', '#a13c3c'],
};

export const paint: Paint = { ...DARK, rev: 0 };

const STORAGE_KEY = 'dispatcher.theme';

function initial(): ThemeMode {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'light' || stored === 'dark') return stored;
  } catch {
    /* private mode */
  }
  return window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

let mode = $state<ThemeMode>('dark');

function apply(next: ThemeMode) {
  const source = next === 'light' ? LIGHT : DARK;
  Object.assign(paint, source);
  paint.rev++;
  mode = next;
  document.documentElement.dataset.theme = next;
}

export const theme = {
  get mode() {
    return mode;
  },
  set(next: ThemeMode) {
    if (next === mode) return;
    apply(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      /* private mode — the choice just won't survive the tab */
    }
  },
  toggle() {
    theme.set(mode === 'dark' ? 'light' : 'dark');
  },
  /** Called once at startup, before the first paint. */
  init() {
    apply(initial());
  },
};
