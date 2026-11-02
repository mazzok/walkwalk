# Settings and Ghost Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Settings screen (track color, ghost mode switch, off-route distance) and a ghost mode that replays every earlier walk of the same route in real time on the live recording map, with ahead/behind readouts.

**Architecture:** Pure, unit-tested domain code (`GhostTrack` timeline, matching, ahead/behind, off-route tracking, colors) in a new `ghost` package. A `GhostSession` (app-scoped, owned by `WalkwalkApp`) loads matches on the new hike's first accepted fix and is updated once per second by `RecordingScreen` off the main thread; it never touches `RecordingController`. Settings are a `SharedPreferences`-backed `AppSettings` exposing `StateFlow`s. `TopoMap` gets a track-color parameter plus ghost dot/trail sources and layers.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, MapLibre (`TopoMap.kt`), Room, JUnit4 + Robolectric (existing test setup).

**Spec:** `docs/superpowers/specs/2026-11-02-settings-and-ghost-mode-design.md`

## Global Constraints

- Default track color stays `#CC3333` (`0xFFCC3333`); nothing changes for a user who never opens Settings.
- Ghost mode is off by default; it is a persistent preference read when the hike starts (first accepted fix).
- Match radius **150 m** between the ghost's first point and the new hike's first accepted fix; all matches shown.
- Ghost clock = the live screen's elapsed time (`LiveStats.elapsedMs` + tick), 1:1, no compression; ghost timeline is **uncompressed** (do not use `ReplayTimeline`); gaps between segments count as zero, stand-still time is kept.
- Candidates: finished hikes with `hasTimes = true` and at least 2 points.
- Ahead/behind by distance along the route: `t − g`; shown `+m:ss` (behind) / `−m:ss` (ahead, U+2212); no number under **50 m** walked; beyond the ghost's end compare against the ghost's total time. Finished ghost row: `finished · <total time>`, delta keeps updating.
- Off-route drop: options **Off / 100 / 200 / 500 m**, default **200 m**; continuously beyond the limit for **60 s** drops the ghost for the rest of the hike; distance to the nearest segment of the ghost's whole track.
- Ghost dot ≈ 80% opacity, white outline, smaller than the user marker; trail ≈ 50% opacity, ghost's color; several trails may be open; tapping empty map hides all; landmark taps and long-press keep working.
- Track color picker = about 12 preset swatches; ghost palette is separate and skips colors too close to the chosen track color; colors repeat when ghosts outnumber palette colors.
- Ghost work must never block recording and must not change what is recorded or saved.
- Repo rule: no commits without explicit user approval. Every "Commit" step below means: show `git status` / `git diff --stat` and the proposed message, then wait for approval.

## Review Focus

- A candidate hike whose points are all identical (zero distance, zero duration) or that has a single point: no NaN, no divide-by-zero, never crashes the live screen (Task 2, Task 4).
- Starting a second hike right after the first in the same process: the session must not reuse the first hike's ghosts or off-route timers (Task 4).
- One bad GPS fix far from the route must not drop a ghost; coming back within the limit resets the 60 s timer (Task 3).
- Ghost mode off (or no earlier hike near the start): no ghost list, no map layers drawn, no repository load, recording behaves exactly as before (Task 4, Task 6).
- Chosen track color equal/near a ghost palette color, and more ghosts than palette colors (Task 3).

---

## File Structure

