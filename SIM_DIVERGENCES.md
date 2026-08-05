# Simulator vs Create Runtime — Divergence Audit (2026-08-04)

Fresh investigation, previous conclusions discarded. Ground truth: Create 6.0.8.1 (create-fabric
1.20.1 decompiled sources) **as modified by** this mod's mixins (`TrainMixin`,
`ScheduleRuntimeMixin`, `TimeOfDayRealistic`) and Tramways 0.3.2 (including its `NavigationMixin` /
`TrainMixin`). Five subsystems audited: movement physics, signals/occupancy, schedule execution,
topology/speed limits, pathfinding. Only findings that change predicted times, positions, routes,
ordering, or deadlocks are listed.

Legend: **RT** = runtime behavior, **SIM** = simulator behavior.

**Fix status (2026-08-04):** implemented —
- H1 (repath gating: chain-only, %100==50 phase, suppressed while holding chain claims), H3
  (no reverse on repath, back-wins tie-break, backward-conductor gating), M4 (forced-red:
  scan red + 400 path penalty), covered by `RepathGatingTest`.
- **M2** tick order: signal-waiting trains tick before moving trains, longest-waiting first,
  with Create's two-list migration (`SimEngine.migrateTickOrder`; `SignalClaimPriorityTest`
  rewritten around this — the old persistent-lease semantics it pinned were themselves a
  divergence).
- **M3** reservations now match Create's model exactly: per-tick transient reservations
  (cleared each tick, lapse when the owner stops re-asserting) + persistent per-train chain
  reservations (`TrainState.chainClaims`, released on crossing/navigation restart, no 32-edge
  budget). This also closes L11 (claim-steal window).
- **M5** red-signal path weight scales with the searcher's wait: `clamp(waitTicks*2, 25, 200)`
  (`Penalties.redSignalWeight`). Create's per-search halving across successive occupied groups
  is expansion-order dependent and deliberately not modeled.
- **M9** passive slowdown: destination-less trains coast `v²/2a`, decaying by accel/tick,
  following unique continuations (ambiguous switches stop them — conservative).
- **M10** degree-2 kink nodes are now cut by the translator (`TrackEdge.canTravelTo` test),
  exposing impassable joins to the sim's transition legality.
