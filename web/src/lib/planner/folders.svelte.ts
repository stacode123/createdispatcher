/**
 * Folders for presets and trains. Both sides store a single slash-separated path per item
 * ("" = unfiled) and this module turns those flat paths into the grouped, collapsible
 * lists the panels render. Preset folders live on the preset record; train folders are a
 * server-side map (trains are Create entities we cannot annotate), fetched here.
 *
 * Collapse state is per-browser — which folders you have open is a view preference, not
 * something to push onto everyone else — so it lives in localStorage, not on the server.
 */
import { MOCK, api, ApiError } from '../api/http';

const COLLAPSE_KEY = 'realism.folders.collapsed';
const EXTRA_KEY = 'realism.folders.extra';

export interface FolderGroup<T> {
  /** Full path, "" for the unfiled group. */
  path: string;
  /** Last segment — what the header shows. */
  label: string;
  depth: number;
  items: T[];
  collapsed: boolean;
  /** Anything filed at or under this path — false for a folder that is still empty. */
  hasItems: boolean;
}

/** Splits a path into its segments, ignoring empties. */
export function segments(path: string): string[] {
  return path.split('/').map((s) => s.trim()).filter(Boolean);
}

/** Client-side mirror of PresetStore.normalizeFolder — the server still has final say. */
export function normalizeFolder(path: string): string {
  return segments(path.replace(/\\/g, '/')).slice(0, 4).map((s) => s.slice(0, 40)).join('/');
}

class FolderStore {
  ui = $state({
    /** trainId → folder path. */
    trains: {} as Record<string, string>,
    /** Collapsed folder keys, namespaced "p:"/"t:" so both lists keep their own state. */
    collapsed: [] as string[],
    /** Declared-but-empty folder paths per scope — see addFolder. */
    extra: { p: [] as string[], t: [] as string[] },
    error: '',
    busy: false,
  });