- Create `app/src/main/java/com/walkwalk/settings/AppSettings.kt` — prefs-backed settings with flows.
- Create `app/src/main/java/com/walkwalk/settings/TrackColors.kt` — `DEFAULT_TRACK_COLOR`, `TRACK_SWATCHES`.
- Create `app/src/main/java/com/walkwalk/ui/SettingsScreen.kt` — the screen.
- Create `app/src/main/java/com/walkwalk/ghost/GhostTrack.kt` — uncompressed timeline of one old hike.
- Create `app/src/main/java/com/walkwalk/ghost/GhostRules.kt` — constants, `GhostCandidate`, `matchGhosts`, `aheadBehindMs`, `OffRouteTracker`.
- Create `app/src/main/java/com/walkwalk/ghost/GhostColors.kt` — `GHOST_PALETTE`, `ghostColors`.
- Create `app/src/main/java/com/walkwalk/ghost/GhostSession.kt` — runtime state holder.
- Create `app/src/main/java/com/walkwalk/map/GhostFeatures.kt` — `GhostMarker`, `GhostTrail`, `colorHex`, GeoJSON builders.
- Modify `data/HikeDao.kt`, `data/HikeRepository.kt` (candidate query), `map/TopoMap.kt` (color + ghost layers), `ui/HikeListScreen.kt` (gear), `ui/ReplayScreen.kt` (color), `ui/RecordingScreen.kt` (ghost list + wiring), `ui/Format.kt` (`delta`), `MainActivity.kt` (route), `WalkwalkApp.kt` (`settings`, `ghosts`).
- Tests mirror the main tree under `app/src/test/java/com/walkwalk/`.

Test command (all tasks): `./gradlew :app:testDebugUnitTest --tests "<fqcn>"`; full: `./gradlew :app:testDebugUnitTest`.

---

### Task 1: AppSettings and track color constants

**Files:**
- Create: `app/src/main/java/com/walkwalk/settings/AppSettings.kt`, `app/src/main/java/com/walkwalk/settings/TrackColors.kt`
- Test: `app/src/test/java/com/walkwalk/settings/AppSettingsTest.kt` (Robolectric, like `GpsLocationSourceTest`)

**Interfaces:**
- Produces:
  - `const val DEFAULT_TRACK_COLOR: Int = 0xFFCC3333.toInt()`; `val TRACK_SWATCHES: List<Int>` (12 ARGB ints, first is the default): CC3333, E65100, F9A825, 2E7D32, 00796B, 1565C0, 3949AB, 7B1FA2, C2185B, 5D4037, 212121, 757575.
  - `class AppSettings(prefs: SharedPreferences)` with `val trackColor: StateFlow<Int>`, `val ghostMode: StateFlow<Boolean>`, `val ghostDropM: StateFlow<Int>` (0 = Off), `fun setTrackColor(argb: Int)`, `fun setGhostMode(on: Boolean)`, `fun setGhostDropM(m: Int)`; `companion object { val DROP_OPTIONS = listOf(0, 100, 200, 500); const val DEFAULT_DROP_M = 200 }`.

- [ ] **Step 1: Write failing tests** in `AppSettingsTest`:
  - `defaults()`: new prefs → `trackColor.value == DEFAULT_TRACK_COLOR`, `ghostMode.value == false`, `ghostDropM.value == 200`.
  - `valuesPersistAcrossInstances()`: set color `TRACK_SWATCHES[5]`, ghost on, drop 500 → a new `AppSettings` on the same prefs reads them back.
  - `flowsEmitOnChange()`: `setGhostMode(true)` → `ghostMode.value == true`.
  - `unknownDropValueFallsBackToDefault()`: prefs containing `ghost_drop_m = 123` → `ghostDropM.value == 200`.
- [ ] **Step 2: Run, expect FAIL** (`AppSettings` unresolved): `./gradlew :app:testDebugUnitTest --tests "com.walkwalk.settings.AppSettingsTest"`
- [ ] **Step 3: Implement** both files per the interfaces above; prefs keys `track_color`, `ghost_mode`, `ghost_drop_m`; setters write with `apply()` then update the flow.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit (needs user approval)** — `feat: add settings store for track color and ghost mode`

### Task 2: GhostTrack (uncompressed timeline of an old hike)

**Files:**
- Create: `app/src/main/java/com/walkwalk/ghost/GhostTrack.kt`
- Test: `app/src/test/java/com/walkwalk/ghost/GhostTrackTest.kt` (use `straightLine`, `walkStandWalk`, `p`, `M_PER_DEG_LAT` from `domain/TestTracks.kt`)

