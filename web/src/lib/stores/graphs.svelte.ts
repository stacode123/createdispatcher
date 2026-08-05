import { api } from '../api/http';
import type { GraphIndexEntry, RailGraphDto } from '../api/types';
import type { LoadedGraph } from '../map/geometry';

// $state.raw: LoadedGraph holds typed arrays — deep reactivity would be pure overhead.
let byId = $state.raw<Map<string, LoadedGraph>>(new Map());
let graphs = $state.raw<LoadedGraph[]>([]);
let dims = $state.raw<string[]>([]);
let status = $state<'empty' | 'loading' | 'ready'>('empty');
let generation = $state(0);

const inflight = new Set<string>();
let indexInflight = false;
let indexDebounce: ReturnType<typeof setTimeout> | null = null;

// ONE shared worker with a sequential queue — servers can have thousands of fragment graphs,
// and a worker spawn per graph costs more than the build itself.
let sharedWorker: Worker | null = null;
let workerQueue: Promise<unknown> = Promise.resolve();

function buildInWorker(dto: RailGraphDto): Promise<LoadedGraph> {
  const run = () =>
    new Promise<LoadedGraph>((resolve, reject) => {
      if (!sharedWorker) sharedWorker = new Worker(new URL('../map/worker.ts', import.meta.url), { type: 'module' });
      const worker = sharedWorker;
      worker.onmessage = (event) => resolve(event.data as LoadedGraph);
      worker.onerror = (event) => {
        worker.terminate();
        sharedWorker = null;
        reject(new Error(event.message));
      };
      worker.postMessage(dto);
    });
  const next = workerQueue.then(run, run);
  workerQueue = next.catch(() => {});
  return next;
}

async function fetchAndBuild(id: string, version: number): Promise<LoadedGraph | null> {
  try {
    const dto = await api<RailGraphDto>(`/api/graphs/${id}`);
    // The body deliberately carries no version (the server keeps versions stable across no-op
    // rebuilds by byte-comparing content) — the index/event version is the authority.
    dto.version = version;
    return await buildInWorker(dto);
  } catch {
    return null;
  }
}

/** Run tasks with bounded parallelism — hundreds of tiny graphs must not stampede the connection pool. */
async function pooled<T>(items: T[], limit: number, run: (item: T) => Promise<void>) {
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) await run(items[next++]);
  });
  await Promise.all(workers);
}

function publish(map: Map<string, LoadedGraph>) {
  byId = map;
  graphs = [...map.values()];
  const union: string[] = [];
  for (const graph of graphs)
    for (const dim of graph.dims) if (!union.includes(dim)) union.push(dim);
  dims = union;
  generation++;
  status = 'ready';
}

export const graphStore = {
  get graphs() {
    return graphs;
  },
  get byId() {
    return byId;
  },
  get dims() {
    return dims;
  },
  get status() {
    return status;
  },
  get generation() {
    return generation;
  },

  /** Fixture/mock path — build directly from DTOs. */
  async load(dtos: RailGraphDto[]) {
    status = 'loading';
    const map = new Map<string, LoadedGraph>();
    for (const dto of dtos) map.set(dto.id, await buildInWorker(dto));
    publish(map);
  },

  /**
   * Full sync against /api/graphs: fetch new/updated (biggest first so the main network
   * renders immediately, published incrementally), drop vanished. Bounded to 6 parallel
   * fetches — big servers can have hundreds of tiny track fragments.
   */
  async loadReal() {
    if (indexInflight) return;
    indexInflight = true;
    try {
      if (!byId.size) status = 'loading';
      const index = await api<{ graphs: GraphIndexEntry[] }>('/api/graphs');
      const wanted = index.graphs
        .filter((g) => !g.tooLarge)
        .sort((a, b) => (b.edges ?? 0) - (a.edges ?? 0));
      const map = new Map(byId);
      let dirty = false;
      const liveIds = new Set(wanted.map((g) => g.id));
      for (const id of [...map.keys()])
        if (!liveIds.has(id)) {
          map.delete(id);
          dirty = true;
        }
      let sincePublish = 0;
      await pooled(wanted, 6, async (entry) => {
        const loaded = map.get(entry.id);
        if (loaded && loaded.version >= entry.version) return;
        const built = await fetchAndBuild(entry.id, entry.version);
        if (!built) return;
        map.set(entry.id, built);
        dirty = true;
        // first (biggest) graph shows immediately; the fragment tail lands in large batches —
        // every publish invalidates the static raster, so keep them rare
        if (++sincePublish === 1 || sincePublish % 200 === 0) publish(new Map(map));
      });
      if (dirty || status !== 'ready') publish(map);
    } catch {
      if (!byId.size) status = 'ready';
    } finally {
      indexInflight = false;
    }
  },

  /** Trailing-debounced loadReal for bursty graphIndex events. */
  loadRealSoon() {
    if (indexDebounce) clearTimeout(indexDebounce);
    indexDebounce = setTimeout(() => {
      indexDebounce = null;
      this.loadReal();
    }, 2000);
  },

  /** Version handshake from hello/trains events — refetch anything stale. */
  reconcile(versions: Record<string, number>) {
    for (const [id, version] of Object.entries(versions)) {
      const loaded = byId.get(id);
      if (!loaded) this.loadRealSoon();
      else if (loaded.version < version) this.applyVersion(id, version);
    }
  },

  /** Debounced single-graph refetch (track edits arrive in bursts). */
  applyVersion(id: string, version: number) {
    const loaded = byId.get(id);
    if ((loaded && loaded.version >= version) || inflight.has(id)) return;
    inflight.add(id);
    setTimeout(async () => {
      try {
        const built = await fetchAndBuild(id, version);
        if (built) {
          const map = new Map(byId);
          map.set(id, built);
          publish(map);
        }
      } finally {
        inflight.delete(id);
      }
    }, 1500);
  },
};
