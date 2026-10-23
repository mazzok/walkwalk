# walkwalk Hike Tracker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A personal Android app (Pixel 9a) that records hikes reliably and replays them on a topographic map with a time scrubber, elevation profile and live numbers.

**Architecture:** Single Gradle module `app`. All logic is pure Kotlin under `com.walkwalk.domain`, with no Android imports, so it can be unit-tested on the JVM: geo math, altitude, stops, stats, replay timeline and GPX. Android shells sit around it: Room storage (`data`), a foreground recording service (`recording`), MapLibre map (`map`) and Compose screens (`ui`). Altitude, stops and stats are computed from stored raw points, so they can always be recomputed.

**Tech Stack:** Kotlin 2.1.0, AGP 8.7.3, Gradle 8.11.1, Jetpack Compose (BOM 2024.12.01), Navigation Compose 2.8.5, Room 2.6.1 + KSP 2.1.0-1.0.29, Coroutines 1.9.0, MapLibre Android SDK 11.8.0 (`org.maplibre.gl:android-sdk`), OkHttp 4.12.0, JUnit 4.13.2, Robolectric 4.14.1, kotlinx-coroutines-test 1.9.0. If a pinned version fails to resolve, use the nearest newer stable version and note it in the commit message.

**Spec:** `docs/superpowers/specs/2026-10-23-hike-tracker-design.md`

## Global Constraints

- Package / applicationId: `com.walkwalk`. `minSdk 34`, `compileSdk 35`, `targetSdk 35`. JVM target 17.
- Local install only (debug build). No Play Store, no signing config beyond debug.
- Toolchain on this machine: JDK 17 on PATH (`D:/Tools/java/jdk-17`). Android SDK at `%LOCALAPPDATA%/Android/Sdk` (platforms 35 and 37). `adb` is at `%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe` (not on PATH). There is no global `gradle`, so Task 1 bootstraps the wrapper.
- Recording never depends on network. No background service and no GPS when no hike is recording. A recording ends **only** when the user presses Stop.
- Tiles: OpenTopoMap `https://tile.opentopomap.org/{z}/{x}/{y}.png` (maxzoom 17) + Waymarked Trails `https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png` overlay. Send User-Agent `walkwalk/0.1 (personal hiking app)`. No bulk prefetch. Show attribution "Map data © OpenStreetMap contributors, SRTM | Map style © OpenTopoMap (CC-BY-SA) | © waymarkedtrails.org".
- DEM: Open-Meteo `https://api.open-meteo.com/v1/elevation?latitude=..&longitude=..`, max 100 coordinates per request.
- All timestamps are epoch milliseconds (`Long`), distances in metres (`Double`), speeds in km/h (`Double`) at the UI boundary only.
- Per the user's global rules: **every commit step needs the user's explicit approval** before running `git commit`.

## Review Focus

