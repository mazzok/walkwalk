# Ghosts in replay, with a shared-range speed heatmap: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When replaying a recorded hike with ghost mode on, show every other start-matched hike as a moving ghost, and let the existing heatmap switch colour this hike and all open ghost trails on one shared blue-to-red range.

**Architecture:** Replay ghosts are computed from pure functions of the replay moment: a ghost's position is `GhostTrack.positionAt(activeElapsed)`, and its vanish moment is computed once when the replay opens by running the existing `OffRouteTracker` rule over the replayed hike's own fixes. So scrubbing is stateless. `ReplayViewModel` owns ghost loading and derived state; `TopoMap` gains a ghost-heat layer and takes a shared range.

**Tech Stack:** Kotlin, Jetpack Compose, MapLibre, Room, JUnit4 + Robolectric. Run tests with `./gradlew testDebugUnitTest` (Windows Git Bash; use `--tests "<class>"` for one class).

**Spec:** `docs/superpowers/specs/2026-11-03-replay-ghosts-design.md`

## Global Constraints

- Match radius 150 m (`MATCH_RADIUS_M`), off-route hold 60 s (`OFF_ROUTE_HOLD_MS`), drop distance from `AppSettings.ghostDropM` (0 = Off), all reused, none redefined.
- Ghosts follow **real elapsed time without pauses between segments**, exactly `GhostTrack`'s definition (not the compressed replay clock, not `ReplayFrame.elapsedMs`, which includes pauses).
- Vanish is a function of the moment: a ghost is visible at elapsed `t` iff `vanishAtMs == null || t < vanishAtMs`.
- The replayed hike is excluded from its own ghosts by id; older and newer hikes both match.
- Ghost mode is read once when the replay opens. Ghost mode off, or any ghost load failure, leaves replay exactly as it is today.
- Heatmap range covers each shown ghost trail's whole track (not the part up to the ghost), excludes resting speeds, and must not change while playing or scrubbing unless the set of shown trails changes.
- Live ghost mode (`GhostSession`) and its settings are not modified.
- **Version control:** this repo's owner requires explicit approval before any commit. Every "Commit" step means: show `git status`/diff summary and wait for approval; do not commit on your own.

## Review Focus

1. A ghost whose hike has no moving edges (all rest or zero speed) must not drag the shared range's minimum to 0. (Task 2)
2. A replayed hike or ghost with a manual pause (two segments): ghost position and vanish moment use pause-free elapsed time, so the ghost is not offset by the pause length. (Tasks 1, 3)
3. Scrubbing across a vanish moment: visible one tick before, hidden at it, visible again after dragging back; an open trail returns with it and the range follows. (Tasks 3, 4)
4. The replayed hike must not appear as its own ghost, and hikes with no usable points are skipped without error. (Task 4)
5. Ghost mode off, a repository failure while loading ghosts, or a ghost with zero points: replay still loads, `ghostViews` stays empty, nothing throws. (Task 4)

---

## File Structure

- `domain/ReplayTimeline.kt` (modify): `ReplayFrame.activeMs`.
- `domain/SpeedHeatmap.kt` (modify): `hasMoving`, `combinedRange`.
- `map/HeatmapFeatures.kt` (modify): range-aware features, ghost heat features.
- `ghost/GhostTrack.kt` (modify): `elapsedAtIndex`.
- `ghost/ReplayGhosts.kt` (create): pure replay-ghost model and loader.
- `ui/ReplayViewModel.kt` (modify): ghost state, open trails, shared range; takes `AppSettings`.
- `map/TopoMap.kt`, `ui/ReplayScreen.kt`, `MainActivity.kt` (modify): wiring and the ghost heat layer.

---

### Task 1: Pause-free elapsed time on replay frames

**Files:**
- Modify: `app/src/main/java/com/walkwalk/domain/ReplayTimeline.kt`
- Test: `app/src/test/java/com/walkwalk/domain/ReplayTimelineTest.kt`

**Interfaces:**
- Produces: `ReplayFrame.activeMs: Long` (last constructor parameter): time since the first fix, counting only time within segments, i.e. the same clock as `GhostTrack`'s elapsed.

