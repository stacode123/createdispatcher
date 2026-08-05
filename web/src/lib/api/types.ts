export interface Me {
  discordId: string;
  username: string;
  tier: 'none' | 'viewer' | 'planner' | 'deployer';
}

/**
 * RailGraph wire shape (the W1 contract; fixtures emit the same).
 * Coordinates are deci-blocks (world blocks × 10, integer).
 * Node/edge indices are per-version transients — never persist them.
 */
export interface RailGraphDto {
  id: string;
  version: number;
  dimensions: string[];
  /** [x, y, z, dim, type] — type: 0 junction, 1 dead end, 2 signal, 3 portal */
  nodes: [number, number, number, number, number][];
  edges: EdgeDto[];
}

export interface EdgeDto {
  from: number;
  to: number;
  /** Reverse-direction twin edge index, or -1. Canonical (drawn) edge: opp < 0 || id < opp. */
  opp: number;
  /** Length in blocks. */
  len: number;
  /** Entry signal: 0 none, 1 entry, 2 chain. */
  sig: 0 | 1 | 2;
  dim: number;
  /** Speed cap in km/h; absent = uncapped. */
  cap?: number;
  /** Inter-dimensional edge — never rendered. */
  xd?: boolean;
  /** Polyline in deci-blocks [x, z], includes both endpoints. */
  shape: [number, number][];
  stations?: StationDto[];
}

export interface GraphIndexEntry {
  id: string;
  version: number;
  tooLarge: boolean;
  microNodes: number;
  builtAgoMs: number;
  edges?: number;
  stations?: number;
  dims: string[];
  /** Per dim index: [minX, minZ, maxX, maxZ] in blocks, or null. */
  bbox: ([number, number, number, number] | null)[];
}

export interface RosterEntry {
  id: string;
  name: string;
  graphId: string;
  length: number;
  carriages: number;
  doubleEnded: boolean;
  state: 'RUNNING' | 'IDLE' | 'PAUSED' | 'COMPLETED' | 'MANUAL' | 'DERAILED';
  scheduleTitle: string;
  destination: string;
  currentStation: string;
  /** Owning player's name — '' when the train has no owner, or none is known. */
  owner: string;
}

export interface HelloEvent {
  serverTick: number;
  serverWallMs: number;
  dayTime: number;
  dayTimeRate: number;
  rosterVersion: number;
  tier: string;
  graphs: Record<string, number>;
}

export interface WebNotification {
  id: string;
  kind: 'SIGNAL_WAIT' | 'DEADLOCK' | 'DETOUR';
  severity: 'WARN' | 'CRITICAL';
  state: 'ACTIVE' | 'RESOLVED';
  message: string;
  trains: { id: string; name: string }[];
  graphId: string | null;
  x: number;
  z: number;
  dim: string;
  sinceTick: number;
  updatedTick: number;
  resolvedTick?: number;
  data: Record<string, unknown>;
}

export interface ReplayIndexEntry {
  id: string;
  notificationId: string;
  kind: WebNotification['kind'];
  message: string;
  createdMs: number;
  graphId: string;
  graphVersion: number;
  dim: string;
  durationTicks: number;
  trains: number;
}

export interface ReplayData {
  id: string;
  notificationId: string;
  kind: WebNotification['kind'];
  message: string;
  graphId: string;
  graphVersion: number;
  dim: string;
  x: number;
  z: number;
  startTick: number;
  /** Tick the issue was detected at (the marker on the scrub bar). */
  eventTick?: number;
  endTick: number;
  cadenceTicks: number;
  involved: string[];
  /** The triggering notification's data payload (e.g. detour route edge lists). */
  eventData?: Record<string, unknown>;
  trains: { id: string; name: string; s: [number, number, number, number][] }[];
}

/** Route overlays inside notification/replay data — edge ids valid only for routesGraphVersion. */
export interface RouteSource {
  routedEdges?: number[];
  directEdges?: number[];
  routesGraphVersion?: number;
}

/** Logical station groups of one graph (server-side CRN-tag/platform-number grouping). */
export interface StationGroupsDto {
  graphId: string;
  graphVersion: number;
  groups: { name: string; platforms: { name: string; x: number; z: number; dim: number }[] }[];
}

/** One train's corridor polyline: segments of [gameTick|simTick, corridorPos]. */
export interface CorridorLineDto {
  id: string;
  name: string;
  segs: [number, number][][];
  /** Plan lines only: this train's own drift estimate (falls back to the plan's `cal`). */
  cal?: PlanCalibrationDto | null;
}

export interface CorridorActualDto {
  graphId: string;
  graphVersion: number;
  from: string;
  to: string;
  length: number;
  nowTick: number;
  sinceTick: number;
  /** The corridor's route as collapsed edge ids (valid for graphVersion only). */
  path: number[];
  /** [groupName, corridorPos] ascending. */
  stations: [string, number][];
  lines: CorridorLineDto[];
  dropped: number;
}

/** Plan overlay: seg ticks are sim-relative — shift by baseTick for the shared axis. */
export interface PlanCalibrationDto {
  /** Measured drift rate: reality ≈ plan time × (1 + stretch). */
  stretch: number;
  /** 1-sigma uncertainty growth, in ticks per minute of horizon. */
  sigmaPerMin: number;
  /** Evidence count behind the estimate. */
  n: number;
}

export interface CorridorPlanDto {
  pending?: boolean;
  graphId: string;
  graphVersion: number;
  from: string;
  to: string;
  baseTick: number;
  startDayTime: number;
  dayTimeRate: number;
  ticksSimulated: number;
  truncated: boolean;
  ageSeconds: number;
  stale: boolean;
  cal: PlanCalibrationDto | null;
  lines: CorridorLineDto[];
  dropped: number;
}