  private loaded = false;
  private refreshTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    try {
      const stored = localStorage.getItem(COLLAPSE_KEY);
      if (stored) this.ui.collapsed = JSON.parse(stored) as string[];
      const extra = localStorage.getItem(EXTRA_KEY);
      if (extra) {
        const parsed = JSON.parse(extra) as { p?: string[]; t?: string[] };
        this.ui.extra = { p: parsed.p ?? [], t: parsed.t ?? [] };
      }
    } catch {
      /* private mode or corrupt value — start expanded, with no declared folders */
    }
  }

  /**
   * Declares a folder so it shows up as a drop target before anything is in it.
   *
   * A folder that holds something is stored server-side by that something — the path on the
   * preset record, the entry in the train-folder map — so it survives restarts and is shared.
   * An empty one has nothing to hang off, so it stays a local view state (this browser only)
   * until the first item lands in it, at which point the server takes over.
   */
  addFolder(scope: 'p' | 't', path: string): string {
    const normalized = normalizeFolder(path);
    if (!normalized) return '';
    if (!this.ui.extra[scope].includes(normalized))
      this.ui.extra = { ...this.ui.extra, [scope]: [...this.ui.extra[scope], normalized] };
    this.persistExtra();
    return normalized;
  }

  /** Drops a declared folder (and anything declared under it). Only offered while empty. */
  removeFolder(scope: 'p' | 't', path: string) {
    this.ui.extra = {
      ...this.ui.extra,
      [scope]: this.ui.extra[scope].filter((p) => p !== path && !p.startsWith(path + '/')),
    };
    this.persistExtra();
  }

  /** Keeps declared folders in step with a rename of a folder that has items. */
  renameExtra(scope: 'p' | 't', from: string, to: string) {
    const source = normalizeFolder(from);
    const target = normalizeFolder(to);
    if (!source || !target || source === target) return;
    this.ui.extra = {
      ...this.ui.extra,
      [scope]: this.ui.extra[scope]
        .map((p) =>
          p === source || p.startsWith(source + '/') ? normalizeFolder(target + p.slice(source.length)) : p,
        )
        .filter(Boolean),
    };
    this.persistExtra();
  }

  private persistExtra() {
    try {
      localStorage.setItem(EXTRA_KEY, JSON.stringify(this.ui.extra));
    } catch {
      /* not persisting an empty folder is survivable */
    }
  }

  async refresh(force = false) {
    if (MOCK) {
      this.loaded = true;
      return;
    }
    if (this.loaded && !force) return;
    try {
      const dto = await api<{ folders: Record<string, string> }>('/api/train-folders');
      this.ui.trains = dto.folders ?? {};
      this.loaded = true;
    } catch (e) {
      this.ui.error = e instanceof ApiError ? e.key : 'network error';
    }
  }

  /** SSE change signal — coalesce bursts into one refetch. */
  changed() {
    if (!this.loaded) return;
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = null;
      void this.refresh(true);
    }, 400);
  }

  folderOfTrain(trainId: string): string {
    return this.ui.trains[trainId] ?? '';
  }

  /** Files one train; blank unfiles it. */
  async setTrainFolder(trainId: string, folder: string) {
    const normalized = normalizeFolder(folder);
    const previous = this.ui.trains;
    // optimistic — the list regroups as you type rather than after a round trip
    const next = { ...previous };
    if (normalized) next[trainId] = normalized;
    else delete next[trainId];
    this.ui.trains = next;
    if (MOCK) return;
    this.ui.busy = true;
    this.ui.error = '';
    try {
      await api('/api/train-folders', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ trainId, folder: normalized }),
      });
    } catch (e) {
      this.ui.trains = previous;
      this.ui.error = e instanceof ApiError ? e.key : 'network error';
    } finally {
      this.ui.busy = false;
    }
  }

  /** Renames a train folder and everything nested under it. */
  async renameTrainFolder(from: string, to: string) {
    const target = normalizeFolder(to);
    const source = normalizeFolder(from);
    if (!source || source === target) return;
    const next: Record<string, string> = {};
    for (const [trainId, folder] of Object.entries(this.ui.trains)) {
      if (folder === source || folder.startsWith(source + '/')) {
        const moved = target + folder.slice(source.length);
        const normalized = normalizeFolder(moved);
        if (normalized) next[trainId] = normalized;
      } else {
        next[trainId] = folder;
      }
    }
    const previous = this.ui.trains;
    this.ui.trains = next;
    if (MOCK) return;
    this.ui.busy = true;
    try {
      await api('/api/train-folders', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ from: source, to: target }),
      });
    } catch (e) {
      this.ui.trains = previous;
      this.ui.error = e instanceof ApiError ? e.key : 'network error';
    } finally {
      this.ui.busy = false;
    }
  }

  isCollapsed(scope: 'p' | 't', path: string): boolean {
    return this.ui.collapsed.includes(scope + ':' + path);
  }

  toggle(scope: 'p' | 't', path: string) {
    const key = scope + ':' + path;
    this.ui.collapsed = this.ui.collapsed.includes(key)
      ? this.ui.collapsed.filter((k) => k !== key)
      : [...this.ui.collapsed, key];
    try {
      localStorage.setItem(COLLAPSE_KEY, JSON.stringify(this.ui.collapsed));
    } catch {
      /* not persisting collapse state is survivable */
    }
  }

  /**
   * Groups items by folder path, alphabetically, unfiled last. Ancestor headers are
   * synthesized so a lone "A/B/C" still shows its A and B rows; collapsing any ancestor
   * hides the whole subtree. Declared-but-empty folders get a header too — that is what
   * makes them droppable.
   */
  group<T>(scope: 'p' | 't', items: T[], folderOf: (item: T) => string): FolderGroup<T>[] {
    const byPath = new Map<string, T[]>();
    for (const item of items) {
      const path = normalizeFolder(folderOf(item));
      const bucket = byPath.get(path);
      if (bucket) bucket.push(item);
      else byPath.set(path, [item]);
    }
    const filled = [...byPath.entries()].filter(([path, list]) => path !== '' && list.length).map(([path]) => path);
    const paths = new Set<string>(byPath.keys());
    for (const declared of this.ui.extra[scope]) if (declared) paths.add(declared);
    // every ancestor of a used path needs a header of its own
    for (const path of [...paths]) {
      const parts = segments(path);
      for (let i = 1; i < parts.length; i++) paths.add(parts.slice(0, i).join('/'));
    }
    const sorted = [...paths].sort((a, b) => {
      if (a === '') return 1; // unfiled sinks to the bottom
      if (b === '') return -1;
      return a.localeCompare(b);
    });
    const groups: FolderGroup<T>[] = [];
    let hiddenUnder: string | null = null;
    for (const path of sorted) {
      // a collapsed ancestor swallows its descendants
      if (hiddenUnder !== null && path.startsWith(hiddenUnder + '/')) continue;
      hiddenUnder = null;
      const parts = segments(path);
      const collapsed = this.isCollapsed(scope, path);
      groups.push({
        path,
        label: parts.length ? parts[parts.length - 1] : 'Unfiled',
        depth: Math.max(0, parts.length - 1),
        items: collapsed ? [] : (byPath.get(path) ?? []),
        collapsed,
        hasItems: path === '' || filled.some((p) => p === path || p.startsWith(path + '/')),
      });
      if (collapsed && path !== '') hiddenUnder = path;
    }
    return groups;
  }

  /** Every folder path in use, for the datalists that back the folder inputs. */
  known(paths: string[]): string[] {
    const all = new Set<string>();
    for (const path of paths) {
      const parts = segments(path);
      for (let i = 1; i <= parts.length; i++) all.add(parts.slice(0, i).join('/'));
    }
    return [...all].sort();
  }
}

export const folders = new FolderStore();