**Interfaces:**
- Consumes: `TrackPoint`, `Geo.distance`, `Geo.cumulativeDistances`.
- Produces: `class GhostTrack(val points: List<TrackPoint>)` with
  `val totalMs: Long` (sum of in-segment time spans, i.e. gaps between segments excluded),
  `val totalDistanceM: Double`,
  `fun positionAt(clockMs: Long): Pair<Double, Double>` (lat, lon; clamped to `0..totalMs`),
  `fun elapsedAtDistance(distanceM: Double): Long` (earliest elapsed at which the ghost had covered that distance; `totalMs` when beyond; 0 for ≤ 0),
  `fun distanceToTrackM(lat: Double, lon: Double): Double` (to the nearest segment, never across a segment gap).

- [ ] **Step 1: Write failing tests:**
  - `positionIsRealTime()`: `straightLine(1.0, 5_000, 61)` → `totalMs == 300_000`; `positionAt(150_000).first` ≈ `47.0 + 150 / M_PER_DEG_LAT` (1e-6).
  - `pauseGapIsExcluded()`: segment 0 = `straightLine(1.0, 5_000, 21)`, segment 1 = `straightLine(1.0, 5_000, 21, startTime = 1_000_000, seg = 1, startLat = <lat 500 m north>)` → `totalMs == 200_000`; `positionAt(150_000)` equals 50 s into segment 1.
  - `standStillIsKept()`: `walkStandWalk()` → `totalMs` equals last point time minus first.
  - `elapsedAtDistanceMatchesPosition()`: on the straight line `elapsedAtDistance(150.0)` ≈ 150_000 (±1_000); beyond the end → `300_000`; `0.0` → `0`.
  - `distanceToTrack()`: point ~100 m east of mid-track (lon + 100 / (M_PER_DEG_LAT·cos 47°)) → ≈100 (±2); 50 m north of the last point → ≈50 (±1).
  - `degenerateTracksDoNotBlowUp()`: single point → `totalMs == 0`, `positionAt(5_000)` is that point, `distanceToTrackM` is the straight distance, no NaN; two identical points with identical time → `elapsedAtDistance(10.0) == 0`, `distanceToTrackM` finite.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement `GhostTrack`.** Precompute `elapsed: LongArray` (adds `time[i]-time[i-1]` only when same segment) and `cumDist`; binary search for lookups, interpolate only inside one segment; `distanceToTrackM` projects to a local equirectangular plane (metres) per segment and clamps the projection parameter to `[0,1]`, treating zero-length segments as points.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit (needs user approval)** — `feat: add uncompressed ghost timeline`

### Task 3: Ghost rules (matching, ahead/behind, off-route) and colors

**Files:**
- Create: `app/src/main/java/com/walkwalk/ghost/GhostRules.kt`, `app/src/main/java/com/walkwalk/ghost/GhostColors.kt`
- Test: `app/src/test/java/com/walkwalk/ghost/GhostRulesTest.kt`, `app/src/test/java/com/walkwalk/ghost/GhostColorsTest.kt`

**Interfaces:**
- Consumes: `GhostTrack` (Task 2), `HikeEntity`, `TrackPoint`, `Geo.distance`.
- Produces:
  - `const val MATCH_RADIUS_M = 150.0`, `MIN_COMPARE_DISTANCE_M = 50.0`, `OFF_ROUTE_HOLD_MS = 60_000L`.
  - `data class GhostCandidate(val hike: HikeEntity, val first: TrackPoint)`.
  - `fun matchGhosts(start: TrackPoint, candidates: List<GhostCandidate>): List<GhostCandidate>` — those with `Geo.distance(start, first) <= MATCH_RADIUS_M`, input order kept.
  - `fun aheadBehindMs(ghost: GhostTrack, userDistanceM: Double, userElapsedMs: Long): Long?` — `null` if `userDistanceM < MIN_COMPARE_DISTANCE_M`, else `userElapsedMs - ghost.elapsedAtDistance(userDistanceM)`.
  - `class OffRouteTracker(private val limitM: Int)` with `fun update(elapsedMs: Long, distanceToTrackM: Double): Boolean` — returns `true` once dropped and stays `true`; `limitM <= 0` never drops.
  - `const val` none; in `GhostColors.kt`: `val GHOST_PALETTE: List<Int>` = 7B3FA0, 00897B, F9A825, 3949AB, 6D4C41, 00ACC1, D81B60, 7CB342 (all `0xFF…`); `fun ghostColors(count: Int, trackColor: Int): List<Int>` — palette minus colors within RGB Euclidean distance < 80 of `trackColor`, cycled to `count` entries.