- [ ] **Step 1: Write the failing test** `activeElapsedSkipsPausesBetweenSegments`: track = `straightLine(1.0, 5_000, 5, seg = 0)` followed by `straightLine(1.0, 5_000, 5, startTime = 620_000, seg = 1, startLat = 47.01)` (a ~10 min pause). Frame at the first point of segment 1 (`ReplayTimeline(points, emptyList()).frameAt(...)` at the replay time where real time = 620_000; that replay time is 20_000 because the gap collapses): assert `elapsedMs == 620_000` and `activeMs == 20_000`. Add `activeMsEqualsElapsedWithoutPauses`: on a single-segment `straightLine(1.0, 5_000, 20)`, `activeMs == elapsedMs` at replay time 40_000.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.walkwalk.domain.ReplayTimelineTest"`. Expected: compile failure (no `activeMs`).
- [ ] **Step 3: Implement.** Add `val activeMs: Long` as the last `ReplayFrame` parameter. In `ReplayTimeline` build `private val active = LongArray(points.size)` the way `GhostTrack` builds `elapsed` (cumulative same-segment time deltas) and set `activeMs = active[i] + ((active[j] - active[i]) * f).toLong()` in `frameAt`. Fix any other `ReplayFrame(...)` constructions the compiler flags.
- [ ] **Step 4: Run** the same command, then the full suite. Expected: PASS.
- [ ] **Step 5: Commit (needs user approval):** `feat: pause-free elapsed time on replay frames`.

### Task 2: Shared heatmap range and ghost heat features

**Files:**
- Modify: `app/src/main/java/com/walkwalk/domain/SpeedHeatmap.kt`, `app/src/main/java/com/walkwalk/map/HeatmapFeatures.kt`
- Test: `app/src/test/java/com/walkwalk/domain/SpeedHeatmapTest.kt`, `app/src/test/java/com/walkwalk/map/HeatmapFeaturesTest.kt`