1. **Bad GPS fixes** (accuracy worse than 50 m, or a jump implying more than 30 km/h on foot) must not show up as spikes in the track, distance or speed. They are dropped at ingestion. Test: Task 2 `filtersInaccurateAndTeleportingFixes`.
2. **Tiny hikes:** if Stop is pressed after 0 or 1 points, list, replay, stats and GPX export must not crash. Tests: Task 4 `statsOfEmptyAndSinglePointTrack`, Task 5 `timelineWithSinglePoint`.
3. **Real-world GPX** (Garmin/Komoot: default namespace, `<extensions>`, multiple `<trk>`, missing `<time>` or `<ele>`, `<rte>` only) must import without crashing. Tests: Task 6 `parsesGarminFileWithExtensions`, `parsesFileWithoutTimes`.
4. **The DEM call fails or is offline,** or the track has more than 100 points. The hike keeps its recorded altitude, is marked uncorrected, and can be corrected later. Test: Task 8 `correctionSkippedWhenApiFails`, `requestsAreBatchedBy100`.
5. **Process killed mid-hike, then the service restarts.** The same hike continues as a new segment, and the gap is treated like a pause. Test: Task 9 `restartContinuesActiveHikeInNewSegment`.

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew(.bat), gradle/wrapper/*
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/walkwalk/
  WalkwalkApp.kt                      Application: DB + MapLibre init
  MainActivity.kt                     Compose host + navigation
  domain/
    TrackPoint.kt                     core data types
    Geo.kt                            haversine, filtering
    Altitude.kt                       smoothing, baro fusion, ascent/descent
    Stops.kt                          stop detection
    Stats.kt                          speed series, HikeStats
    ReplayTimeline.kt                 compressed replay time ↔ real time, interpolation
    Gpx.kt                            GpxWriter, GpxParser
  data/
    Entities.kt, HikeDao.kt, WalkwalkDb.kt, HikeRepository.kt
    DemClient.kt                      Open-Meteo client + AltitudeCorrector
  recording/
    RecordingController.kt            pure state machine (testable)
    LocationSource.kt                 GPS + barometer adapters
    RecordingService.kt               foreground service (location type)
    BootReceiver.kt
  map/
    TopoMap.kt                        MapLibre composable, style, cache, UA
  ui/
    HikeListScreen.kt, RecordingScreen.kt, ReplayScreen.kt, ElevationProfile.kt, Permissions.kt
app/src/test/java/com/walkwalk/...    JVM + Robolectric tests mirroring the packages
app/src/test/resources/gpx/           garmin.gpx, no_times.gpx, route_only.gpx
```

---

### Task 1: Project scaffold that builds and runs a test

**Files:**
- Create: everything under the root build files above, `app/build.gradle.kts`, `AndroidManifest.xml`, `WalkwalkApp.kt`, `MainActivity.kt` (shows the text "walkwalk")
- Test: `app/src/test/java/com/walkwalk/SmokeTest.kt`

**Interfaces:**
- Produces: buildable `app` module with all Tech Stack dependencies declared in `libs.versions.toml`. KSP plugin applied for Room. `testOptions.unitTests.isIncludeAndroidResources = true` for Robolectric.

- [ ] **Step 1: Bootstrap the Gradle wrapper.** Download `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip` into the scratchpad, unzip, and run `<unzipped>/bin/gradle wrapper --gradle-version 8.11.1` in the repo root. Expected: `gradlew`, `gradlew.bat` and `gradle/wrapper/` exist.
- [ ] **Step 2: Write `local.properties`** with `sdk.dir` pointing at `%LOCALAPPDATA%/Android/Sdk` (forward slashes), and add `local.properties`, `build/`, `.gradle/` and `app/build/` to `.gitignore`.
- [ ] **Step 3: Write the build files and the minimal app** (manifest declares `WalkwalkApp` and `MainActivity` as launcher).
- [ ] **Step 4: Write `SmokeTest.smokeTest()`** asserting `1 + 1 == 2`.
- [ ] **Step 5: Run** `./gradlew testDebugUnitTest assembleDebug`. Expected: `BUILD SUCCESSFUL`, `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 6: Commit** (with user approval): `chore: scaffold Android project`.

---

### Task 2: Core types and geo math

**Files:**
- Create: `domain/TrackPoint.kt`, `domain/Geo.kt`
- Test: `app/src/test/java/com/walkwalk/domain/GeoTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class TrackPoint(
      val time: Long, val lat: Double, val lon: Double,
      val gpsAltitude: Double?,      // metres above sea level (MSL) when available
      val pressureHpa: Double?,
      val accuracy: Float?,          // horizontal, metres
      val segment: Int,
      val altitude: Double? = null,  // fused/corrected altitude, filled by Altitude.kt / corrector
  )
  object Geo {
      fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double   // haversine, R = 6_371_000.0
      fun cumulativeDistances(points: List<TrackPoint>): DoubleArray                  // [0]=0; no distance added across segment boundaries
      fun acceptFix(prev: TrackPoint?, next: TrackPoint): Boolean                     // ingestion filter
  }
  const val MAX_ACCURACY_M = 50f
  const val MAX_WALK_SPEED_MS = 30.0 / 3.6
  ```

- [ ] **Step 1: Write the failing tests**
  ```kotlin
  @Test fun distanceOneDegreeLatitude() = assertEquals(111_195.0, Geo.distance(47.0, 11.0, 48.0, 11.0), 50.0)
  @Test fun cumulativeIgnoresSegmentGaps() {
      val pts = listOf(p(0, 47.0, 11.0, seg = 0), p(1000, 47.001, 11.0, seg = 0), p(2000, 48.0, 11.0, seg = 1))
      val d = Geo.cumulativeDistances(pts)
      assertEquals(111.2, d[1], 0.5); assertEquals(d[1], d[2], 0.001)
  }
  @Test fun filtersInaccurateAndTeleportingFixes() {
      val a = p(0, 47.0, 11.0, acc = 5f)
      assertFalse(Geo.acceptFix(a, p(2000, 47.0001, 11.0, acc = 80f)))   // accuracy > 50
      assertFalse(Geo.acceptFix(a, p(2000, 47.01, 11.0, acc = 5f)))      // 1.1 km in 2 s
      assertTrue(Geo.acceptFix(a, p(2000, 47.00002, 11.0, acc = 5f)))
      assertTrue(Geo.acceptFix(null, p(0, 47.0, 11.0, acc = 20f)))
  }
  ```
  (`p(...)` is a test helper building a `TrackPoint` with defaults.)
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.walkwalk.domain.GeoTest"`. Expected: FAIL (unresolved references).
- [ ] **Step 3: Implement** the types and `Geo`. A null accuracy counts as acceptable.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit** (with user approval): `feat: track point model and geo math`.

---

### Task 3: Altitude (baro fusion, smoothing, ascent/descent) and stop detection

**Files:**
- Create: `domain/Altitude.kt`, `domain/Stops.kt`
- Test: `domain/AltitudeTest.kt`, `domain/StopsTest.kt`

**Interfaces:**
- Consumes: `TrackPoint`, `Geo.distance`
- Produces:
  ```kotlin
  object Altitude {
      fun baroAltitude(pressureHpa: Double): Double          // ISA: 44330 * (1 - (p / 1013.25)^(1/5.255))
      fun fuse(points: List<TrackPoint>): List<TrackPoint>   // fills .altitude
      fun smooth(values: DoubleArray, window: Int = 5): DoubleArray   // centred moving average, edges use available neighbours
      fun ascentDescent(smoothed: DoubleArray, threshold: Double = 5.0): Pair<Double, Double>
  }
  data class Stop(val start: Long, val end: Long)
  object Stops {
      const val RADIUS_M = 20.0
      const val MIN_DURATION_MS = 120_000L
      fun detect(points: List<TrackPoint>): List<Stop>
  }
  ```
- **`fuse` rule:** if at least 80% of points have `pressureHpa`, use the barometer path. Anchor = mean `gpsAltitude` of the first 10 points that have one. Altitude = anchor + baroAltitude(p) − baroAltitude(p at the first anchor point); points without pressure get linear interpolation. Otherwise use `gpsAltitude` directly. If neither exists, `altitude` stays null.
- **`ascentDescent` rule (hysteresis):** `ref = v[0]`. For each v: if `v - ref >= threshold` then ascent += v − ref and ref = v; if `ref - v >= threshold` then descent += ref − v and ref = v.
- **`detect` rule:** anchor index i. Extend j while `distance(anchor, p[j]) <= RADIUS_M` and the same segment. If `p[j_last].time - p[i].time >= MIN_DURATION_MS`, emit `Stop(p[i].time, p[j_last].time)` and continue at j_last + 1; otherwise i++.

- [ ] **Step 1: Write the failing tests**
  ```kotlin
  @Test fun baroAltitudeAtStandardPressure() = assertEquals(0.0, Altitude.baroAltitude(1013.25), 0.01)
  @Test fun baroAltitudeAt900hPa() = assertEquals(989.0, Altitude.baroAltitude(900.0), 1.0)
  @Test fun fuseUsesBaroShapeAnchoredToGps() {
      // gps noisy around 1000, pressure falling by 1 hPa (~9.2 m up at 900 hPa)
      val pts = (0 until 12).map { i -> p(i * 2000L, gpsAlt = 1000.0 + if (i % 2 == 0) 15 else -15, pressure = 900.0 - i / 11.0) }
      val f = Altitude.fuse(pts)
      assertEquals(1000.0, f.first().altitude!!, 1.0)
      assertEquals(f.first().altitude!! + 9.2, f.last().altitude!!, 0.5)
  }
  @Test fun ascentIgnoresJitterBelowThreshold() {
      val v = doubleArrayOf(100.0, 103.0, 99.0, 102.0, 100.0)
      assertEquals(0.0 to 0.0, Altitude.ascentDescent(v))
  }
  @Test fun ascentCountsRealClimb() {
      val v = doubleArrayOf(100.0, 110.0, 120.0, 115.0, 100.0)
      assertEquals(20.0 to 20.0, Altitude.ascentDescent(v))
  }
  @Test fun detectsLunchStop() {
      // walk 5 min (moving ~1 m/s), stand 10 min within 5 m, walk 5 min, points every 5 s
      val stops = Stops.detect(walkStandWalk())
      assertEquals(1, stops.size)
      assertEquals(600_000.0, (stops[0].end - stops[0].start).toDouble(), 15_000.0)
  }
  @Test fun shortPauseIsNotAStop() = assertTrue(Stops.detect(walkStandWalk(standMs = 60_000)).isEmpty())
  ```
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.walkwalk.domain.*"`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement** `Altitude` and `Stops` per the rules above.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit** (with user approval): `feat: altitude fusion, ascent hysteresis, stop detection`.

---

### Task 4: Speed series and hike statistics

**Files:**
- Create: `domain/Stats.kt`
- Test: `domain/StatsTest.kt`

**Interfaces:**
- Consumes: `Geo`, `Altitude`, `Stop`
- Produces:
  ```kotlin
  data class HikeStats(
      val distanceM: Double, val totalMs: Long, val movingMs: Long,
      val ascentM: Double, val descentM: Double,
      val minAltM: Double?, val maxAltM: Double?,
      val avgSpeedKmh: Double, val maxSpeedKmh: Double,   // avg = distance / movingMs
  )
  object Stats {
      fun speedsKmh(points: List<TrackPoint>, windowMs: Long = 30_000): DoubleArray  // distance over ±windowMs/2 within segment ÷ time
      fun compute(points: List<TrackPoint>, stops: List<Stop>): HikeStats
  }
  ```
- `totalMs` = the sum over segments of (last.time − first.time), so manual pauses are excluded. `movingMs` = totalMs − the sum of stop durations. Ascent and min/max come from `Altitude.smooth` of non-null `altitude`. Speed is 0 inside stops.

- [ ] **Step 1: Write the failing tests**
  ```kotlin
  @Test fun statsOfEmptyAndSinglePointTrack() {
      val e = Stats.compute(emptyList(), emptyList())
      assertEquals(0.0, e.distanceM, 0.0); assertEquals(0L, e.totalMs); assertNull(e.maxAltM)
      val one = Stats.compute(listOf(p(0, 47.0, 11.0, alt = 900.0)), emptyList())
      assertEquals(0L, one.totalMs); assertEquals(900.0, one.maxAltM!!, 0.0)
  }
  @Test fun manualPauseExcludedStopsExcludedFromMoving() {
      // seg0: 0..600s, seg1: 1800..2400s, stop 100..220s
      val s = Stats.compute(twoSegments(), listOf(Stop(100_000, 220_000)))
      assertEquals(1_200_000L, s.totalMs); assertEquals(1_080_000L, s.movingMs)
  }
  @Test fun steadyWalkSpeed() {
      val speeds = Stats.speedsKmh(straightLine(metersPerSec = 1.25, everyMs = 2000, count = 100))
      assertEquals(4.5, speeds[50], 0.1)
  }
  ```
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.walkwalk.domain.StatsTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** `Stats`.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit** (with user approval): `feat: speed series and hike stats`.

---

### Task 5: Replay timeline

**Files:**
- Create: `domain/ReplayTimeline.kt`
- Test: `domain/ReplayTimelineTest.kt`

**Interfaces:**
- Consumes: `TrackPoint`, `Stop`, `Geo.cumulativeDistances`, `Stats.speedsKmh`
- Produces:
  ```kotlin
  data class ReplayFrame(
      val clockTime: Long, val elapsedMs: Long,       // elapsed = real time since first point
      val lat: Double, val lon: Double,
      val altitude: Double?, val speedKmh: Double,
      val distanceM: Double, val ascentM: Double,     // so far
      val inStop: Boolean,
  )
  class ReplayTimeline(points: List<TrackPoint>, stops: List<Stop>) {
      val durationMs: Long                              // length of compressed replay time
      fun frameAt(replayMs: Long): ReplayFrame          // clamps to [0, durationMs]
      fun replayMsForDistance(distanceM: Double): Long  // for scrubbing on the profile
      val stopMarkers: List<LongRange>                  // stops in replay ms
      companion object { const val STOP_REPLAY_MS = 30_000L }   // each stop plays as 30 s of hike time
  }
  ```
- **Compression:** moving time maps 1:1. Each detected stop collapses to `STOP_REPLAY_MS`. A manual-pause gap between segments collapses to 0, so the clock jumps. Position between points is interpolated linearly in lat/lon. Inside a stop the position is fixed at the stop start, and the clock advances proportionally through the real stop. Precompute cumulative arrays so `frameAt` is a binary search with no per-call allocations, to keep scrubbing smooth.

- [ ] **Step 1: Write the failing tests**
  ```kotlin
  @Test fun timelineWithSinglePoint() {
      val t = ReplayTimeline(listOf(p(1000, 47.0, 11.0)), emptyList())
      assertEquals(0L, t.durationMs); assertEquals(1000L, t.frameAt(500).clockTime)
  }
  @Test fun interpolatesBetweenPoints() {
      val t = ReplayTimeline(listOf(p(0, 47.0, 11.0), p(10_000, 47.001, 11.0)), emptyList())
      val f = t.frameAt(5_000)
      assertEquals(47.0005, f.lat, 1e-6); assertEquals(5_000L, f.clockTime)
  }
  @Test fun stopIsCompressed() {
      // 10 min walk, 30 min stop, 10 min walk
      val t = ReplayTimeline(walkStandWalk(walkMs = 600_000, standMs = 1_800_000), listOf(Stop(600_000, 2_400_000)))
      assertEquals(1_200_000L + ReplayTimeline.STOP_REPLAY_MS, t.durationMs)
      assertTrue(t.frameAt(600_000 + 15_000).inStop)
      assertEquals(2_400_000L + 10_000, t.frameAt(600_000 + 30_000 + 10_000).clockTime)
  }
  @Test fun manualPauseGapIsSkipped() {
      val t = ReplayTimeline(twoSegments(), emptyList())        // seg0 0..600s, seg1 1800..2400s
      assertEquals(1_200_000L, t.durationMs)
      assertEquals(1_800_000L, t.frameAt(600_001).clockTime, 2_000)
  }
  ```
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.walkwalk.domain.ReplayTimelineTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** `ReplayTimeline`.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit** (with user approval): `feat: compressed replay timeline`.

---

### Task 6: GPX import and export

**Files:**
- Create: `domain/Gpx.kt`
- Test: `domain/GpxTest.kt`, resources `gpx/garmin.gpx` (Garmin-style: GPX 1.1 default namespace, `<extensions>` with `gpxtpx:TrackPointExtension`, 2 `<trk>`), `gpx/no_times.gpx`, `gpx/route_only.gpx` (`<rte>/<rtept>` only)

**Interfaces:**
- Produces:
  ```kotlin
  data class ParsedGpx(val name: String?, val points: List<TrackPoint>, val hasTimes: Boolean)
  object GpxWriter { fun write(name: String, points: List<TrackPoint>, out: java.io.OutputStream) }
  object GpxParser { fun parse(input: java.io.InputStream): ParsedGpx }   // throws GpxException on non-GPX / no points
  class GpxException(msg: String) : Exception(msg)
  ```
- Parse with `XmlPullParser` (`org.xmlpull.v1`, available on Android; Robolectric provides it in tests), matching on local names and ignoring namespaces. Each `<trkseg>` (in every `<trk>`) and each `<rte>` becomes a new `segment`. Without times, assign synthetic `time = index * 1000` and set `hasTimes = false`. Writer: GPX 1.1, `creator="walkwalk"`, one `<trkseg>` per segment, `<ele>` = `altitude ?: gpsAltitude` (omitted if null), and `<time>` in ISO-8601 UTC.

- [ ] **Step 1: Write the failing tests** (Robolectric runner: `@RunWith(RobolectricTestRunner::class)`)
  ```kotlin
  @Test fun roundTrip() {
      val pts = listOf(p(1_700_000_000_000, 47.1, 11.2, alt = 812.5, seg = 0), p(1_700_000_005_000, 47.1001, 11.2, alt = 813.0, seg = 1))
      val out = ByteArrayOutputStream(); GpxWriter.write("Test", pts, out)
      val back = GpxParser.parse(out.toByteArray().inputStream())
      assertEquals("Test", back.name); assertTrue(back.hasTimes)
      assertEquals(pts.map { it.time }, back.points.map { it.time })
      assertEquals(listOf(0, 1), back.points.map { it.segment })
      assertEquals(812.5, back.points[0].gpsAltitude!!, 0.01)
  }
  @Test fun parsesGarminFileWithExtensions() {
      val g = GpxParser.parse(res("gpx/garmin.gpx")); assertTrue(g.hasTimes); assertEquals(2, g.points.map { it.segment }.distinct().size)
  }
  @Test fun parsesFileWithoutTimes() { val g = GpxParser.parse(res("gpx/no_times.gpx")); assertFalse(g.hasTimes); assertTrue(g.points.isNotEmpty()) }
  @Test fun parsesRouteOnly() = assertTrue(GpxParser.parse(res("gpx/route_only.gpx")).points.isNotEmpty())
  @Test(expected = GpxException::class) fun rejectsNonGpx() { GpxParser.parse("<html/>".byteInputStream()) }
  ```
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.walkwalk.domain.GpxTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** `GpxWriter` and `GpxParser`.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit** (with user approval): `feat: GPX import/export`.

---

### Task 7: Room storage and repository

**Files:**
- Create: `data/Entities.kt`, `data/HikeDao.kt`, `data/WalkwalkDb.kt`, `data/HikeRepository.kt`
- Test: `data/HikeRepositoryTest.kt` (Robolectric, `Room.inMemoryDatabaseBuilder`)

**Interfaces:**
- Consumes: `TrackPoint`, `Stop`, `HikeStats`, `Stops.detect`, `Altitude.fuse`, `Stats.compute`
- Produces:
  ```kotlin
  enum class HikeStatus { RECORDING, PAUSED, FINISHED }
  enum class HikeSource { RECORDED, IMPORTED }
  @Entity data class HikeEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
      val startTime: Long, val endTime: Long?, val status: HikeStatus, val source: HikeSource,
      val hasTimes: Boolean = true, val demCorrected: Boolean = false,
      // cached HikeStats columns:
      val distanceM: Double = 0.0, val totalMs: Long = 0, val movingMs: Long = 0, val ascentM: Double = 0.0,
      val descentM: Double = 0.0, val minAltM: Double? = null, val maxAltM: Double? = null,
      val avgSpeedKmh: Double = 0.0, val maxSpeedKmh: Double = 0.0)
  @Entity(indices = [Index("hikeId")]) data class TrackPointEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
      val hikeId: Long, val segment: Int, val time: Long, val lat: Double, val lon: Double,
      val gpsAltitude: Double?, val pressureHpa: Double?, val accuracy: Float?, val altitude: Double?)
  @Entity(indices = [Index("hikeId")]) data class StopEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val hikeId: Long, val start: Long, val end: Long)

  class HikeRepository(db: WalkwalkDb) {
      fun observeFinishedHikes(): Flow<List<HikeEntity>>                // newest first
      suspend fun activeHike(): HikeEntity?                            // status RECORDING or PAUSED
      suspend fun startHike(now: Long): Long                           // name = defaultName(now)
      suspend fun appendPoint(hikeId: Long, p: TrackPoint)             // single insert, written immediately
      suspend fun lastSegment(hikeId: Long): Int?                      // null if no points yet
      suspend fun setStatus(hikeId: Long, status: HikeStatus)
      suspend fun finish(hikeId: Long, now: Long)                      // fuse altitude, detect stops, compute stats, status FINISHED
      suspend fun recompute(hikeId: Long)                              // same processing, used after import / DEM correction
      suspend fun points(hikeId: Long): List<TrackPoint>
      suspend fun stops(hikeId: Long): List<Stop>
      suspend fun hike(hikeId: Long): HikeEntity?
      suspend fun rename(hikeId: Long, name: String)
      suspend fun delete(hikeId: Long)                                 // cascades points + stops
      suspend fun importGpx(parsed: ParsedGpx, fallbackName: String): Long
      suspend fun setCorrectedAltitudes(hikeId: Long, altitudes: List<Double>)   // same order as points(); sets demCorrected
      companion object { fun defaultName(startTime: Long, zone: ZoneId = ZoneId.systemDefault()): String }  // "Sat 12 Oct 2026, 09:00", Locale.ENGLISH
  }
  ```

- [ ] **Step 1: Write the failing tests**
  ```kotlin
  @Test fun defaultName() = assertEquals("Mon 12 Oct 2026, 09:00", HikeRepository.defaultName(ZonedDateTime.of(2026,10,12,9,0,0,0, ZoneOffset.UTC).toInstant().toEpochMilli(), ZoneOffset.UTC))
  @Test fun recordAndFinishComputesStats() = runTest {
      val id = repo.startHike(0); straightLine(1.25, 2000, 100).forEach { repo.appendPoint(id, it) }
      repo.finish(id, 200_000)
      val h = repo.hike(id)!!; assertEquals(HikeStatus.FINISHED, h.status); assertEquals(247.5, h.distanceM, 2.0)
      assertNull(repo.activeHike())
  }
  @Test fun activeHikeFoundAfterStart() = runTest { val id = repo.startHike(0); assertEquals(id, repo.activeHike()!!.id) }
  @Test fun deleteRemovesPoints() = runTest { val id = repo.startHike(0); repo.appendPoint(id, p(0, 47.0, 11.0)); repo.delete(id); assertTrue(repo.points(id).isEmpty()) }
  @Test fun importWithoutTimesMarksHike() = runTest { val id = repo.importGpx(ParsedGpx(null, someTimelessPoints(), false), "route.gpx"); assertFalse(repo.hike(id)!!.hasTimes); assertEquals("route.gpx", repo.hike(id)!!.name) }
  ```
  Format: `EEE d MMM yyyy, HH:mm` with `Locale.ENGLISH`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.walkwalk.data.HikeRepositoryTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** entities, DAO, DB (`WalkwalkDb.create(context)` builds `walkwalk.db`) and repository.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit** (with user approval): `feat: Room storage and hike repository`.

---

### Task 8: DEM altitude correction (Open-Meteo)

**Files:**
- Create: `data/DemClient.kt`
- Test: `data/DemClientTest.kt` (uses `okhttp3.mockwebserver.MockWebServer`; add `mockwebserver 4.12.0` as a test dependency)

**Interfaces:**
- Consumes: `HikeRepository.points / setCorrectedAltitudes / recompute`
- Produces:
  ```kotlin
  interface ElevationApi { suspend fun elevations(coords: List<Pair<Double, Double>>): List<Double> }   // throws IOException
  class OpenMeteoElevationApi(client: OkHttpClient, baseUrl: String = "https://api.open-meteo.com/") : ElevationApi  // batches of ≤100
  class AltitudeCorrector(api: ElevationApi, repo: HikeRepository) {
      suspend fun correct(hikeId: Long): Boolean    // false = skipped/failed, hike unchanged
  }
  ```
- **Correction rule (offset only):** sample up to 100 evenly spaced points. offset = median(dem_i − altitude_i) over samples where altitude is non-null. corrected = altitude + offset. If **all** altitudes are null (GPX without `<ele>`), fetch DEM for every point (batched) and use those directly. Then `recompute`. The response JSON is `{"elevation":[...]}`; parse with `org.json.JSONObject`.

- [ ] **Step 1: Write the failing tests**
  ```kotlin
  @Test fun requestsAreBatchedBy100() = runTest { server.enqueue(json(List(100){500.0})); server.enqueue(json(List(50){500.0}))
      assertEquals(150, api.elevations(List(150){47.0 to 11.0}).size); assertEquals(2, server.requestCount) }
  @Test fun offsetAppliedFromMedian() = runTest { /* altitudes 1000..1010, fake api returns +40 each */ assertTrue(corrector.correct(id)); assertEquals(1040.0, repo.points(id).first().altitude!!, 0.5); assertTrue(repo.hike(id)!!.demCorrected) }
  @Test fun correctionSkippedWhenApiFails() = runTest { val c = AltitudeCorrector(failingApi, repo); assertFalse(c.correct(id)); assertFalse(repo.hike(id)!!.demCorrected); assertEquals(1000.0, repo.points(id).first().altitude!!, 0.01) }
  @Test fun fillsMissingElevationFromDem() = runTest { /* imported hike, all altitudes null */ assertTrue(corrector.correct(id)); assertNotNull(repo.points(id).first().altitude) }
  ```
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.walkwalk.data.DemClientTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** it.
- [ ] **Step 4: Run the tests again.** Expected: PASS.
- [ ] **Step 5: Commit** (with user approval): `feat: DEM altitude correction via Open-Meteo`.

---

### Task 9: Recording controller and foreground service

**Files:**
- Create: `recording/RecordingController.kt`, `recording/LocationSource.kt`, `recording/RecordingService.kt`, `recording/BootReceiver.kt`. Modify: `AndroidManifest.xml`
- Test: `recording/RecordingControllerTest.kt` (Robolectric + in-memory repo, fake `LocationSource`, `TestScope`)

**Interfaces:**
- Consumes: `HikeRepository`, `Geo.acceptFix`, `Stats.compute`, `AltitudeCorrector`
- Produces:
  ```kotlin
  data class Fix(val time: Long, val lat: Double, val lon: Double, val mslAltitude: Double?, val accuracy: Float?)
  interface LocationSource { fun fixes(): Flow<Fix> }        // GPS; cold flow, collection = GPS on
  interface PressureSource { fun latestHpa(): Double? }       // null if no barometer
  data class LiveState(val hikeId: Long, val status: HikeStatus, val elapsedMs: Long, val distanceM: Double,
                       val altitudeM: Double?, val speedKmh: Double, val ascentM: Double, val track: List<TrackPoint>)
  class RecordingController(repo: HikeRepository, location: LocationSource, pressure: PressureSource,
                            scope: CoroutineScope, clock: () -> Long) {
      val state: StateFlow<LiveState?>
      suspend fun start(): Long
      suspend fun pause(); suspend fun resume()
      suspend fun stop(): Long                       // repo.finish, returns hikeId; GPS collection cancelled
      suspend fun restoreActive(): Boolean           // called on service (re)start with no explicit command
  }
  ```
- **Controller rules:** each accepted fix is written immediately via `appendPoint`, with the barometer pressure read at that moment. Pause cancels GPS collection and sets status PAUSED. Resume starts segment `lastSegment + 1`. `restoreActive`: if the active hike is RECORDING, continue in segment `lastSegment + 1`. If PAUSED, stay paused with no GPS. If there is none, return false. Live stats are cheap incremental values (distance, elapsed, last altitude, 30 s speed, and ascent by the hysteresis rule), not a full recompute per fix.
- **Android side:**
  - `RecordingService`: `foregroundServiceType="location"`. Commands are intents with actions `START`, `PAUSE`, `RESUME`, `STOP`.
  - `onStartCommand` returns `START_STICKY`. A null intent or no action means `restoreActive()`; if that returns false, `stopSelf()`.
  - The notification channel is `recording`. The notification shows elapsed time and distance (updated at most every 5 s), with Pause/Resume and an "Open" content intent. Stop is only in the app, so it can't be pressed by accident.
  - After `stop()`, launch `AltitudeCorrector.correct` in the app scope; failures are ignored. Then `stopForeground` and `stopSelf`.
  - `GpsLocationSource` uses `LocationManager.GPS_PROVIDER` with minTime 2000 ms and minDistance 0. When `!location.hasMslAltitude()`, call `android.location.altitude.AltitudeConverter().addMslAltitudeToLocation(context, location)` on `Dispatchers.IO` (catch IOException and leave the altitude null).
  - `BaroPressureSource` uses `Sensor.TYPE_PRESSURE` and keeps the latest value; if the phone has no sensor it returns null.
  - `BootReceiver` (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`): if `activeHike()` is non-null, call `startForegroundService` with no action.
  - Manifest permissions: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `INTERNET`.

