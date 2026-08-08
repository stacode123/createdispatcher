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

const COLLAPSE_KEY = 'dispatcher.folders.collapsed';
const EXTRA_KEY = 'dispatcher.folders.extra';

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
  /** Whether this folder holds real content independent of the current filter. */
  hasContent: boolean;
  /** Nested folders directly under this one, in display order. */
  children: FolderGroup<T>[];
}

/**
 * One renderable row of the folder tree: a folder header, or the direct items of one
 * folder. Headers are emitted depth-first with a folder's own items after its entire
 * subtree, so opening a folder shows its nested folders on top and its direct items
 * underneath them.
 */
export type FolderRow<T> =
  | { kind: 'folder'; path: string; label: string; depth: number; collapsed: boolean; hasItems: boolean; hasContent: boolean }
  | { kind: 'items'; path: string; depth: number; items: T[] };

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

  /**
   * Deployer-only: ask the server to re-file the roster from each train's CRN category
   * and line. {@code overwrite} false only files currently-unfiled trains; true replaces
   * every folder (and unfiles trains without a category). Returns the number changed.
   */
  async autoSortTrains(overwrite: boolean): Promise<number> {
    if (MOCK) return 0;
    this.ui.busy = true;
    this.ui.error = '';
    try {
      const dto = await api<{ changed: number }>('/api/train-folders', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ autoSort: true, overwrite }),
      });
      const changed = dto.changed ?? 0;
      await this.refresh(true);
      return changed;
    } catch (e) {
      this.ui.error = e instanceof ApiError ? e.key : 'network error';
      return 0;
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
   * Groups items by folder path, alphabetically, unfiled last, and flattens the tree into
   * render rows. Ancestor headers are synthesized so a lone "A/B/C" still shows its A and B
   * rows; collapsing any ancestor hides the whole subtree. Declared-but-empty folders get a
   * header too — that is what makes them droppable.
   *
   * Rows are depth-first with each folder's direct items after its entire subtree, so an
   * opened folder lists its nested folders on top and its own items underneath them.
   *
   * `items` is expected to already be search/text-filtered by the caller. Pass
   * `searching: true` while a search is active: a folder with nothing matching in it (no
   * direct hit and no matching descendant) is dropped rather than shown empty, and every
   * remaining folder is forced open — a match three folders deep is useless if you still
   * have to expand each ancestor by hand to see it.
   */
  group<T>(
    scope: 'p' | 't',
    items: T[],
    folderOf: (item: T) => string,
    opts?: { searching?: boolean },
  ): FolderRow<T>[] {
    const searching = opts?.searching ?? false;
    const byPath = new Map<string, T[]>();
    for (const item of items) {
      const path = normalizeFolder(folderOf(item));
      const bucket = byPath.get(path);
      if (bucket) bucket.push(item);
      else byPath.set(path, [item]);
    }
    const paths = new Set<string>(byPath.keys());
    // A declared-but-empty folder is a drop target while browsing, but while searching it
    // has nothing that could match, so it would only ever show up as noise.
    if (!searching) for (const declared of this.ui.extra[scope]) if (declared) paths.add(declared);
    // every ancestor of a used path needs a header of its own
    for (const path of [...paths]) {
      const parts = segments(path);
      for (let i = 1; i < parts.length; i++) paths.add(parts.slice(0, i).join('/'));
    }

    const nodes = new Map<string, FolderGroup<T>>();
    const nodeFor = (path: string): FolderGroup<T> => {
      let node = nodes.get(path);
      if (node) return node;
      const parts = segments(path);
      node = {
        path,
        label: parts.length ? parts[parts.length - 1] : 'Unfiled',
        depth: Math.max(0, parts.length - 1),
        items: [],
        collapsed: false,
        hasItems: false,
        hasContent: false,
        children: [],
      };
      nodes.set(path, node);
      return node;
    };

    const roots: FolderGroup<T>[] = [];
    for (const path of paths) {
      const node = nodeFor(path);
      node.items = byPath.get(path) ?? [];
      node.collapsed = searching ? false : this.isCollapsed(scope, path);
      const parts = segments(path);
      const parent = nodeFor(parts.slice(0, parts.length - 1).join('/'));
      if (parent.path === '') roots.push(node);
      else parent.children.push(node);
    }

    const byFullPath = (a: FolderGroup<T>, b: FolderGroup<T>) => {
      if (a.path === '') return 1; // unfiled sinks to the bottom
      if (b.path === '') return -1;
      return a.path.localeCompare(b.path);
    };
    const sortTree = (node: FolderGroup<T>) => {
      node.children.sort(byFullPath);
      for (const child of node.children) sortTree(child);
    };
    roots.sort(byFullPath);
    for (const root of roots) sortTree(root);

    const computeHasItems = (node: FolderGroup<T>): boolean => {
      let anyChild = false;
      for (const child of node.children) if (computeHasItems(child)) anyChild = true;
      node.hasItems = node.path === '' || node.items.length > 0 || anyChild;
      return node.hasItems;
    };
    for (const root of roots) computeHasItems(root);

    // hasItems reflects the filter-passed items (a folder wiped out by the schedule/search
    // filter would otherwise look empty). hasContent reflects what the folder really holds —
    // every filed train for trains, the passed items for presets — so "drag … here" only ever
    // shows on a folder that is genuinely empty, not merely filtered out of view.
    const contentPaths = new Set<string>();
    if (scope === 't') {
      for (const path of Object.values(this.ui.trains)) if (path) contentPaths.add(normalizeFolder(path));
    } else {
      for (const [path, list] of byPath) if (path && list.length) contentPaths.add(path);
    }
    const computeHasContent = (node: FolderGroup<T>): boolean => {
      if (node.path === '') return node.hasContent = true;
      const prefix = node.path + '/';
      let any = false;
      for (const p of contentPaths)
        if (p === node.path || p.startsWith(prefix)) { any = true; break; }
      return node.hasContent = any;
    };
    for (const root of roots) computeHasContent(root);

    const rows: FolderRow<T>[] = [];
    const emit = (node: FolderGroup<T>) => {
      // no direct match and no matching descendant — this folder is a dead end for the search
      if (searching && node.path !== '' && !node.hasItems) return;
      rows.push({
        kind: 'folder',
        path: node.path,
        label: node.label,
        depth: node.depth,
        collapsed: node.collapsed,
        hasItems: node.hasItems,
        hasContent: node.hasContent,
      });
      // a collapsed ancestor swallows its descendants
      if (node.collapsed) return;
      for (const child of node.children) emit(child);
      if (node.items.length)
        rows.push({ kind: 'items', path: node.path, depth: node.depth, items: node.items });
    };
    for (const root of roots) emit(root);
    return rows;
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