**Interfaces:**
- Consumes: `SpeedHeatmap.of`, `SpeedHeatmap.colorFor` (existing).
- Produces:
  - `val SpeedHeatmap.hasMoving: Boolean` (any edge with non-null speed).
  - `SpeedHeatmap.combinedRange(heats: List<SpeedHeatmap>): ClosedFloatingPointRange<Double>`: min..max over heats where `hasMoving`; `0.0..0.0` when none.
  - `fun heatmapFeatures(heat: SpeedHeatmap, range: ClosedFloatingPointRange<Double> = heat.minKmh..heat.maxKmh): FeatureCollection`
  - `fun ghostHeatFeatures(heats: List<SpeedHeatmap>, range: ClosedFloatingPointRange<Double>): FeatureCollection` (all heats' edges in one collection, same property names as `heatmapFeatures`).

- [ ] **Step 1: Write failing tests.** `combinedRangeSpansAllHeatmaps` (slow 1 m/s track + fast 2 m/s track → `3.6..7.2` within 0.2); `combinedRangeIgnoresHeatmapsWithoutMovingEdges` (a heatmap of 6 identical points plus the 1 m/s one → min stays ≈3.6, not 0.0); `combinedRangeOfNothingIsZero`. In `HeatmapFeaturesTest`: `explicitRangeRecolours` (a 1 m/s track coloured on range `0.0..3.6` gives `#FF0000` somewhere and with `3.6..7.2` gives `#0000FF`); `ghostHeatFeaturesContainEveryEdgeOfEveryHeatmap` (edge counts add up).
- [ ] **Step 2: Run** both classes. Expected: compile failure.
- [ ] **Step 3: Implement** the four members above in `SpeedHeatmap.kt` / `HeatmapFeatures.kt`; the existing single-argument `heatmapFeatures` call sites keep working through the default.
- [ ] **Step 4: Run** both classes, expect PASS.
- [ ] **Step 5: Commit (needs user approval):** `feat: shared speed range across heatmaps`.

### Task 3: Replay ghost model

**Files:**
- Modify: `app/src/main/java/com/walkwalk/ghost/GhostTrack.kt`
- Create: `app/src/main/java/com/walkwalk/ghost/ReplayGhosts.kt`
- Test: `app/src/test/java/com/walkwalk/ghost/ReplayGhostsTest.kt`

**Interfaces:**
- Consumes: `GhostTrack`, `OffRouteTracker`, `ghostColors`, `GhostView`, `SpeedHeatmap`.
- Produces:
  - `GhostTrack.elapsedAtIndex(i: Int): Long`
  - `class ReplayGhost(val id: Long, val name: String, val track: GhostTrack, val heat: SpeedHeatmap, val vanishAtMs: Long?)` with `fun visibleAt(elapsedMs: Long): Boolean`.
  - `fun vanishMomentMs(hikePoints: List<TrackPoint>, ghost: GhostTrack, limitM: Int): Long?`: runs `OffRouteTracker(limitM)` over `hikePoints` using `GhostTrack(hikePoints).elapsedAtIndex(i)` as the clock and `ghost.distanceToTrackM(lat, lon)` as the distance; returns the elapsed at which it first reports true, `null` if never (always null for `limitM == 0`).
  - `fun replayGhostViews(ghosts: List<ReplayGhost>, elapsedMs: Long, trackColor: Int): List<GhostView>`: visible ghosts only, colours from `ghostColors(ghosts.size, trackColor)` indexed by position in the full list (so colours do not shift when others vanish), `deltaMs = null`, `finishedMs = track.totalMs` once `elapsedMs >= track.totalMs` else null, position `track.positionAt(elapsedMs)`.

- [ ] **Step 1: Write failing tests** (build points with `p(...)`/`straightLine`): 
  - `vanishMomentIsHoldAfterFirstFarFix`: ghost = `GhostTrack(straightLine(1.0, 5_000, 61))`; hike = same line for the first 100 s then fixes 0.02° east every 5 s up to 300 s; `vanishMomentMs(hike, ghost, 200) == 165_000L`.
  - `neverVanishesWhenOffOrOnRoute`: limit 0 → null on the far-away hike; limit 200 on an identical hike → null.
  - `visibleOnlyBeforeVanishMoment`: `ReplayGhost(..., vanishAtMs = 165_000)`: `visibleAt(164_999)` true, `visibleAt(165_000)` false, `visibleAt(10_000)` true again (no memory).
  - `colourStaysWithTheGhostWhenAnotherVanishes`: two ghosts, first vanished at 50 s; the second ghost's `colorArgb` at 10 s and at 60 s are equal.
  - `finishedGhostStaysAtItsEnd`: at `elapsedMs` past `totalMs`, view has `finishedMs == totalMs` and position equal to the track's last point.
  - `pauseBetweenSegmentsDoesNotShiftTheVanishClock`: hike with a 10 min pause between two segments, off route only in segment 2: the returned moment excludes the pause.
- [ ] **Step 2: Run** `--tests "com.walkwalk.ghost.ReplayGhostsTest"`. Expected: compile failure.
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4: Run** the class and the existing ghost tests, expect PASS.
- [ ] **Step 5: Commit (needs user approval):** `feat: replay ghost model`.

### Task 4: Ghost loading and replay state

**Files:**
- Modify: `app/src/main/java/com/walkwalk/ghost/ReplayGhosts.kt`, `app/src/main/java/com/walkwalk/ui/ReplayViewModel.kt`, `app/src/main/java/com/walkwalk/MainActivity.kt`
- Test: `app/src/test/java/com/walkwalk/ui/ReplayViewModelTest.kt`

**Interfaces:**
- Consumes: Tasks 1-3; `HikeRepository.ghostCandidates()`, `.points(id)`, `.stops(id)`; `matchGhosts`; `AppSettings.ghostMode`, `.ghostDropM`, `.trackColor`.
- Produces:
  - `suspend fun loadReplayGhosts(repo: HikeRepository, hikeId: Long, hikePoints: List<TrackPoint>, dropM: Int): List<ReplayGhost>`: candidates minus `hikeId`, `matchGhosts(hikePoints.first(), …)`, skipping hikes with fewer than 2 points; heat from `SpeedHeatmap.of(points, repo.stops(id))`; vanish from `vanishMomentMs`.
  - `ReplayViewModel(repo: HikeRepository, hikeId: Long, settings: AppSettings)` (new third parameter).
  - On the view model: `ghostViews: StateFlow<List<GhostView>>`, `openTrails: StateFlow<Set<Long>>`, `fun toggleTrail(id: Long)`, `shownTrails: StateFlow<List<GhostTrail>>` and `ghostHeatmaps: StateFlow<List<SpeedHeatmap>>` (both: open ∧ visible, same order, re-emitted **only when the set of shown ids changes**), `heatRange: StateFlow<ClosedFloatingPointRange<Double>?>` = `SpeedHeatmap.combinedRange(listOfNotNull(heatmap) + ghostHeatmaps)`, recomputed only when the shown set changes, null until the hike has loaded.

- [ ] **Step 1: Write failing tests** in `ReplayViewModelTest` (existing `walkVm` helper builds a hike with `repo.startHike/appendPoint/finish`; give it an `AppSettings` built from a cleared `SharedPreferences` with `setGhostMode(true)`; update the existing `ReplayViewModel(...)` construction to pass settings). Cases:
  - `ghostsFollowRealElapsedTime`: record an older hike at the same start walking 2 m/s; replay a 1.25 m/s hike; `seek(60_000)` → the ghost's position equals `GhostTrack.positionAt(frame.activeMs)`.
  - `newerNearbyHikesAreGhostsToo` and `replayedHikeIsNotItsOwnGhost` and `farAwayHikesAreNotGhosts` (start 200 m away).
  - `ghostModeOffMeansNoGhosts`.
  - `dropOffKeepsEveryGhost` (`ghostDropM` Off, hike diverges, ghost still visible at the end).
  - `ghostVanishesAndReturnsWhenScrubbing`: diverging hike; `seek` past the vanish moment → `ghostViews` empty; `seek` back → one ghost again.
  - `openTrailReturnsWithItsGhostAndRangeFollows`: open the ghost's trail, scrub past vanish → `shownTrails` empty and `heatRange` equals the replayed hike's own range; scrub back → trail and combined range again.
  - `rangeIsStableWhilePlayingWhenShownSetIsUnchanged`: collect `heatRange` emissions across several `seek` calls inside one visibility window; expect a single value.
  - `ghostLoadFailureLeavesReplayWorking`: a repository whose `ghostCandidates()` throws (subclass/wrapper; if `HikeRepository` is final, close the database before the ghost load) → hike and frame still load, `ghostViews` empty.
- [ ] **Step 2: Run** `--tests "com.walkwalk.ui.ReplayViewModelTest"`. Expected: compile failure, then failures per case.
- [ ] **Step 3: Implement** `loadReplayGhosts`; in `ReplayViewModel.reload()` after the hike state is set, launch the ghost load in `viewModelScope` with the work in `withContext(Dispatchers.Default)`, catching everything except `CancellationException` (log with `Log.w("walkwalk", "replay ghost load failed", e)`), only when `settings.ghostMode.value` was true at construction (read once); then recompute ghost state for the current frame. `seek()` updates `ghostViews` from `frame.activeMs` via `replayGhostViews`, and re-derives `shownTrails`/`ghostHeatmaps`/`heatRange` only when the shown id set differs from the previous one. Pass `app.settings` to the constructor in `MainActivity`.
- [ ] **Step 4: Run** the class, then the full suite. Expected: PASS.
- [ ] **Step 5: Commit (needs user approval):** `feat: ghosts and shared heat range in replay state`.

### Task 5: Map and screen wiring

**Files:**
- Modify: `app/src/main/java/com/walkwalk/map/TopoMap.kt`, `app/src/main/java/com/walkwalk/ui/ReplayScreen.kt`

**Interfaces:**
- Consumes: Task 4's view model state; Task 2's `heatmapFeatures(heat, range)` and `ghostHeatFeatures`.
- Produces: `TopoMap` parameters `ghostHeatmaps: List<SpeedHeatmap> = emptyList()` and `heatRange: ClosedFloatingPointRange<Double>? = null`.

- [ ] **Step 1: Wire `ReplayScreen`**: collect `ghostViews`, `shownTrails`, `ghostHeatmaps`, `heatRange`; pass `ghosts = ghostViews.map { GhostMarker(it.id, it.colorArgb, it.lat, it.lon) }`, `ghostTrails = shownTrails`, `onGhostClick = vm::toggleTrail`, `ghostHeatmaps`, `heatRange`, to `TopoMap`. The legend (`SpeedLegend`) shows `heatRange` instead of the single hike's min/max.
- [ ] **Step 2: Extend `TopoMap`**: add a `ghost-heat-line` `LineLayer` (colour from the feature's `color`, width 3, opacity 0.8, initially hidden) above `ghost-trails`; when `heatmap != null` and the heat range is non-null, set its source from `ghostHeatFeatures(ghostHeatmaps, range)`, hide `ghost-trails`, show `ghost-heat-line`, and build the own heat features with `heatmapFeatures(heatmap, range)`; otherwise restore the previous visibilities. Keep the tap logic unchanged (landmark pin beats ghost tap).
- [ ] **Step 3: Build** `./gradlew assembleDebug testDebugUnitTest`. Expected: BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 4: Check on the emulator** (adb as in the earlier heatmap check; the debug app needs ghost mode on in Settings and at least two hikes starting within 150 m of each other, e.g. import `varied.gpx` twice with different timings): ghost dots move with the replay and follow a scrub; tapping a dot opens its trail; with the heatmap switch on, both trails use one legend range; a ghost vanishes when the hike leaves its route and returns when scrubbing back. Report anything that looks wrong with a screenshot.
- [ ] **Step 5: Commit (needs user approval):** `feat: ghosts and shared speed heatmap in replay`.

---

## Self-review notes

- Spec goals 1-7 map to Tasks 3-5 (1: T4; 2: T1+T3; 3, 4, 5: T3/T4; 6, 7: T2/T5). Non-goals untouched.
- Names used across tasks: `activeMs`, `combinedRange`, `hasMoving`, `ghostHeatFeatures`, `elapsedAtIndex`, `ReplayGhost`, `vanishMomentMs`, `replayGhostViews`, `loadReplayGhosts`, `shownTrails`, `ghostHeatmaps`, `heatRange`.