- [ ] **Step 1: Write the failing tests**
  ```kotlin
  @Test fun fixesArePersistedImmediately() = runTest { val id = ctl.start(); fakeGps.emit(fix(0)); fakeGps.emit(fix(2000)); advanceUntilIdle(); assertEquals(2, repo.points(id).size) }
  @Test fun pauseStopsGpsAndResumeStartsNewSegment() = runTest { val id = ctl.start(); fakeGps.emit(fix(0)); ctl.pause(); assertFalse(fakeGps.collecting)
      ctl.resume(); fakeGps.emit(fix(60_000)); advanceUntilIdle(); assertEquals(listOf(0, 1), repo.points(id).map { it.segment }) }
  @Test fun restartContinuesActiveHikeInNewSegment() = runTest { val id = ctl.start(); fakeGps.emit(fix(0)); advanceUntilIdle()
      val ctl2 = newController(); assertTrue(ctl2.restoreActive()); fakeGps.emit(fix(300_000)); advanceUntilIdle()
      assertEquals(id, ctl2.state.value!!.hikeId); assertEquals(listOf(0, 1), repo.points(id).map { it.segment }) }
  @Test fun restoreWithNothingActiveReturnsFalse() = runTest { assertFalse(newController().restoreActive()) }
  @Test fun stopFinishesHike() = runTest { val id = ctl.start(); fakeGps.emit(fix(0)); val done = ctl.stop(); assertEquals(id, done); assertEquals(HikeStatus.FINISHED, repo.hike(id)!!.status); assertFalse(fakeGps.collecting) }
  @Test fun pressureStoredWithFix() = runTest { fakeBaro.value = 900.0; val id = ctl.start(); fakeGps.emit(fix(0)); advanceUntilIdle(); assertEquals(900.0, repo.points(id)[0].pressureHpa!!, 0.0) }
  ```
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.walkwalk.recording.*"`. Expected: FAIL.
- [ ] **Step 3: Implement** the controller, then the Android adapters, service, receiver and manifest.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest assembleDebug`. Expected: PASS and BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** (with user approval): `feat: recording controller and foreground service`.

---

### Task 10: Topographic map component

**Files:**
- Create: `map/TopoMap.kt`. Modify: `WalkwalkApp.kt` (MapLibre init, OkHttp client with User-Agent, ambient cache)
- Test: `map/TopoStyleTest.kt` (JVM: style JSON content)

**Interfaces:**
- Produces:
  ```kotlin
  object TopoStyle { fun json(): String }   // MapLibre style v8: raster source "otm" (tileSize 256, maxzoom 17), raster source "wmt" (maxzoom 18), layers otm then wmt, attribution strings from Global Constraints
  const val AMBIENT_CACHE_BYTES = 500L * 1024 * 1024
  @Composable fun TopoMap(
      modifier: Modifier,
      track: List<TrackPoint>,          // drawn as one red line (#CC3333, 4 dp); a new line per segment
      marker: Pair<Double, Double>?,    // blue dot (#0066CC, white outline)
      follow: Boolean,                  // camera follows marker
      fitTrackOnFirstLayout: Boolean,
      stopMarkers: List<Pair<Double, Double>> = emptyList(),
  )
  ```
- `WalkwalkApp.onCreate`: `MapLibre.getInstance(this)`; `HttpRequestUtil.setOkHttpClient(...)` with an interceptor setting `User-Agent: walkwalk/0.1 (personal hiking app)`; `OfflineManager.getInstance(this).setMaximumAmbientCacheSize(AMBIENT_CACHE_BYTES, callback)`.
- Wrap `MapView` in `AndroidView` and forward lifecycle events. Update the track and marker through GeoJSON sources, never by rebuilding the style, so marker moves stay cheap. With no tiles (offline, uncached), the track and marker still render on the background colour `#E9EFE4`.