/** Preset library (W4). */
export interface PresetSummaryDto {
  id: string;
  name: string;
  /** Slash-separated organizing path; "" = unfiled. */
  folder: string;
  source: string;
  createdMs: number;
  updatedMs: number;
  entries: number;
}

export interface PresetFieldSpec {
  key: string;
  type: 'int' | 'string';
  min?: number;
  max?: number;
}

/** One instruction or condition: id + current field values + what may be edited. */
export interface PresetNodeDto {
  id: string;
  fields: Record<string, string | number>;
  editable: PresetFieldSpec[];
}

export interface PresetEntryDto {
  instruction: PresetNodeDto;
  conditions: PresetNodeDto[][];
}

export interface PresetDetailDto extends PresetSummaryDto {
  cyclic: boolean;
  schedule: PresetEntryDto[];
}

export interface TrainsEvent {
  tick: number;
  wallMs: number;
  dayTime: number;
  rate: number;
  full: boolean;
  g: Record<string, number>;
  /** [trainUuid, graphUuid, edgeId, offsetBlocks, speedBlocksPerTick, dim] */
  p: [string, string, number, number, number, number][];
}

export interface StationDto {
  id: string;
  name: string;
  /** Offset in blocks from the edge start. */
  off: number;
  ap: boolean;
}

/** Sim playback data — SimDebugExporter-shaped subset. */
export interface SimRunDto {
  meta: { start: number; rate: number; ticks: number; stride: number };
  trains: SimTrainDto[];
}

export interface SimTrainDto {
  n: string;
  /** Stable train UUID (planner sim results; fixtures omit it). */
  id?: string;
  p?: boolean;
  o?: boolean | number;
  /** 1 = this train ran an assigned (overridden) schedule. */
  a?: number;
  len: number;
  ts: number;
  end: string;
  /** Samples [tick, edgeId, offsetBlocks, speed], tick-ascending. */
  s: [number, number, number, number][];
  /** Every edge the head entered, in traversal order. */
  path: number[];
  /** Visits [arrivalTick, departureTick, stationName]. */
  v: [number, number, string][];
  /** Destination filters navigation could not route to. */
  fail?: string[];
}

/** Planner sims (W5). */
export interface SimEventDto {
  simId: string;
  state: 'queued' | 'preparing' | 'running' | 'done' | 'failed' | 'cancelled';
  graphId: string;
  graphVersion: number;
  progressTicks: number;
  horizonTicks: number;
  queuePos: number;
  createdMs: number;
  error?: string;
  /** [trainName, translationKey, detail] — assignments the sim ran without. */
  issues?: [string, string, string][];
}

export interface SimResultDto extends SimRunDto {
  meta: SimRunDto['meta'] & {
    graphId: string;
    graphVersion: number;
    baseTick: number;
    truncated: boolean;
  };
  /** Names of live trains the planner took off the network for this run. */
  removed: string[];
  /** [trainName, translationKey, detail]. */
  excluded: [string, string, string][];
  overrideIssues: [string, string, string][];
  /** [typeOrdinal, start, end, count, x, z, dim, resource, [trainIdx...], nonDet]. */
  conflicts: [number, number, number, number, number, number, number, string, number[], number][];
  /** [rootIdx, kind, detail, [strandedIdx...], sinceTick, x, z, dim]. */
  rootCauses: [number, string, string, number[], number, number, number, number][];
}

/** Saved planned timetables (W5). */
export interface PlanTrainRef {
  trainId: string;
  trainName: string;
}

export interface PlanAssignmentDto extends PlanTrainRef {
  presetId: string;
  presetName: string;
}

export interface PlanSummaryDto {
  id: string;
  name: string;
  author: string;
  createdMs: number;
  updatedMs: number;
  graphId: string;
  removeScheduled: boolean;
  /** Counts in summaries; the full plan carries the rows themselves. */
  assignments: number;
  keeps: number;
  removals: number;
}

export interface PlanDto {
  id: string;
  name: string;
  author: string;
  createdMs: number;
  updatedMs: number;
  graphId: string;
  removeScheduled: boolean;
  horizonHours: number;
  headwaySeconds: number;
  startTime: string;
  assignments: PlanAssignmentDto[];
  keeps: PlanTrainRef[];
  removals: PlanTrainRef[];
}

export interface SimDiagramDto {
  simId: string;
  graphId: string;
  graphVersion: number;
  from: string;
  to: string;
  length: number;
  start: number;
  rate: number;
  ticks: number;
  /** [groupName, corridorPos] ascending. */
  stations: [string, number][];
  /** Seg ticks are sim-relative. */
  lines: { id: string; name: string; segs: [number, number][][] }[];
  dropped: number;
}

/** One train's outcome from POST /api/deploy. */
export interface DeployResultDto {
  trainId: string;
  train: string;
  ok: boolean;
  /** Refusal key when !ok: not_found | derailed | not_idle | preset_invalid | empty | error. */
  reason?: string;
  /** Simulator-compile complaints; the schedule was still installed. */
  notices?: string[];
}

export interface DeployResponseDto {
  mode: 'IMMEDIATE' | 'IDLE_ONLY';
  applied: number;
  skipped: number;
  results: DeployResultDto[];
}

/** A line of the deploy journal (GET /api/audit), newest first. */
export interface AuditEntryDto {
  ts: number;
  gameTick: number;
  user: string;
  discordId: string;
  mode: string;
  trainId: string;
  train: string;
  presetId: string;
  preset: string;
  ok: boolean;
  reason?: string;
  notices?: string[];
}

/** The `deployed` SSE event — someone else changed the running timetable. */
export interface DeployedEventDto {
  user: string;
  mode: string;
  applied: number;
  skipped: number;
}