- [ ] **Step 1: Write failing tests:**
  - Matching: start vs first at 100 m → matched; at 200 m → not; exactly-on-start → matched; empty candidates → empty list.
  - `aheadBehind` on `GhostTrack(straightLine(1.0, 5_000, 61))`: user at 150 m after 180 000 ms → `+30_000`; at 150 m after 120 000 ms → `-30_000`; at 49 m → `null`; at 400 m after 500 000 ms → `500_000 - 300_000 = 200_000` (beyond end uses total time).
  - OffRouteTracker(200): `update(0, 250)` false; `update(59_000, 250)` false; `update(60_000, 250)` true; after true, `update(61_000, 0.0)` stays true.
  - `singleOutlierDoesNotDrop()`: `update(0, 10)`, `update(1_000, 900)`, `update(2_000, 10)`, `update(70_000, 10)` all false. `returningResetsTimer()`: far from 0 to 50 s, near at 55 s, far again from 56 s → still false at 100 s, true at 116 s.
  - `offNeverDrops()`: `OffRouteTracker(0)` with distance 10 000 at 10 minutes → false.
  - Colors: `ghostColors(3, DEFAULT_TRACK_COLOR)` has 3 entries and none within distance 80 of the track color; with `trackColor = GHOST_PALETTE[0]` that entry never appears; `ghostColors(20, …)` has 20 entries (repeats); `ghostColors(0, …)` empty.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement both files per the interfaces.** `OffRouteTracker` keeps `farSince: Long?` (set on first far reading, cleared on near reading) and compares `elapsedMs - farSince >= OFF_ROUTE_HOLD_MS`.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit (needs user approval)** — `feat: add ghost matching, ahead/behind, off-route and palette rules`

### Task 4: Candidate query and GhostSession

**Files:**
- Modify: `data/HikeDao.kt`, `data/HikeRepository.kt`
- Create: `app/src/main/java/com/walkwalk/ghost/GhostSession.kt`
- Test: `app/src/test/java/com/walkwalk/data/HikeRepositoryTest.kt` (add), `app/src/test/java/com/walkwalk/ghost/GhostSessionTest.kt` (build the repository the way `HikeRepositoryTest` does)

