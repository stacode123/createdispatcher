import { api } from '../api/http';
import type { RosterEntry, TrainsEvent } from '../api/types';
import { posAlongEdge } from '../map/geometry';
import { graphStore } from './graphs.svelte';
import { focusMap } from './mapFocus.svelte';

export interface LiveSample {
  tick: number;
  graphId: string;
  edge: number;
  off: number;
  speed: number;
  dim: number;
}

const RING_CAP = 12;
const focusPoint: [number, number] = [0, 0];

/**
 * Live train state. Hot path (per-train sample rings, read by the rAF loop) is plain;
 * roster and selection are runes. Delta frames merge; full frames also drop vanished trains.
 * Rings are pruned when their graph's version changes — old edge ids never interpolate.
 */
class LiveTrainsStore {
  rings = new Map<string, LiveSample[]>();
  /** Graph versions the latest frame's edge ids belong to — render only on match. */
  frameVersions: Record<string, number> = {};
  nameById = new Map<string, string>();

  ui = $state({
    roster: [] as RosterEntry[],
    rosterVersion: -1,
    positionCount: 0,
    selected: null as string | null,
    follow: false,
  });

  applyTrains(event: TrainsEvent) {
    // graph version changed → every ring on that graph holds stale edge ids
    for (const [graphId, version] of Object.entries(event.g)) {
      const previous = this.frameVersions[graphId];
      if (previous !== undefined && previous !== version) {
        for (const [id, ring] of this.rings)
          if (ring.length && ring[ring.length - 1].graphId === graphId) this.rings.delete(id);
      }
    }
    this.frameVersions = event.g;

    if (event.full) {
      const seen = new Set<string>();
      for (const p of event.p) seen.add(p[0]);
      for (const id of [...this.rings.keys()]) if (!seen.has(id)) this.rings.delete(id);
    }
    for (const [id, graphId, edge, off, speed, dim] of event.p) {
      let ring = this.rings.get(id);
      if (!ring) this.rings.set(id, (ring = []));
      const last = ring[ring.length - 1];
      if (!last || event.tick > last.tick) {
        ring.push({ tick: event.tick, graphId, edge, off, speed, dim });
        if (ring.length > RING_CAP) ring.shift();
      }
    }
    this.ui.positionCount = this.rings.size;
  }

  newest(id: string): LiveSample | undefined {
    const ring = this.rings.get(id);
    return ring ? ring[ring.length - 1] : undefined;
  }

  private rosterFetchAt = 0;
  private rosterTimer: ReturnType<typeof setTimeout> | null = null;

  /**
   * Trailing-throttled: on busy servers the roster version bumps every few seconds (some train
   * always changes destination), and a 200+ KB refetch per bump melts the UI. At most one
   * fetch per 5 s; bursts coalesce into one trailing refresh.
   */
  refreshRoster() {
    const now = Date.now();
    const wait = Math.max(0, this.rosterFetchAt + 5000 - now);
    if (this.rosterTimer) return;
    this.rosterTimer = setTimeout(() => {
      this.rosterTimer = null;
      this.rosterFetchAt = Date.now();
      api<{ rosterVersion: number; trains: RosterEntry[] }>('/api/trains')
        .then((response) => this.setRoster(response.trains, response.rosterVersion))
        .catch(() => {});
    }, wait);
  }

  setRoster(roster: RosterEntry[], version: number) {
    this.ui.roster = roster;
    this.ui.rosterVersion = version;
    this.nameById = new Map(roster.map((t) => [t.id, t.name]));
    if (this.ui.selected && !this.nameById.has(this.ui.selected)) {
      this.ui.selected = null;
      this.ui.follow = false;
    }
  }

  select(id: string | null, follow = false) {
    this.ui.selected = id;
    this.ui.follow = follow && id != null;
  }

  /**
   * Select a train and jump the camera to its newest known position, switching dimension if
   * it is running in another one. Returns false when there is nothing to jump to — the train
   * is in the roster but has no usable sample (never reported, or its graph reloaded under
   * it). Selection still happens, so the info panel fills in once positions arrive.
   *
   * Follows by default, unlike a map click: the jump zooms in from network scale onto a train
   * doing 100 km/h, and a fixed camera there would lose it again within a second. One drag of
   * the map (or the info panel's Unfollow) releases it.
   */
  focus(id: string, follow = true): boolean {
    this.select(id);
    const sample = this.newest(id);
    if (!sample) return false;
    const g = graphStore.byId.get(sample.graphId);
    if (!g || this.frameVersions[sample.graphId] !== g.version) return false; // stale edge ids
    this.ui.follow = follow; // only once there is something to follow
    posAlongEdge(g, sample.edge, sample.off, focusPoint);
    focusMap(focusPoint[0], focusPoint[1], g.dims[sample.dim] ?? g.dims[0] ?? '');
    return true;
  }

  clear() {
    this.rings.clear();
    this.ui.positionCount = 0;
  }
}

export const liveTrains = new LiveTrainsStore();
