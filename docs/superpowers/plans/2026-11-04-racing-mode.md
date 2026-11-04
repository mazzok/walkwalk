# Racing Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A per-hike "race" start that turns one rival ghost into an eyes-free proximity beeper (rate = closeness, single/double = ahead/behind, Doppler pitch bend = closing/opening), playing from the recording service with the screen off.

**Architecture:** Pure Kotlin rules (`race/RaceRules.kt`, `race/RaceSignal.kt`) decide the rival and map the gap to sound parameters. An app-scoped `RaceSession` (like `GhostSession`, but its own loading, own 100 m trackers, locked rival) is fed from `RecordingService`'s existing collection of `RecordingController.state`, and publishes a `RaceState`. `RaceAudio` (Android only) consumes that state, synthesises beeps with `AudioTrack` and handles ducking via audio focus. The race flag is a persisted `raceHikeId` in `AppSettings`.

**Tech Stack:** Kotlin, coroutines/StateFlow, Jetpack Compose, Room (via `HikeRepository`), Android `AudioTrack` + `AudioFocusRequest`, JUnit4 + Robolectric.

**Spec:** `docs/superpowers/specs/2026-11-04-racing-mode-design.md`

## Global Constraints

- Everything race-related is read-only towards the repository; failures are logged (`Log.w("walkwalk", …)`) and must never stop or corrupt recording.
- The gap is `aheadBehindMs(track, userDistanceM, userElapsedMs)`: positive = user behind the rival (rival ahead).
- Race off-route limit: fixed **100 m** with the existing `OFF_ROUTE_HOLD_MS` (60 s) — independent of `ghostDropM`.
- Rival = among eligible candidates, highest `GhostTrack.totalDistanceM / GhostTrack.totalMs`; picked at the first fix, locked; replaced only when it drops off-route.
- Beep interval: 250 ms at gap 0 → 4000 ms at |gap| ≥ 120 s (linear in between); beyond 120 s stays at 4000 ms.
- Doppler bend: from the slope of |gap| over a ~10 s window, `semitones = clamp(-15 × d|gap|/dt, -3, +3)` (d|gap|/dt in seconds per second; shrinking gap → positive).
- Duck music while |gap| < 30 s; release once |gap| > 40 s (hysteresis, my call within the spec's "~30 s").
- Silent while paused, and while the gap is null (first `MIN_COMPARE_DISTANCE_M`).
- Sounds synthesised in code; no audio assets.
- "Start race" works with ghost mode off; ghosts then also show on the map for that hike.
- Version control: per the user's global rule, every "Commit" step means *show the change and ask the user before committing*.

## Review Focus

1. **Process restore mid-race** (`restoreActive`, sticky service restart): the race must resume for the same hike, and the rival choice must account for the track already walked — replay the existing track through the trackers on first load. Test in Task 3.
2. **Rival finishes before the user** (user past the rival's `totalDistanceM`): gap keeps growing, no crash, no silence. Test in Task 3.
3. **Every candidate drops** (user leaves all routes): "no rival" plays once, then silence — not a switch loop. Test in Task 3.
4. **Irregular fixes** (sparse GPS, a 30 s hole): the Doppler slope must use actual timestamps, not sample count. Test in Task 2.
5. **Pause → resume**: silent while paused, and the gap after resume uses the pause-free clock so the pitch doesn't jump. Test in Task 3 (state carries `paused`) and Task 2 (window reset on gap of null).

---

### Task 1: Rival rules

**Files:**
- Create: `app/src/main/java/com/walkwalk/race/RaceRules.kt`
- Test: `app/src/test/java/com/walkwalk/race/RaceRulesTest.kt`

**Interfaces:**
- Consumes: `GhostTrack`, `OffRouteTracker`, `OFF_ROUTE_HOLD_MS` (ghost package).
- Produces:
  - `const val RACE_DROP_M = 100`
  - `class RaceCandidate(val id: Long, val name: String, val track: GhostTrack) { val tracker = OffRouteTracker(RACE_DROP_M); var dropped = false; val pace: Double /* m per ms, totalDistanceM / totalMs, 0 if totalMs == 0 */ }`
  - `fun RaceCandidate.observe(elapsedMs: Long, lat: Double, lon: Double)` — feeds the tracker, sets `dropped`.
  - `fun pickRival(candidates: List<RaceCandidate>): RaceCandidate?` — highest `pace` among `!dropped`; ties keep input order.

- [ ] **Step 1: Write the failing tests** (use `straightLine` from `domain/TestTracks.kt`)
  - `pickRivalTakesHighestPaceOverStopsIncludedClock`: A = 1.0 m/s straight 300 m; B = 1.2 m/s for 300 m but with one 120 s stop (points stationary, same segment) → B's pace = 300/(250+120) s < A's → `pickRival(listOf(b, a)) === a`.
  - `droppedCandidatesAreSkipped`: mark faster one dropped → slower one returned; all dropped → `null`.
  - `observeDropsAfterHoldBeyond100m`: candidate on route; observe points 150 m east from t=0..59 s → not dropped; at t=60 s → dropped. 90 m east for 120 s → never dropped.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.walkwalk.race.RaceRulesTest"` — Expected: compile FAIL (unresolved `RaceCandidate`).
- [ ] **Step 3: Implement** the Interfaces above in `RaceRules.kt`.
- [ ] **Step 4: Run** same command — Expected: PASS.
- [ ] **Step 5: Commit** (ask first) `feat: rival choice rules for racing mode`

### Task 2: Sound mapping

**Files:**
- Create: `app/src/main/java/com/walkwalk/race/RaceSignal.kt`
- Test: `app/src/test/java/com/walkwalk/race/RaceSignalTest.kt`

**Interfaces:**
- Produces:
  - `enum class BeepPattern { SINGLE, DOUBLE }`
  - `fun beepPattern(gapMs: Long): BeepPattern` — `gapMs > 0` (rival ahead) → `SINGLE`, otherwise `DOUBLE`.
  - `fun beepIntervalMs(gapMs: Long): Long` — Global Constraints mapping on `abs(gapMs)`.
  - `class DopplerTracker(windowMs: Long = 10_000) { fun update(timeMs: Long, gapMs: Long?): Double /* semitones */ }` — keeps (time, |gap|) samples within the window; slope = (newest − oldest)/(Δtime) using timestamps; < 2 samples or Δtime < 2 s → 0.0; `gapMs == null` clears the window and returns 0.0.
  - `class DuckGate { fun update(gapMs: Long?): Boolean }` — true once |gap| < 30 000, stays true until |gap| > 40 000; null → false.

- [ ] **Step 1: Write the failing tests**
  - `intervalEndsAndMiddle`: `beepIntervalMs(0) == 250`, `beepIntervalMs(120_000) == 4000`, `beepIntervalMs(-120_000) == 4000`, `beepIntervalMs(600_000) == 4000`, `beepIntervalMs(60_000) == 2125`.
  - `patternBySide`: `beepPattern(5_000) == SINGLE`, `beepPattern(-5_000) == DOUBLE`.
  - `dopplerPositiveWhileClosing`: samples every 1 s for 10 s, |gap| 30 s → 28 s (slope −0.2 s/s) → `3.0` (clamped); slope −0.1 → `1.5` (±0.01).
  - `dopplerNegativeWhileOpening` and `dopplerDropsAtPass`: gap +4 s → +2 s → 0 → −2 s → −4 s at 1 s steps; value after the pass window turns negative.
  - `dopplerUsesTimestampsNotSampleCount`: two samples 30 s apart, |gap| 30 → 27 s → slope −0.1 → `1.5`; window of 10 s keeps only samples within 10 s of newest, so with samples at 0 s and 30 s only one remains → `0.0`.
  - `dopplerResetsOnNullGap`: after closing samples, `update(t, null) == 0.0` and next non-null sample returns `0.0`.
  - `duckHysteresis`: 50 s → false, 29 s → true, 35 s → true, 41 s → false, null → false.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.walkwalk.race.RaceSignalTest"` — Expected: compile FAIL.
- [ ] **Step 3: Implement** `RaceSignal.kt` per Interfaces.
- [ ] **Step 4: Run** same — Expected: PASS.
- [ ] **Step 5: Commit** (ask first) `feat: gap-to-sound mapping for racing mode`

### Task 3: RaceSession

**Files:**
- Create: `app/src/main/java/com/walkwalk/race/RaceSession.kt`
- Test: `app/src/test/java/com/walkwalk/race/RaceSessionTest.kt` (Robolectric + in-memory Room, set up like `GhostSessionTest`)

**Interfaces:**
- Consumes: Task 1 (`RaceCandidate`, `observe`, `pickRival`), `matchGhosts`, `aheadBehindMs`, `HikeRepository.ghostCandidates()/points()`.
- Produces:
  - `sealed interface RaceEvent { data object NoRival : RaceEvent; data class Switched(val rivalId: Long) : RaceEvent }`
  - `data class RaceState(val hikeId: Long?, val rivalId: Long?, val rivalName: String?, val gapMs: Long?, val timeMs: Long, val paused: Boolean, val event: RaceEvent?, val eventSeq: Int)` — `eventSeq` increments on each new event so a collector plays each event exactly once.
  - `class RaceSession(repo: HikeRepository, dispatcher: CoroutineDispatcher = Dispatchers.Default)` with
    - `val state: StateFlow<RaceState>`
    - `suspend fun update(hikeId: Long, track: List<TrackPoint>, elapsedMs: Long, distanceM: Double, paused: Boolean)`
    - `fun reset()` — clears everything (called on Stop / non-race hike).
    - `internal suspend fun awaitLoad()` (test hook, as in `GhostSession`).
- Behaviour:
  - New `hikeId` → reset and load candidates for `track.first()` in the session's own scope (load ignores the ghost-mode setting; skip hikes with < 2 points and the current hike id). On load, replay every point of the current `track` through each candidate's `observe` using `GhostTrack(track).elapsedAtIndex(i)` as clock (same pattern as `vanishMomentMs`), then `pickRival`.
  - No candidates / pick returns null at load → emit `NoRival` once.
  - Each update: `observe` all candidates with the last point; if the rival dropped → `pickRival` again → `Switched(newId)`, or `NoRival` if none (emitted once; no further events while rival-less).
  - `gapMs = aheadBehindMs(rival.track, distanceM, elapsedMs)` (null while paused is NOT required — `paused` flag carries that).
  - Load failure: log, retry after 30 s on a later update (as `GhostSession.LOAD_RETRY_MS`).

- [ ] **Step 1: Write the failing tests**
  - `picksFastestMatchingStart`: seed slow (1.0 m/s) and fast (1.2 m/s) walks from 47.0/11.0 plus one 5 km away; after first tick `rivalId == fast` and `event == null`.
  - `gapIsAheadBehindOfRival`: user at 100 m after 130 s vs 1.0 m/s rival → `gapMs ≈ 30_000` (±1000).
  - `noRivalWhenNothingMatches`: no seeded hikes → `event == NoRival`, `rivalId == null`; further ticks keep `eventSeq` unchanged.
  - `switchesWhenRivalRouteLeft` (Review Focus 3 partly): two routes share 0–100 m then fast one goes east; user follows the slow one; after 60 s off the fast route → `event == Switched(slowId)`, `rivalId == slowId`; `eventSeq` incremented exactly once.
  - `allDroppedGivesNoRivalOnce` (Review Focus 3): user leaves every route → one `NoRival`, then `eventSeq` stable over 5 more ticks.
  - `restoreReplaysTrackHistory` (Review Focus 1): first ever update arrives with a 10-minute track that left the fast route at minute 2 → rival after load is the slow one, and no `Switched` event (it was decided during replay).
  - `rivalFinishedGapKeepsGrowing` (Review Focus 2): rival 200 m in 200 s, user at 250 m, elapsed 300 s → `gapMs == 300_000 - 200_000`, and at 260 m/320 s → larger.
  - `pausedFlagPassedThrough` (Review Focus 5): `update(..., paused = true)` → `state.paused == true`.
  - `loadsWithGhostModeOff`: no `AppSettings` dependency at all — covered by construction; assert rival found with default prefs.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.walkwalk.race.RaceSessionTest"` — Expected: compile FAIL.
- [ ] **Step 3: Implement** `RaceSession.kt` per Interfaces; guard state with a `Mutex` as `GhostSession` does.
- [ ] **Step 4: Run** same — Expected: PASS.
- [ ] **Step 5: Commit** (ask first) `feat: race session picks and tracks a locked rival`

### Task 4: Race flag and start

**Files:**
- Modify: `app/src/main/java/com/walkwalk/settings/AppSettings.kt` (add race flag)
- Modify: `app/src/main/java/com/walkwalk/ghost/GhostSession.kt:118` (`startLoadLocked` setting check)
- Modify: `app/src/main/java/com/walkwalk/recording/RecordingService.kt` (new action)
- Modify: `app/src/main/java/com/walkwalk/ui/HikeListScreen.kt:66-99` (Race button)
- Modify: `app/src/main/java/com/walkwalk/MainActivity.kt:122-170` (start race flow)
- Test: `app/src/test/java/com/walkwalk/settings/AppSettingsTest.kt`, `app/src/test/java/com/walkwalk/ghost/GhostSessionTest.kt`

**Interfaces:**
- Produces:
  - `AppSettings.raceHikeId: StateFlow<Long?>`, `fun setRaceHikeId(id: Long?)` (key `"race_hike_id"`, persisted; null = remove key).
  - `RecordingService.ACTION_START_RACE = "com.walkwalk.START_RACE"`: `val id = controller.start(); app.settings.setRaceHikeId(id)`. `ACTION_START` sets `setRaceHikeId(null)` only when it creates/continues a hike whose id differs from the stored one.
  - `HikeListScreen(onStartRace: () -> Unit, …)` — `OutlinedButton("Race")` after "Start hike", through `startOrConfirm`.
  - `GhostSession.startLoadLocked`: load when `settings.ghostMode.value || settings.raceHikeId.value == forHike`.

- [ ] **Step 1: Write the failing tests**
  - `AppSettingsTest.raceHikeIdPersists`: set 7 → new `AppSettings` on same prefs reads 7; set null → reads null.
  - `GhostSessionTest.racingHikeLoadsGhostsWithGhostModeOff`: `setGhostMode(false)`, `setRaceHikeId(1)`, seed `oldWalk`, tick hike 1 → one view; tick hike 2 → none.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.walkwalk.settings.AppSettingsTest" --tests "com.walkwalk.ghost.GhostSessionTest"` — Expected: compile FAIL.
- [ ] **Step 3: Implement** settings + GhostSession change; add the action, button and `startRaceWithPermissions` (copy of `startWithPermissions` sending `ACTION_START_RACE`).
- [ ] **Step 4: Run** same plus `./gradlew assembleDebug` — Expected: PASS, build OK.
- [ ] **Step 5: Commit** (ask first) `feat: start a hike as a race`

### Task 5: RaceAudio and service wiring

**Files:**
- Create: `app/src/main/java/com/walkwalk/race/RaceAudio.kt`
- Modify: `app/src/main/java/com/walkwalk/WalkwalkApp.kt` (add `val race: RaceSession by lazy { RaceSession(repository) }`)
- Modify: `app/src/main/java/com/walkwalk/recording/RecordingService.kt` (feed session, run audio)
- Modify: `app/src/main/java/com/walkwalk/ui/RecordingScreen.kt` (small "Racing <name>" line in the ghost card when `race.state.rivalName != null`)

**Interfaces:**
- Consumes: `RaceState`, `RaceEvent` (Task 3); `beepIntervalMs`, `beepPattern`, `DopplerTracker`, `DuckGate` (Task 2); `AppSettings.raceHikeId` (Task 4).
- Produces: `class RaceAudio(context: Context, scope: CoroutineScope) { fun start(state: StateFlow<RaceState>); fun stop() }`.
- Audio (builder's call within these bounds): `AudioTrack` mono 44.1 kHz, `AudioAttributes` usage `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, content `CONTENT_TYPE_SONIFICATION`. Beep = ~60 ms sine, short attack/release envelope, base ~1000 Hz × 2^(semitones/12). DOUBLE = two beeps 70 ms apart. `Switched` = short rising three-note chirp; `NoRival` = one low ~400 Hz 300 ms tone. Focus: `AudioFocusRequest(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)` requested when `DuckGate` turns true, abandoned when false and on `stop()`.
- Loop: one coroutine; each turn reads `state.value`; plays a new event if `eventSeq` changed; if `paused` or `gapMs == null` → `DopplerTracker.update(now, null)`, delay 250 ms; else beep with `beepPattern`/Doppler bend, then delay `beepIntervalMs(gapMs)`.
- Service: inside the existing `controller.state` collector, when `s != null && s.hikeId == settings.raceHikeId.value`: `try { app.race.update(s.hikeId, s.track, s.elapsedMs, s.distanceM, s.status == HikeStatus.PAUSED) } catch (…) { Log.w }` and start `RaceAudio` once; on `s == null` (Stop) or `onDestroy`: `audio.stop()`, `app.race.reset()`, `settings.setRaceHikeId(null)` only on Stop (not on destroy, so restore can resume).

- [ ] **Step 1: Build** `./gradlew testDebugUnitTest assembleDebug` — Expected: all tests PASS, build OK.
- [ ] **Step 2: Manual check on the emulator with the Demo route** (debug "Demo" start drives a bundled route; seed one older hike on the same route, e.g. a previous demo run): start a race → hear beeps; screen off → beeps continue; pause → silent; resume → beeps again; play music in another app → it ducks when close. Record what was heard.
- [ ] **Step 3: Commit** (ask first) `feat: racing mode beeper`

## Self-review notes

- Spec coverage: start per hike (T4), rival filter/pace/lock/switch/no-rival (T1, T3), rate/pattern/Doppler (T2, T5), ducking (T2, T5), service-driven with screen off (T5), ghost mode off still works and shows ghosts (T3 load, T4 GhostSession), pause silence (T3, T5), failures logged (T3, T5).
- The Demo start currently has no race variant; the manual check in Task 5 may need a debug "Race demo" — builder's call (add `app.demo.enable()` before `ACTION_START_RACE`).