- **M12** full glob support (`?`, `[...]`, `[!...]`, `{a,b}`, `\` escapes) in `SimGlob`,
  with literal-star fallback for malformed globs.
- **M14 + L4** Tramways sign demands execute per-tick in-window: TEMPORARY applies within
  `|v²−u²|/2a` (raises AND reductions, early), PERMANENT within braking displacement when
  reducing; mutations survive reroutes; crossing re-fire is idempotent.
- **L3** penalty classes: MANUAL=200 (`spec.manual`), ARRIVING=50 (`distanceToTarget<50` or
  signal within 20), dwelling trains now ANY=25 as in Create.
- **L8** train length = Σ bogey + carriage spacings (dropped the +2 pad).
- **L9** dispatch and first movement now share a tick (Create: runtime.tick before
  navigation.tick), same-station destinations resolve same-tick, and the failed-nav cooldown
  is seeded from the snapshot (`ScheduleRuntimeAccessor`).

Open: H2 (mid-transit re-navigation), M11 (emulating Create's approximate A*), L5 (bezier
t-parameterization drift — the sim already uses Create's own `getLength`/`getLocationOn`
units; reproducing the runtime's first-order `incrementT` integration error would require
carrying bezier control points into the MC-free core for sub-second gains), L10 (chunk
stalls/collisions — unknowable to a snapshot).

---

## High severity

### H1. Repathing at red signals: sim repaths where Create never does *(confirmed by 2 independent audits)*
- **RT**: a train pinned at a red repaths only when the blocking signal is a **CROSS (chain) signal**, at `ticksWaitingForSignal % 100 == 50`, and **never while `reservedSignalBlocks` is non-empty** (`Train.java:528-547`). A train at a plain **entry** signal keeps its route forever — entry-signal deadlocks are permanent in-game.
- **SIM**: every signal-waiting train repaths every 100 ticks regardless of signal type or held claims (`SimEngine.java:30, 817-819, 1004-1016`).
- **Impact**: the sim dissolves exactly the deadlocks it is supposed to predict. `ConflictDetector` assumes "a real deadlock never resolves in-sim" (`ConflictDetector.java:11-13`) — the engine's repath violates that assumption.

### H2. No mid-transit re-navigation / dynamic platform retargeting *(confirmed by 2 audits)*
- **RT**: each time distance-to-destination crosses a 100-block boundary (50 near), Create re-runs `startCurrentInstruction` — re-resolving the station glob with fresh penalties. Moving trains switch to a platform that frees up mid-run and divert around new congestion (`Train.java:507-547`, `ScheduleRuntime.java:190`).
- **SIM**: route and platform fixed at dispatch; reconsidered only once stopped at a red (`SimEngine.java:482-488`).
- **Impact**: wrong platform assignment and arrival ordering on any multi-platform (glob) destination.

### H3. Sim repath can physically reverse the train
- **RT**: `findPathTo` skips the reversing direction while a destination is active ("avoid reversing out of path", `Navigation.java:457-459`); backward travel additionally requires `doubleEnded && hasBackwardConductor` (`:507-508`); on a cost tie the **back** path wins (`:515-516`).
- **SIM**: `repath()` offers the reverse start whenever `spec.canReverse` and flips the train if it wins; `canReverse` = `doubleEnded` only, no conductor check; **forward** wins ties (`SimEngine.java:442-451, 479-480, 1004-1016`; `SimPathfinder.java:68-69`; `NetworkSnapshotter.java:138`).
- **Impact**: sim trains reverse out of situations real trains wait in; symmetric layouts pick opposite directions.

### H4. `TimeOfDayRealistic` (this mod's own condition) is mis-modeled — errors up to a full day
Three related defects:
1. **Day anchor**: RT computes the target day from the **dispatch-time ETA** (`expectedArrivalDate` set by `ScheduleRuntimeMixin.java:154-180`; `TimeOfDayRealistic.java:39-58`); SIM uses the **actual current day** at evaluation (`SimCondition.java:48-63`). Whenever predicted and actual arrival fall on different days, the two systems wait for different targets (real train may depart immediately where sim waits ~2000+ ticks, or vice versa — up to 24000 ticks).
2. **Daily-departure branch missing**: RT's `diff < 0 && |departureDate − gameTime| < 10` branch (`TimeOfDayRealistic.java:46-51`) makes "depart daily at HH:MM from here" loop once per day; SIM's wrap test (`SimCondition.java:55`) fails after the first cycle → sim departs every cycle instantly.
3. **`lastScheduledDepartureDate` not seeded** from the runtime NBT at snapshot (`NetworkSnapshotter.java:158-171` has no field for it; `TrainState.java:76` starts at 0) → first post-snapshot midnight-wrap departure off by up to a day.

### H5. Unsupported schedule content turns whole trains into tick-0 static obstacles
- **RT**: unknown instruction ids parse as an empty `DestinationInstruction` — the train **runs all earlier entries normally**, then stalls at the unknown entry retrying every 40 ticks (`ScheduleInstruction.java:44-48`). Unsupported *conditions* in an OR column don't stop anything if a sibling column (e.g. a Delay timeout) completes — cargo trains with "item threshold OR 2-min timeout" run on a bounded dwell (`ScheduleRuntime.java:163-184`).
- **SIM**: any unsupported instruction/condition anywhere in the schedule → compile Problem → the entire train becomes `Mode.OBSTACLE` frozen at its snapshot position (`ScheduleCompiler.java:105-108, 175-177`; `NetworkSnapshotter.java:101-105`).
- **Impact**: every visit before the unsupported entry is lost, and the frozen train is a phantom permanent blocker for everything routed near it. Affects `create:package_delivery`/`package_retrieval` (present in Create 6.0.8 but not in the compiler switch) and all cargo-condition schedules.

---

## Medium severity

### M1. Fuel dynamics frozen at snapshot
`maxSpeed()/maxTurnSpeed()/acceleration()` switch between powered/unpowered stats on `fuelTicks > 0` **every tick** (`Train.java:1107-1120`) and fuel burns only while navigating. The snapshotter patches top/turn speed up via observed floors, but passes `train.acceleration()` through uncorrected (`NetworkSnapshotter.java:125-137`) — a fueled train snapshotted between burns keeps unpowered acceleration for the whole horizon; mid-run fuel exhaustion is never modeled.

### M2. Queue wake order at freed sections
- **RT**: waiting trains tick before moving trains, ordered by when they started waiting — longest-waiting wins a freed section; contention is re-fought every tick because `group.trains`/`reserved` are cleared and re-asserted each tick (`GlobalRailwayManager.java:192-195, 218-259`).
- **SIM**: fixed tick order sorted by train UUID string (`NetworkSnapshotter.java:51`) with persistent first-come claims (`SimEngine.java:59-70, 219-221`).
- **Impact**: reorders departures/junction transits in any congested network.

### M3. Reservation lifetime diverges in both directions
- Non-chain: RT reservations are per-tick — they lapse the moment the owner's braking distance no longer reaches the boundary (owner brakes for a turn/nearer red), letting another train take the section (`Navigation.java:179-181`). SIM refreshes committed claims via a 32-edge beyond-scan walk, so the slowing owner keeps them (`SimEngine.java:917-940`). Opposite orderings through single-track segments.
- Chain: RT chain reservations persist unconditionally until crossed or navigation restarts (`Train.java:186, 483-488`). SIM chain claims stretching >32 governed edges past braking distance silently expire and can be stolen (`SimEngine.java:917-924`).

### M4. Forced-red (redstone-powered) signals entirely unmodeled *(2 audits)*
RT: powered signal = red regardless of occupancy (`SignalBoundary.java:210-221`, `Navigation.java:164, 320-323`) plus a 400 pathfinding penalty (`Navigation.java:629-631`). SIM: the translator reads `blockEntities`/`types` but drops the stored power boolean (`RailGraphTranslator.java:258-269`); no concept in `SimEdge`/`SimPathfinder`. Any layout using redstone dispatching/interlocking predicts wrong.

### M5. Red-signal path penalty: flat 25 vs wait-scaled decaying 25–200 *(2 audits)*
RT: `clamp(ticksWaitingForSignal*2, 25, 200)`, halved per successive occupied group in one search (`Navigation.java:613, 640-643, 739-742`) — long-waiting trains increasingly prefer detours. SIM: constant 25 (`SimPathfinder.java:24, 370-376`). Detours >25 blocks longer than a blocked route are never taken in sim even when the real train would divert.

### M6. Station auto-schedules ignored
RT: arrival at an auto-schedule station **replaces the whole program** (`StationBlockEntity.java:260, 282-283` → `ScheduleRuntime.setSchedule`). SIM: no reference anywhere; the compiled snapshot program runs unchanged forever. Silent — no notice emitted.

### M7. Conductor requirements ignored
RT: no conductor → navigation cancels / destination instruction never starts (`Navigation.java:77-96`, `DestinationInstruction.java:93-97`); backward path needs a backward conductor. SIM: any train with a compiled program moves; reverse gated on `doubleEnded` only.

### M8. Tramways request stops *(3 audits)*
RT: unrequested stops are rolled through — Tramways' `NavigationMixin` cancels the route inside braking distance, schedule advances, tram keeps speed (`NavigationMixin.java:42-51, 75-111`). SIM: `tramways:request_stop` compiles as an ordinary destination — full brake + dwell every time (`ScheduleCompiler.java:81-88`; has a notice, but times still shift by a stop-cycle per stop).

### M9. Passive-slowdown coasting missing
RT: a moving train whose navigation ends/fails coasts forward v²/2a blocks while decaying speed by `a`/tick (`Train.java:554-563`) — up to ~150 blocks with realism-reduced acceleration. SIM: PRE_TRANSIT/path-fail trains freeze in place (`SimEngine.java:408-438`). Occupancy seen by followers diverges.

### M10. Degree-2 "kink" nodes merged without turn-legality check
RT gates *every* node transition on the 7/8-dot test (`TrackEdge.canTravelTo`, enforced in `TravellingPoint.java:240-250` and `Navigation.java:694-699`) — a 45° join at a plain degree-2 node severs the network. SIM: `RailGraphTranslator.walk()` (`:190-225`) merges degree-2 chains with no angle check at interior nodes; the dot test applies only at collapsed-edge boundaries (`SimGraph.java:64-83`). Sim routes and drives through impassable track.

### M11. Exact Dijkstra vs Create's approximate A*
Create's search is expansion-order dependent: octile heuristic, first-station-hit termination, no reopening, first-recorded predecessor (`Navigation.java:684-690, 749-798, 833-836`) — it can settle a suboptimal platform. SIM is exact minimum-cost Dijkstra with deterministic ties (`SimPathfinder.java:220-346`). On near-ties Create may pick the costlier platform the sim never would; occupancy chains diverge from there.

### M12. Glob matching supports only `*`
RT uses catnip `Glob.toRegexPattern` — full glob (`?`, `[...]`, `{a,b}`, escapes) (`DestinationInstruction.java:57-59`). SIM: `SimGlob.java:12-30` translates `*` only, everything else literal. `Platform ?` matches nine stations in-game, zero in sim → sim predicts a stall that doesn't happen.

### M13. Track-material (gauge) reachability filter missing
RT restricts paths to track types valid for every bogey (`Navigation.java:556-569, 705-707`). SIM has none — relevant once Steam 'n' Rails-style mixed gauges are in play; sim can declare reachable what is unreachable.

### M14. Tramways temporary sign that *raises* the limit applies early in-game
`TemporarySpeedSignDemand.execute` fires when `distance ≤ |s|`, `s=(v²−u²)/2a`, with no lower-than check (`TemporarySpeedSignDemand.java:36-57`) — the raise lands `(v²−u²)/2a` blocks before the sign and the real train accelerates early. SIM fires raises only at crossing (`SimEngine.java:1051-1093`).

---

## Low severity (compact)

- **L1** Station penalty details: RT skips the searcher's own station and charges every station point; SIM charges approachable platforms only, uses ±3-block presence, includes own platform (`SimEngine.java:170-192, 503-520` vs `Navigation.java:645-655, 744-756`). Flips near-tie choices.
- **L2** Own-occupancy in search: RT's `isOccupiedUnless(SignalBoundary)` counts the searching train's own presence; SIM exempts self (`SimEngine.java:383-393`) — loops through one's own tail are 25 cheaper in sim.
- **L3** ARRIVING(50)/MANUAL(200) penalty classes missing; sim maps moving→25, dwelling→50 (`SimEngine.java:616-625` vs `Train.java:1054-1069`).
- **L4** Permanent slow sign mutates `train.throttle` pre-crossing and survives reroute/abort in RT (`SpeedSignDemand.java:52-79`, tail-pass cleanup `TrainMixin.java:113-125`); SIM mutates only at crossing.
- **L5** Bezier parameterization: RT advances `t × length` (first-order, Create's own FIXME `TravellingPoint.java:214-220`); SIM treats sub-edge offsets as exact arc length — sub-second shifts per asymmetric curve.
- **L6** `GlobalStation.assembling` ignored — sim routes to stations in assembly mode (`GlobalStation.java:116-118` vs `RailGraphTranslator.java:272`).
- **L7** Span-boundary artifacts: station exactly at a cut lands on both spans (double-counted targets/penalties, `RailGraphTranslator.java:346`); a sign at offset 0 of a walk-start span can never fire (`SimEngine.java:1085-1093` fires `(from,to]`).
- **L8** Train length padded +2 blocks (`NetworkSnapshotter.java:78-82`) — sections release ~2 blocks late systematically.
- **L9** Quantization: 1-tick dispatch offset for resumed trains; same-station destination costs 1-2 extra ticks; snapshot mid-cooldown restarts with `cooldown=0` (≤40 ticks early).
- **L10** Stall/chunk-load/collision/derail dynamics absent — includes physical collisions on **unsignalled** shared track (RT: both trains crash permanently, `Train.java:584-614`; SIM: trains pass through each other). Intentional scope, but predictions are void where it applies.
- **L11** Claim-steal window: sim claims persist one extra tick, first claimant can't be out-prioritized on simultaneous approach (documented design divergence at `SimEngine.java:59-67`).
- **L12** `tramways:set_primary_limit` value clamped to 1.0 in compiler (NBT-edited >100 diverges); invented 0.05 throttle floor in snapshotter — both practically unreachable via GUI.
- **L13** Compiler's null-condition skip assumes Create drops unparseable conditions; Create likely NPEs instead (mod-removal edge case, unverified).
- **L14** `TramwaysCompat.getSignZoneKmh` counts `AdvanceWarningAuxSignDemand` (a no-op in Tramways) toward `SimEdge.signCap` and uses a global rather than per-train max — currently harmless because the engine never reads `signCap` (display only); flagged in case it gets wired into movement.

---

## Verified faithful (checked line-by-line, no action needed)

- **Movement integration**: bang-bang control, v²/2a braking, ±a/tick with clamp, decide→move→subtract tick order, `brakingDistance + 3 − bd%3` no-flicker quirk, +0.25 overshoot target, 1/32 arrival snap, <10-block taper using **unthrottled** top speed with 0.5 exponential pursuit, 4.5-block pre-departure hold — all bit-for-bit (`SimEngine.java:788-887` ≡ `Navigation.java:100-290`).
- **Modded acceleration**: the snapshotter calls `train.acceleration()`, which resolves to this mod's `TrainMixin` overwrite — Realistic/Custom/None modes, carriage penalty, locomotive offset, floor, global toggle all captured.
- **Turn model**: exact transcription of the curve listener incl. straight-mild-slope exemption (`RailGraphTranslator.java:561-575` ≡ `Navigation.java:206-224`); head-only enforcement; `turnTop = min(top·throttle, maxTurnSpeed)`; lookahead restriction proven equivalent.
- **Signal section model**: sections = edge components cut at boundaries ≡ signal edge groups (not per-edge locks); diamond-crossing transitive closure ≡ `intersectingResolved` walk; occupied-unless semantics; tail-crossing release; one-way `canNavigateVia`.
- **Chain signals**: chain collected through the terminating entry signal's group inclusive, atomic all-free reservation, wait posted at first signal, chains followed beyond scan distance (`SimEngine.java:907-988` ≡ `Navigation.java:139-227, 292-300`).
- **Schedule mechanics**: conditions polled every tick (the 40-tick INTERVAL is only failed-nav retry — both); OR-columns/AND-rows incl. one-tick-late completion; ScheduledDelay exact counts; vanilla TimeOfDayCondition math identical incl. rotation table and [0,40] window; end-of-schedule wrap/park; one instruction per tick; mid-dwell `conditionProgress` seeding.
- **CRN `train_separation`**: bytecode-verified instant-column + delayed departure-gate model matches.
- **Tramways lower limits**: anticipatory braking `(u²−v²)/2a`, TEMPORARY stash / RELEASE restore / PERMANENT-updates-stash, same-offset ordering, `primaryLimit` clamp, live throttle/stash/limit snapshot seeding — all mirror `TrainMixin.tramways$tickSigns` exactly.
- **Geometry**: edge lengths bit-identical by construction (translator sums Create's own `getLength()`, incl. 16-sample bezier arc); station stop position/side/epsilon; portals as 0-length edges; base path costs and endpoint-edge penalties (`getNavigationPenalty()/2`, both directions, both ends); IDLE=700, WAITING=50+min(ticks/20,1000).