- [ ] **Step 1: Write the failing test**
  ```kotlin
  @Test fun styleHasTopoAndTrailLayersWithAttribution() { val s = JSONObject(TopoStyle.json())
      val src = s.getJSONObject("sources"); assertTrue(src.getJSONObject("otm").getJSONArray("tiles").getString(0).contains("tile.opentopomap.org"))
      assertTrue(src.getJSONObject("wmt").getJSONArray("tiles").getString(0).contains("waymarkedtrails.org/hiking"))
      assertEquals(listOf("background", "otm", "wmt"), (0 until s.getJSONArray("layers").length()).map { s.getJSONArray("layers").getJSONObject(it).getString("id") })
      assertTrue(src.getJSONObject("otm").getString("attribution").contains("OpenTopoMap")) }
  ```
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.walkwalk.map.*"`. Expected: FAIL.
- [ ] **Step 3: Implement** `TopoStyle`, `TopoMap` and the app init.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest assembleDebug`. Expected: PASS and BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** (with user approval): `feat: topo map with trail overlay and tile cache`.

---

### Task 11: Screens (list, recording, replay), permissions, navigation

**Files:**
- Create: `ui/HikeListScreen.kt`, `ui/RecordingScreen.kt`, `ui/ReplayScreen.kt`, `ui/ReplayViewModel.kt`, `ui/ElevationProfile.kt`, `ui/Permissions.kt`. Modify: `MainActivity.kt` (NavHost: `list`, `recording`, `replay/{hikeId}`; handles `ACTION_VIEW`/`ACTION_SEND` of `.gpx`). Modify: `AndroidManifest.xml` (intent filters for GPX, `FileProvider` for export)
- Test: `ui/ReplayViewModelTest.kt` (JVM: scrub/play logic)

**Interfaces:**
- Consumes: everything above.
- Produces:
  ```kotlin
  class ReplayViewModel(repo: HikeRepository, hikeId: Long) : ViewModel() {
      val frame: StateFlow<ReplayFrame?>; val replayMs: StateFlow<Long>; val playing: StateFlow<Boolean>
      val speedFactor: StateFlow<Int>                          // one of SPEEDS
      fun seek(replayMs: Long); fun seekToDistance(m: Double); fun togglePlay(); fun setSpeed(f: Int)
      companion object { val SPEEDS = listOf(10, 60, 300) }    // default 60
  }
  ```
  Playback ticks every 50 ms, advancing `50 * speedFactor` ms of replay time, and stops at the end. Replay is disabled (`frame` from `frameAt(0)`, play hidden) when `hasTimes == false`.
- **Hike list:** rows show name, date, distance (km, 1 decimal), duration (h:mm) and ascent (m). Actions per row: rename, delete (with a confirm dialog) and export GPX (written to `cacheDir/export/<name>.gpx`, shared via `FileProvider` + `ACTION_SEND`). There is a **Start hike** button and an **Import GPX** button (`OpenDocument` with `*/*`; parse failures show a snackbar "Not a readable GPX file"). If `activeHike()` exists on launch, go straight to the recording screen (this is the resume path, since the recording continues until Stop).
- **Permissions (`Permissions.kt`), shown on Start hike, in order, each with a one-line explanation:**
  1. Fine location
  2. Notifications
  3. Background location ("Allow all the time", via the settings screen as Android requires)
  4. Ignore battery optimisations (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)

  Without fine location, recording can't start. If 3 or 4 is missing, recording starts but shows a persistent banner: "Recording may stop when the phone is in your pocket — allow location all the time and disable battery optimisation."
- **Recording screen (layout B):**
  - full-screen `TopoMap` with `follow = true` and a re-centre button when the user pans
  - a top floating card with elapsed time, distance, altitude, speed and ascent
  - a bottom card with Pause/Resume and Stop (Stop asks "Stop and save this hike?"; on confirm it navigates to `replay/{id}`)
  - it binds to the service's `RecordingController.state`
- **Replay screen (layout B):**
  - full-screen `TopoMap` with `fitTrackOnFirstLayout = true` and the marker from the frame
  - the top floating card shows clock time (HH:mm), elapsed (h:mm), altitude, speed, distance so far and ascent so far. Tapping it expands to the full `HikeStats` summary, with "Altitude corrected" or "Altitude not yet corrected" and a "Correct now" button when uncorrected
  - the bottom slim card holds `ElevationProfile` (Canvas: altitude vs distance, stop ranges shaded, vertical cursor, horizontal drag → `seekToDistance`), the play/pause button, a `Slider` over `0..durationMs` and a speed chip cycling through ×10, ×60 and ×300

- [ ] **Step 1: Write the failing tests**
  ```kotlin
  @Test fun playAdvancesBySpeedFactor() = runTest { vm.setSpeed(60); vm.togglePlay(); advanceTimeBy(1_000); assertEquals(60_000L, vm.replayMs.value, 3_000) }
  @Test fun playStopsAtEnd() = runTest { vm.seek(vm.durationMs - 100); vm.togglePlay(); advanceTimeBy(5_000); assertFalse(vm.playing.value); assertEquals(vm.durationMs, vm.replayMs.value) }
  @Test fun seekClamps() { vm.seek(-5); assertEquals(0L, vm.replayMs.value); vm.seek(Long.MAX_VALUE); assertEquals(vm.durationMs, vm.replayMs.value) }
  @Test fun timelessHikeCannotPlay() = runTest { timelessVm.togglePlay(); assertFalse(timelessVm.playing.value) }
  ```
  (Expose `val durationMs: Long` on the ViewModel for these tests.)
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.walkwalk.ui.*"`. Expected: FAIL.
- [ ] **Step 3: Implement** the ViewModels, screens, navigation, permissions, GPX intents and FileProvider.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest assembleDebug`. Expected: all tests PASS and BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** (with user approval): `feat: hike list, recording and replay screens`.

---

### Task 12: On-device verification (Pixel 9a)

**Files:** none (fix-ups only if something fails, each with its own failing test where it is logic)

- [ ] **Step 1: Install** with `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk`. Expected: `Success`.
- [ ] **Step 2: Check the permissions flow.** Start a hike and grant everything. Expected: the notification "Recording" appears.
- [ ] **Step 3: Simulate a kill mid-hike** with `adb shell am kill com.walkwalk`, then wait 1 min. Expected: the notification comes back and points keep arriving. Check with `adb shell run-as com.walkwalk sqlite3 databases/walkwalk.db "select segment,count(*) from TrackPointEntity group by segment"` (or a debug log line), which should show a new segment.
- [ ] **Step 4: Swipe the app away from Recents.** Expected: recording continues and the notification stays.
- [ ] **Step 5: Stop with no hike active.** Close the app and check `adb shell dumpsys activity services com.walkwalk`. Expected: no running service. A reboot does not start anything.
- [ ] **Step 6: Walk outdoors for about 10 minutes, including a stop of 3 minutes or more,** then press Stop. Expected:
  - replay opens and the marker follows the scrubber smoothly
  - the stop is shaded on the profile and passes quickly in playback
  - the summary shows "Altitude corrected"
- [ ] **Step 7: Test GPX in both directions.** Export, then import the exported file and a Garmin Connect export. Expected: both replay. A GPX without times shows the route and profile, with playback hidden.
- [ ] **Step 8: Test airplane mode** on a previously viewed area. Expected: cached tiles show and the track draws.
- [ ] **Step 9: Report results to the user.** Commit any fixes (with user approval).