**Interfaces:**
- Consumes: Task 1 `AppSettings`; Tasks 2–3 types; `HikeRepository.points(hikeId)`.
- Produces:
  - `HikeDao.ghostFirstPoints(): List<TrackPointEntity>` — first point (by `time, id`) of every FINISHED hike with `hasTimes = 1` that has at least 2 points.
  - `HikeRepository.ghostCandidates(): List<GhostCandidate>` (joins hikes to those first points).
  - `data class GhostView(val id: Long, val name: String, val colorArgb: Int, val lat: Double, val lon: Double, val deltaMs: Long?, val finishedMs: Long?)` (`id` = old hike id; `finishedMs` = ghost's `totalMs` once the clock passed it, else null).
  - `class GhostSession(repo: HikeRepository, settings: AppSettings)` with `val ghosts: StateFlow<List<GhostView>>`, `suspend fun update(hikeId: Long, track: List<TrackPoint>, elapsedMs: Long, distanceM: Double)`, `fun trail(id: Long): List<TrackPoint>`.
  - `update` behavior: runs its work on `Dispatchers.Default`; if `hikeId` differs from the session's current hike, reset everything (ghosts, trackers, loaded tracks); if `track` is empty do nothing further; on the first call with a non-empty track for a hike, read `settings.ghostMode.value` once — if off, stay empty and never touch the repo; if on, match `track.first()` against `repo.ghostCandidates()`, load matches' points, build `GhostTrack`s, assign colors with `ghostColors(matchCount, settings.trackColor.value)` once (stable per ghost). Each call then computes per ghost: position at `elapsedMs`, `aheadBehindMs`, `finishedMs`, and feeds `OffRouteTracker(settings.ghostDropM.value)` with `distanceToTrackM(track.last())`; dropped ghosts leave the list permanently for that hike. Matched hikes whose loaded points turn out < 2 are skipped.

- [ ] **Step 1: Write failing repository test:** insert finished timed hikes: A (3 points), B (1 point), C (finished, `hasTimes = false`), D (still RECORDING) → `ghostCandidates()` returns only A, with `first` equal to A's earliest point.
- [ ] **Step 2: Write failing session tests** (seed old hikes with `straightLine`; new hike track = first point near the old start):
  - `ghostOffByDefault()`: ghost mode off → `ghosts.value` empty after `update`, and `repo.ghostCandidates` not called (use a counting wrapper or assert via a hike that would match).
  - `matchesNearStartOnly()`: ghost mode on; old hikes starting 100 m and 400 m from the new start → one ghost.
  - `ghostPositionFollowsElapsed()`: after `update(..., elapsedMs = 150_000, distanceM = 150.0)` the ghost's lat ≈ `47.0 + 150 / M_PER_DEG_LAT`, `deltaMs` is `elapsedMs − 150_000`'s equivalent (0 ± 1 000 for a 1 m/s old walk).
  - `restoreMidHike()`: the very first `update` has `elapsedMs = 600_000` and a long track → ghost already at the 600 s position (ghosts rebuilt, nothing persisted).
  - `finishedLabel()`: `elapsedMs` past the old total → `finishedMs == total` and `deltaMs` still non-null.
  - `noNumberBeforeFiftyMeters()`: `distanceM = 10.0` → `deltaMs == null`, ghost still present.
  - `dropsGhostLeavingRoute()`: drop 200; user track ≥ 250 m from the old track from `elapsed` 100 000 through 161 000 → ghost removed and does not return when the user comes back.
  - `newHikeResetsSession()`: update with hike 1 (ghost present), then `update` with hike 2 whose start matches nothing → `ghosts.value` empty (no leftovers).
  - `degenerateOldHikeIgnored()`: a candidate whose points are identical → either skipped or present with finite values; `update` never throws.
- [ ] **Step 3: Run, expect FAIL.**
- [ ] **Step 4: Implement** the DAO query (correlated subquery for the first point + `HAVING COUNT(*) >= 2` filter), repository method, and `GhostSession` (guard concurrent `update` calls with a `Mutex`; publish immutable lists to the `MutableStateFlow`).
- [ ] **Step 5: Run, expect PASS** for both test classes plus `HikeRepositoryTest` as a whole.
- [ ] **Step 6: Commit (needs user approval)** — `feat: add ghost session and candidate query`

### Task 5: Settings screen, navigation, and track color on the map

**Files:**
- Create: `app/src/main/java/com/walkwalk/ui/SettingsScreen.kt`, `app/src/main/java/com/walkwalk/map/GhostFeatures.kt` (only `colorHex` in this task)
- Modify: `WalkwalkApp.kt`, `MainActivity.kt`, `ui/HikeListScreen.kt`, `ui/ReplayScreen.kt`, `ui/RecordingScreen.kt` (color pass-through only), `map/TopoMap.kt`
- Test: `app/src/test/java/com/walkwalk/map/GhostFeaturesTest.kt` (`colorHex` only now)

**Interfaces:**
- Consumes: `AppSettings`, `TRACK_SWATCHES`.
- Produces:
  - `fun colorHex(argb: Int): String` → `"#RRGGBB"` uppercase, alpha dropped (`0xFFCC3333.toInt()` → `"#CC3333"`).
  - `WalkwalkApp.settings: AppSettings` (lazy, `getSharedPreferences("settings", MODE_PRIVATE)`).
  - `@Composable fun SettingsScreen(settings: AppSettings, onBack: () -> Unit)`: top bar "Settings" with back arrow; a "Track color" section with a grid of `TRACK_SWATCHES` (selected one marked with a check/ring, `contentDescription` per swatch); a "Ghost mode" row with `Switch` and one-line explanation; "Drop ghosts that leave the route" choice (Off / 100 m / 200 m / 500 m, e.g. `FilterChip`s from `AppSettings.DROP_OPTIONS`) shown enabled only while ghost mode is on.
  - `HikeListScreen(..., onOpenSettings: () -> Unit)` — `TopAppBar` `actions` gets an `IconButton` with `Icons.Default.Settings`, `contentDescription = "Settings"`.
  - Nav route `"settings"` in `MainActivity` (back = `nav.popBackStack()`).
  - `TopoMap(..., trackColor: Int = DEFAULT_TRACK_COLOR)`: a `LaunchedEffect(style, trackColor)` sets the `track-line` layer's `lineColor(colorHex(trackColor))`; the hard-coded `"#CC3333"` in the layer creation is replaced by the same call. `RecordingScreen` and `ReplayScreen` get a `trackColor: Int` parameter passed from `app.settings.trackColor.collectAsState()` in `MainActivity`.

- [ ] **Step 1: Write failing test** `colorHexDropsAlphaAndUppercases()`: `colorHex(0xFFCC3333.toInt()) == "#CC3333"`, `colorHex(0x80002200.toInt()) == "#002200"`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** `colorHex`, then the UI/wiring items above. Follow the existing screens' style (Scaffold + Material 3).
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest` (expect PASS) and `./gradlew :app:assembleDebug` (expect BUILD SUCCESSFUL).
- [ ] **Step 5: Manual check on emulator:** gear opens Settings; pick a swatch; start a hike (Demo button) and confirm the live track uses it; open a replay and confirm the same color; relaunch app and confirm the color persisted. Report results.
- [ ] **Step 6: Commit (needs user approval)** — `feat: add settings screen with track color`

### Task 6: Ghost layers on the map

**Files:**
- Modify: `map/GhostFeatures.kt`, `map/TopoMap.kt`
- Test: `app/src/test/java/com/walkwalk/map/GhostFeaturesTest.kt` (extend; model on `LandmarkFeaturesTest`)

**Interfaces:**
- Produces:
  - `data class GhostMarker(val id: Long, val colorArgb: Int, val lat: Double, val lon: Double)`
  - `data class GhostTrail(val id: Long, val colorArgb: Int, val points: List<TrackPoint>)`
  - `fun ghostDotFeatures(ghosts: List<GhostMarker>): FeatureCollection` — one Point per ghost, number property `id`, string property `color` (`colorHex`).
  - `fun ghostTrailFeatures(trails: List<GhostTrail>): FeatureCollection` — one LineString per segment with ≥ 2 points, properties `id`, `color`.
  - `TopoMap(..., ghosts: List<GhostMarker> = emptyList(), ghostTrails: List<GhostTrail> = emptyList(), onGhostClick: (Long) -> Unit = {})`.
  - In `TopoMap`: sources `ghost-trails` and `ghost-dots`; trail `LineLayer` (color from `get("color")`, opacity 0.5, width 3, added **below** `track-line`); dot `CircleLayer` (color from `get("color")`, opacity 0.8, radius 5, white stroke 1.5, added **below** `marker-dot`). The map click listener queries the ghost-dot layer first (when it exists), then the pin layer; a ghost hit calls `onGhostClick(id)`, a pin hit keeps calling `onLandmarkClick`, otherwise `onMapClick()`. Long-press untouched.

- [ ] **Step 1: Write failing tests:** `dotFeatures` → 2 ghosts give 2 features with the right `id` and `color` strings and coordinates `(lon, lat)`; empty list → 0 features; `trailFeatures` for a trail with segments of 3 and 1 points → exactly 1 LineString (segment of 1 skipped) carrying `id`/`color`; two trails → their features in input order.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** the data classes, builders and the `TopoMap` changes (update sources in `LaunchedEffect(style, ghosts)` / `LaunchedEffect(style, ghostTrails)`, mirroring the landmarks effect).
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest` (PASS) and `./gradlew :app:assembleDebug` (BUILD SUCCESSFUL).
- [ ] **Step 5: Commit (needs user approval)** — `feat: draw ghost dots and trails on the topo map`

### Task 7: Live screen integration

**Files:**
- Modify: `ui/Format.kt`, `ui/RecordingScreen.kt`, `WalkwalkApp.kt`, `MainActivity.kt`
- Test: `app/src/test/java/com/walkwalk/ui/FormatTest.kt` (add)

**Interfaces:**
- Consumes: `GhostSession`, `GhostView`, `GhostMarker`, `GhostTrail`, `TopoMap` ghost params, `colorHex`.
- Produces:
  - `Format.delta(ms: Long): String` — `"+2:30"` for 150 000, `"−1:10"` (U+2212) for −70 000, `"+0:00"` for 0; minutes are not wrapped at 60 (`"+75:00"` for 4 500 000).
  - `WalkwalkApp.ghosts: GhostSession` (lazy, `GhostSession(repository, settings)`).
  - `RecordingScreen(..., ghosts: GhostSession, trackColor: Int)`.

- [ ] **Step 1: Write failing tests** in `FormatTest`: `delta(150_000) == "+2:30"`, `delta(-70_000) == "−1:10"`, `delta(0) == "+0:00"`, `delta(4_500_000) == "+75:00"`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement `Format.delta`.**
- [ ] **Step 4: Wire the screen:**
  - Once per second (`LaunchedEffect` keyed on the existing `now` ticker and `s.hikeId`, reading the latest `s`/`elapsed` via `rememberUpdatedState`) call `ghosts.update(s.hikeId, s.track, elapsed, s.distanceM)`. While the hike is paused `elapsed` stops, so ghosts freeze.
  - Collect `ghosts.ghosts`; keep `var openTrails by remember { mutableStateOf(setOf<Long>()) }`. `onGhostClick` and row taps toggle the id in `openTrails`; the `onMapClick` passed to `TopoMap` clears `openTrails` and still calls `args?.onMapClick`. Trails passed to the map = `openTrails` ∩ current ghost ids, built with `ghosts.trail(id)`.
  - Ghost list: below the stats card (above the risk card is fine), only when non-empty; a compact `Column` (max height ≈ 120 dp, `verticalScroll`) of rows: color dot, `name` (single line, ellipsized), then `finished · ${Format.duration(finishedMs)}` when `finishedMs != null`, then `Format.delta(deltaMs)` (nothing when `deltaMs == null`). Must not overlap the Pause/Stop card or the right-hand buttons.
  - Pass `ghosts`/`trackColor` from `MainActivity`'s `RECORDING` composable.
- [ ] **Step 5: Run** `./gradlew :app:testDebugUnitTest` (all PASS) and `./gradlew :app:assembleDebug` (BUILD SUCCESSFUL).
- [ ] **Step 6: Manual check on emulator** (record two short Demo hikes first so a finished hike with times exists): ghost mode off → no list, no dots; ghost mode on → start a hike at the same spot → dots appear after the first fix, list rows show `+/−` after 50 m, tap dot and row toggle the trail, tap empty map hides all, landmark pin tap and long-press still work, pausing freezes ghosts, killing and reopening the app mid-hike rebuilds ghosts, a recorded hike is identical in points/stats to one recorded with ghost mode off. Report results.
- [ ] **Step 7: Commit (needs user approval)** — `feat: show ghosts and ahead/behind on the live screen`

---

## Self-Review (done)

- **Spec coverage:** goals 1–2 → Task 5; 3 → Tasks 1, 5; 4 → Tasks 3–4; 5 → Tasks 2, 4; 6, 8 → Tasks 6–7; 7 → Tasks 3, 7; 9 → Tasks 3–5; 10 → Task 3. Anti-goals: recording untouched (session only reads; work on `Dispatchers.Default`).
- **Types:** `GhostTrack`, `GhostCandidate`, `GhostView`, `GhostMarker`/`GhostTrail`, `AppSettings.ghostDropM: StateFlow<Int>` (0 = Off) are used with the same names/signatures in every task that consumes them.
- **Known accepted limits (from spec):** off-route timer only advances while the recording screen is open; ghost mode toggled mid-hike takes effect on the next hike.
