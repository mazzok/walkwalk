# Photo Landmarks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pin landmarks to places on a hike (live or afterwards), attach photos to them (camera while recording, or phone photos from the hike's time window), show them as round thumbnails under the pin, and send/receive whole hikes with landmarks and photos as one `.walkwalk` file.

**Architecture:** Two new Room tables (landmarks, landmark photos) plus a `bundleId` column on hikes, added by a real 1→2 migration. GPX gains waypoints in both directions. A `PhotoStore` wraps MediaStore (time-window query, photo state, camera target, private copies). A `HikeBundle` writes and reads the zip. The map draws pins as a MapLibre symbol layer and positions a Compose overlay (the photo strip) under the selected pin. A `LandmarksViewModel` holds the landmark state for both the recording and the replay screens.

**Tech Stack:** Existing: Kotlin 2.1.0, Compose BOM 2024.12.01, Room 2.6.1 + KSP, MapLibre 11.8.0, Robolectric 4.14.1. No new dependencies: thumbnails via `ContentResolver.loadThumbnail` / `ImageDecoder`, zip via `java.util.zip`, JSON via `org.json`.

**Spec:** `docs/superpowers/specs/2026-10-29-photo-landmarks-design.md`

## Global Constraints

- Every landmark uses the **same pin**: blue `#0066CC`; selected: orange `#E0A030` (same colours as the existing marker and stop dots). No types, names, notes or icon choice.
- Add on the map with **long-press** only. A plain tap on a pin selects it; a plain tap elsewhere closes the strip.
- Two photo sources only: **camera** (only while the hike's status is `RECORDING` or `PAUSED`) and **photos from this hike** (MediaStore, filtered by taken time only; not offered when `hasTimes == false`). No general gallery picker.
- Gallery photos are **referenced** (`content://` URI), never copied. Photos from a received hike are stored under `filesDir/photos/<hikeId>/`.
- Camera photos are saved to the gallery under `Pictures/walkwalk`.
- Thumbnail states: `AVAILABLE`, `MISSING` (grey circle, ⊘, offers **Remove**), `UNREADABLE` (lock, offers to allow access; never Remove).
- `.walkwalk` = zip with `hike.gpx`, `walkwalk.json` (`{"bundleId": "<uuid>", "format": 1}`) and `photos/<photoId>.jpg`; JPEG long edge ≤ **1600 px**, quality **80** *(plan's call)*. Missing photos are left out.
- Size check before sharing: warn when the file is over **25 MB** (`25 * 1024 * 1024` bytes).
- Same `bundleId` imported twice → open the existing hike.
- Plain GPX import turns `<wpt>` into landmarks (position, optional `<time>`); name/desc/sym and links in plain GPX are ignored.
- Database: version 2, `MIGRATION_1_2`, **never** `fallbackToDestructiveMigration`.
- Recording must keep running while the camera or the picker is in front (it's in a foreground service already, so nothing may pause or detach it from the UI side).
- Commits only with the user's approval (user's global rule). Commit steps below mean "ask, then commit".

## Review Focus

1. **Upgrade of a phone that already has hikes (v1 database)** → all hikes, points and stops survive; landmarks table usable. Test: Task 1 `MigrationTest.v1DataSurvivesUpgrade`.
2. **Photos without `DATE_TAKEN`** (screenshots, downloads, edited copies) → use `DATE_ADDED * 1000`, so they still appear in the time window. Test: Task 3 `PhotoStoreTest.takenAtFallsBackToDateAdded`.
3. **A hostile or broken `.walkwalk` file** (zip entry `../x`, photo linked but absent, no `hike.gpx`) → entries outside `photos/` are ignored, absent photos skipped, no `hike.gpx` → "Not a walkwalk file". Test: Task 4 `HikeBundleTest.ignoresZipSlipAndMissingPhotos`, `HikeBundleTest.rejectsZipWithoutGpx`.
4. **Deleting a received hike** → its `filesDir/photos/<hikeId>/` folder is removed, and importing the same file again creates it afresh (no dangling dedupe). Test: Task 4 `BundleImportTest.deleteThenReimport`.
5. **Camera cancelled or app killed while the camera is open** → no empty gallery entry left behind, nothing attached; the pending target survives process death. Test: Task 6 `LandmarksViewModelTest.cancelledCameraDiscardsEntry`.

---

### Task 1: Landmark storage and the 1→2 migration

**Files:**
- Modify: `app/src/main/java/com/walkwalk/data/Entities.kt` (add `bundleId` to `HikeEntity`, two entities)
- Create: `app/src/main/java/com/walkwalk/data/LandmarkDao.kt`
- Modify: `app/src/main/java/com/walkwalk/data/WalkwalkDb.kt` (version 2, `MIGRATION_1_2`, `landmarkDao()`)
- Modify: `app/src/main/java/com/walkwalk/data/HikeRepository.kt`
- Test: `app/src/test/java/com/walkwalk/data/MigrationTest.kt`, `app/src/test/java/com/walkwalk/data/LandmarkRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `HikeEntity.bundleId: String? = null`
  - `@Entity(indices=[Index("hikeId")]) data class LandmarkEntity(@PrimaryKey(autoGenerate=true) val id: Long = 0, val hikeId: Long, val lat: Double, val lon: Double, val time: Long?)`
  - `@Entity(indices=[Index("landmarkId")]) data class LandmarkPhotoEntity(@PrimaryKey(autoGenerate=true) val id: Long = 0, val landmarkId: Long, val uri: String, val takenAt: Long?)`
  - `data class Landmark(val id: Long, val lat: Double, val lon: Double, val time: Long?, val photos: List<LandmarkPhotoEntity>)`
  - `HikeRepository.observeLandmarks(hikeId: Long): Flow<List<Landmark>>` (ordered by `time` nulls last, then `id`)
  - `HikeRepository.landmarks(hikeId: Long): List<Landmark>`
  - `HikeRepository.addLandmark(hikeId: Long, lat: Double, lon: Double, time: Long?): Long`
  - `HikeRepository.addLandmarkAt(hikeId: Long, lat: Double, lon: Double): Long` — time = time of the nearest track point (`Geo.distance`) when `hike.hasTimes`, else null
  - `HikeRepository.attachPhotos(landmarkId: Long, photos: List<Pair<String, Long?>>)` — skips URIs already on that landmark
  - `HikeRepository.removePhoto(photoId: Long)`, `HikeRepository.deleteLandmark(landmarkId: Long)`
  - `HikeRepository.hikeByBundleId(bundleId: String): HikeEntity?`, `HikeRepository.ensureBundleId(hikeId: Long): String` (assigns a random UUID once)
  - `HikeRepository.delete(hikeId)` now also deletes the hike's landmarks and photo rows
  - `WalkwalkDb.MIGRATION_1_2: Migration`

- [ ] **Step 1: Write the failing migration test**

`MigrationTest.v1DataSurvivesUpgrade` (Robolectric): create file DB `migration-test.db` with `SQLiteDatabase.openOrCreateDatabase`, run the v1 schema below, `PRAGMA user_version = 1`, insert one hike (`name='Old hike'`, `status='FINISHED'`, `source='RECORDED'`, other NOT NULL columns 0), two track points and one stop. Then open it with `Room.databaseBuilder(ctx, WalkwalkDb::class.java, "migration-test.db").addMigrations(WalkwalkDb.MIGRATION_1_2).allowMainThreadQueries().build()` and assert:
- `repo.finishedHikes().single().name == "Old hike"` and its `bundleId == null`
- `repo.points(id).size == 2`, `repo.stops(id).size == 1`
- `repo.addLandmark(id, 48.0, 16.0, null)` succeeds and `repo.landmarks(id).single().lat == 48.0`

v1 schema (from Room's generated code at commit `21f87f7`, identity hash `f8b83a09c7403889253fcbc690b3f2f5`):

```sql
CREATE TABLE IF NOT EXISTS `HikeEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `status` TEXT NOT NULL, `source` TEXT NOT NULL, `hasTimes` INTEGER NOT NULL, `demCorrected` INTEGER NOT NULL, `distanceM` REAL NOT NULL, `totalMs` INTEGER NOT NULL, `movingMs` INTEGER NOT NULL, `ascentM` REAL NOT NULL, `descentM` REAL NOT NULL, `minAltM` REAL, `maxAltM` REAL, `avgSpeedKmh` REAL NOT NULL, `maxSpeedKmh` REAL NOT NULL);
CREATE TABLE IF NOT EXISTS `TrackPointEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hikeId` INTEGER NOT NULL, `segment` INTEGER NOT NULL, `time` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `gpsAltitude` REAL, `pressureHpa` REAL, `accuracy` REAL, `altitude` REAL);
CREATE INDEX IF NOT EXISTS `index_TrackPointEntity_hikeId` ON `TrackPointEntity` (`hikeId`);
CREATE TABLE IF NOT EXISTS `StopEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hikeId` INTEGER NOT NULL, `start` INTEGER NOT NULL, `end` INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS `index_StopEntity_hikeId` ON `StopEntity` (`hikeId`);
CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT);
INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'f8b83a09c7403889253fcbc690b3f2f5');
```

- [ ] **Step 2: Write the failing repository tests** in `LandmarkRepositoryTest` (in-memory DB as in `HikeRepositoryTest`):
  - `addLandmarkAtTakesNearestPointTime`: import a `straightLine(...)` hike, `addLandmarkAt` at the 3rd point's coordinates → `time == points[2].time`
  - `addLandmarkAtOnRouteWithoutTimesHasNoTime`: import `ParsedGpx(..., hasTimes = false)` → `time == null`
  - `attachPhotosSkipsDuplicates`: attach `("content://a", 1L)` twice → one photo
  - `deleteHikeDeletesLandmarksAndPhotos`: after `repo.delete(id)`, `repo.landmarks(id)` is empty and no orphan `LandmarkPhotoEntity` rows remain (query via DAO)
  - `ensureBundleIdIsStable`: two calls return the same non-blank string; `hikeByBundleId(it)?.id == id`

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.walkwalk.data.MigrationTest" --tests "com.walkwalk.data.LandmarkRepositoryTest"`
Expected: compilation FAIL (`LandmarkEntity`, `MIGRATION_1_2` unresolved).

- [ ] **Step 4: Implement** the entities, `LandmarkDao`, `WalkwalkDb` version 2 with `.addMigrations(MIGRATION_1_2)` in `create()`, and the repository methods above. `MIGRATION_1_2` runs `ALTER TABLE HikeEntity ADD COLUMN bundleId TEXT` and creates both tables plus their indices exactly as Room would (`index_LandmarkEntity_hikeId`, `index_LandmarkPhotoEntity_landmarkId`); Room validates them on open, so a mismatch fails the migration test. `observeLandmarks` uses a Room `@Transaction` query with an `@Relation` POJO or two flows combined; the choice is free.

- [ ] **Step 5: Run the tests to verify they pass, plus the whole suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 6: Commit** (with approval): `feat: landmark storage with 1→2 migration`

---

### Task 2: GPX waypoints in and out

**Files:**
- Modify: `app/src/main/java/com/walkwalk/domain/Gpx.kt`
- Modify: `app/src/main/java/com/walkwalk/data/HikeRepository.kt` (`importGpx` stores waypoints)
- Modify: `app/src/main/java/com/walkwalk/MainActivity.kt` (`exportGpx` passes landmarks)
- Test: `app/src/test/java/com/walkwalk/domain/GpxTest.kt`, `LandmarkRepositoryTest`

**Interfaces:**
- Consumes: `HikeRepository.landmarks`, `addLandmark` (Task 1)
- Produces:
  - `data class GpxWaypoint(val lat: Double, val lon: Double, val time: Long?, val links: List<String> = emptyList())`
  - `ParsedGpx.waypoints: List<GpxWaypoint> = emptyList()` (new last parameter)
  - `GpxWriter.write(name: String, points: List<TrackPoint>, out: OutputStream, waypoints: List<GpxWaypoint> = emptyList())`: `<wpt lat lon>` before `<trk>`, with `<time>` when set and one `<link href="…"/>` per link
  - `GpxParser.parse` fills `waypoints` from top-level `<wpt>` (lat/lon via `decimal`-safe parsing as for trkpt, `<time>`, `<link href>`)

- [ ] **Step 1: Write the failing tests**
  - `GpxTest.waypointsRoundTrip`: write 2 waypoints (one with time and links `["photos/1.jpg","photos/2.jpg"]`, one without time) → parse → equal `GpxWaypoint`s
  - `GpxTest.waypointNameDescSymIgnored`: parse a GPX whose `<wpt>` has `<name>`, `<desc>`, `<sym>`, `<ele>` → one waypoint, track `name` unaffected (the wpt `<name>` must not become the hike name)
  - `GpxTest.fileWithOnlyWaypointsIsRejected`: still throws `GpxException` (no track points)
  - `LandmarkRepositoryTest.importGpxCreatesLandmarksWithoutPhotos`: `importGpx(ParsedGpx(..., waypoints = listOf(GpxWaypoint(48.1, 16.1, 5000L, listOf("photos/9.jpg")))))` → one landmark, `time == 5000L`, no photos

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew testDebugUnitTest --tests "com.walkwalk.domain.GpxTest" --tests "com.walkwalk.data.LandmarkRepositoryTest"`
Expected: compilation FAIL (`GpxWaypoint` unresolved).

- [ ] **Step 3: Implement** the writer and parser changes. In the parser, `name` stays restricted to `trk`/`rte`/`metadata` parents (already), and `ele`/`time` inside `wpt` go to the current waypoint, not a track point. `importGpx` inserts one landmark per waypoint inside the same transaction. `exportGpx` passes `landmarks.map { GpxWaypoint(it.lat, it.lon, it.time) }` (no links in a plain GPX export).

- [ ] **Step 4: Run to verify pass**: `./gradlew testDebugUnitTest` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** (with approval): `feat: GPX waypoints become landmarks and are exported`

---

### Task 3: PhotoStore (time-window query, photo state, camera target, private copies)

**Files:**
- Create: `app/src/main/java/com/walkwalk/photos/PhotoStore.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add `READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED`; **do not** add `CAMERA`, so the camera intent needs no permission)
- Modify: `app/src/main/java/com/walkwalk/WalkwalkApp.kt` (`val photos: PhotoStore by lazy { PhotoStore(this) }`)
- Test: `app/src/test/java/com/walkwalk/photos/PhotoStoreTest.kt`

**Interfaces:**
- Produces:
  - `enum class PhotoState { AVAILABLE, MISSING, UNREADABLE }`
  - `data class GalleryPhoto(val uri: String, val takenAt: Long)`
  - `enum class PhotoAccess { FULL, PARTIAL, NONE }`
  - `class PhotoStore(context: Context)` with:
    - `fun access(): PhotoAccess` (FULL = `READ_MEDIA_IMAGES` granted; PARTIAL = only `READ_MEDIA_VISUAL_USER_SELECTED`)
    - `suspend fun takenBetween(from: Long, to: Long): List<GalleryPhoto>` — newest first
    - `suspend fun state(uri: String): PhotoState`
    - `fun newCameraTarget(): Uri` — MediaStore insert into `Pictures/walkwalk` with `IS_PENDING = 1`
    - `fun commitCameraTarget(uri: Uri)` (sets `IS_PENDING = 0`), `fun discardCameraTarget(uri: Uri)` (deletes the row)
    - `fun privateDir(hikeId: Long): File` = `filesDir/photos/<hikeId>`; `fun deletePrivate(hikeId: Long)`
    - `fun open(uri: String): InputStream?` (content or file URI)
  - Pure helpers in the companion, used by the above and tested directly:
    - `fun takenAt(dateTakenMs: Long?, dateAddedSec: Long): Long` — `dateTakenMs ?: dateAddedSec * 1000`
    - `fun decideState(found: Boolean, access: PhotoAccess, isPrivateFile: Boolean): PhotoState` — private file: found→AVAILABLE else MISSING; gallery: found→AVAILABLE; not found with FULL→MISSING; not found with PARTIAL/NONE→UNREADABLE

- [ ] **Step 1: Write the failing tests**
  - `takenAtUsesDateTaken`: `takenAt(1_000L, 5) == 1_000L`
  - `takenAtFallsBackToDateAdded`: `takenAt(null, 5) == 5_000L`
  - `decideState`: a table covering all branches above, in particular `decideState(false, PhotoAccess.PARTIAL, false) == UNREADABLE` and `decideState(false, PhotoAccess.FULL, false) == MISSING`
  - `privateDirIsPerHike` (Robolectric): `privateDir(7).path.endsWith("photos/7")`; `deletePrivate(7)` removes a file written there

- [ ] **Step 2: Run to verify failure**: `./gradlew testDebugUnitTest --tests "com.walkwalk.photos.PhotoStoreTest"` → compilation FAIL.

- [ ] **Step 3: Implement `PhotoStore`.** `takenBetween` queries `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` for `_ID, DATE_TAKEN, DATE_ADDED`, selecting rows where `COALESCE(DATE_TAKEN, DATE_ADDED*1000)` lies in `[from, to]`, sorted descending. `state` for a gallery URI queries that URI for `_ID` (`found` = cursor has a row; a `SecurityException` counts as not found).

- [ ] **Step 4: Run to verify pass**: `./gradlew testDebugUnitTest` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** (with approval): `feat: photo store for gallery, camera and private photos`

---

### Task 4: `.walkwalk` bundle write/read and import with dedupe

**Files:**
- Create: `app/src/main/java/com/walkwalk/share/HikeBundle.kt`
- Create: `app/src/main/java/com/walkwalk/share/PhotoShrinker.kt`
- Modify: `app/src/main/java/com/walkwalk/data/HikeRepository.kt` (`importBundle`)
- Test: `app/src/test/java/com/walkwalk/share/HikeBundleTest.kt`, `app/src/test/java/com/walkwalk/share/BundleImportTest.kt`

**Interfaces:**
- Consumes: Task 1 repository methods, Task 2 `GpxWriter`/`GpxParser`/`GpxWaypoint`, Task 3 `PhotoStore.open`/`privateDir`/`deletePrivate`
- Produces:
  - `object HikeBundle`:
    - `const val MAX_EDGE_PX = 1600`, `const val JPEG_QUALITY = 80`, `const val WARN_BYTES = 25L * 1024 * 1024`
    - `fun write(out: OutputStream, bundleId: String, name: String, points: List<TrackPoint>, landmarks: List<Landmark>, photo: (LandmarkPhotoEntity) -> ByteArray?)` — `photo` returns shrunk JPEG bytes or null when the photo is missing; missing ones get no file and no link
    - `fun read(input: InputStream, savePhoto: (entryName: String, InputStream) -> String): ReadBundle` — `savePhoto` returns the stored URI; only entries matching `photos/[^/]+\.jpg` are passed on
    - `data class ReadBundle(val bundleId: String, val gpx: ParsedGpx, val photoUris: Map<String, String>)` (link path → stored URI)
    - `fun fileName(hikeName: String): String` — same sanitising as `exportGpx` (`[^A-Za-z0-9 _.-]` → `_`, blank → `hike`) + `.walkwalk`
  - `object PhotoShrinker { fun shrink(source: ImageDecoder.Source): ByteArray }` — `ImageDecoder` (applies EXIF rotation) with target size so the long edge ≤ `MAX_EDGE_PX`, JPEG `JPEG_QUALITY`
  - `HikeRepository.importBundle(bundle: ReadBundle, fallbackName: String): Long` — creates the hike via the `importGpx` path with `bundleId` set, then attaches each waypoint's links mapped through `photoUris` (unknown links skipped)
  - `class BundleImporter(repo: HikeRepository, photos: PhotoStore)` with `suspend fun import(input: () -> InputStream, fallbackName: String): Long` — reads `walkwalk.json` first; if `repo.hikeByBundleId` finds a hike, returns its id without writing anything; otherwise saves photos into `photos.privateDir(newHikeId)` and imports. Throws `GpxException("Not a walkwalk file")` when `hike.gpx` or `walkwalk.json` is missing.

- [ ] **Step 1: Write the failing tests**
  - `HikeBundleTest.roundTrip` (Robolectric, fake `photo` returning `byteArrayOf(1,2,3)`): 3 points, 2 landmarks with 2 and 0 photos → `read` gives same point count, 2 waypoints, the first with 2 links that resolve in `photoUris`, saved bytes equal `[1,2,3]`, same `bundleId`
  - `HikeBundleTest.missingPhotoLeftOut`: `photo` returns null for one of two photos → zip has 1 photo entry and the waypoint 1 link
  - `HikeBundleTest.ignoresZipSlipAndMissingPhotos`: hand-built zip with `hike.gpx` (one wpt linking `photos/1.jpg` and `photos/2.jpg`), `walkwalk.json`, `photos/1.jpg` and `../evil.jpg` → `savePhoto` called once with `photos/1.jpg`, `photoUris.keys == setOf("photos/1.jpg")`
  - `HikeBundleTest.rejectsZipWithoutGpx`: zip with only `walkwalk.json` → `GpxException`
  - `HikeBundleTest.fileNameIsSafe`: `fileName("Thu 29 Oct 2026, 22:07") == "Thu 29 Oct 2026_ 22_07.walkwalk"`
  - `BundleImportTest.sameBundleTwiceOpensExisting`: import the same bytes twice → same id, one hike in `finishedHikes()`
  - `BundleImportTest.deleteThenReimport`: import, `repo.delete(id)` + `photos.deletePrivate(id)`, `privateDir(id)` no longer exists; import again → new id, photos present again

- [ ] **Step 2: Run to verify failure**: `./gradlew testDebugUnitTest --tests "com.walkwalk.share.*"` → compilation FAIL.

- [ ] **Step 3: Implement** `HikeBundle`, `PhotoShrinker`, `importBundle`, `BundleImporter`. The importer opens the stream twice (once for `walkwalk.json`, once for everything), which is why it takes `() -> InputStream`. `walkwalk.json` is `{"bundleId": "...", "format": 1}`; an unknown higher `format` is still read *(plan's call: forward-compatible)*.

- [ ] **Step 4: Run to verify pass**: `./gradlew testDebugUnitTest` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** (with approval): `feat: walkwalk hike bundle with photos`

---

### Task 5: Pins on the map, long-press, and the overlay under the selected pin

**Files:**
- Modify: `app/src/main/java/com/walkwalk/map/TopoMap.kt`
- Create: `app/src/main/res/drawable/ic_pin.xml` (vector pin, white inner dot, white outline)
- Test: `app/src/test/java/com/walkwalk/map/LandmarkFeaturesTest.kt`

**Interfaces:**
- Consumes: `Landmark` (Task 1)
- Produces: new optional `TopoMap` parameters (defaults keep today's behaviour):
  - `landmarks: List<Landmark> = emptyList()`
  - `selectedLandmarkId: Long? = null`
  - `onLandmarkClick: (Long) -> Unit = {}`
  - `onMapClick: () -> Unit = {}`
  - `onLongPress: ((lat: Double, lon: Double) -> Unit)? = null` (null = long-press does nothing)
  - `selectedOverlay: (@Composable () -> Unit)? = null` — drawn horizontally centred just below the selected pin, repositioned on every camera move via `map.projection.toScreenLocation`; hidden when the pin is off screen
  - pure helper `fun landmarkFeatures(landmarks: List<Landmark>, selectedId: Long?): FeatureCollection` — one Point feature per landmark with properties `id` (number) and `selected` (boolean)

- [ ] **Step 1: Write the failing test** `LandmarkFeaturesTest.featuresCarryIdAndSelection`: 2 landmarks, second selected → 2 features; coordinates are `(lon, lat)`; `selected` is false/true; `id` matches.

- [ ] **Step 2: Run to verify failure**: `./gradlew testDebugUnitTest --tests "com.walkwalk.map.LandmarkFeaturesTest"` → compilation FAIL.

- [ ] **Step 3: Implement.** Pins: a `GeoJsonSource("landmarks")` + `SymbolLayer` with two images added to the style (`pin` tinted `#0066CC`, `pin-selected` tinted `#E0A030`, rendered from `ic_pin.xml`), `iconImage` chosen by the `selected` property, `iconAnchor = bottom`, `iconAllowOverlap = true`. Taps: `addOnMapClickListener` → `queryRenderedFeatures` on the pin layer → `onLandmarkClick(id)` else `onMapClick()`. Long-press: `addOnMapLongClickListener` → `onLongPress`. The existing `onUserPan` behaviour stays.

- [ ] **Step 4: Run to verify pass**: `./gradlew testDebugUnitTest` → BUILD SUCCESSFUL; `./gradlew assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** (with approval): `feat: landmark pins and long-press on the map`

---

### Task 6: Landmark UI — view model, strip, menu, picker, viewer

**Files:**
- Create: `app/src/main/java/com/walkwalk/ui/landmarks/LandmarksViewModel.kt`
- Create: `app/src/main/java/com/walkwalk/ui/landmarks/PhotoStrip.kt` (strip + thumbnail states + menu)
- Create: `app/src/main/java/com/walkwalk/ui/landmarks/HikePhotoPicker.kt` (full-screen dialog)
- Create: `app/src/main/java/com/walkwalk/ui/landmarks/PhotoViewer.kt` (full-screen dialog)
- Test: `app/src/test/java/com/walkwalk/ui/landmarks/LandmarksViewModelTest.kt`

**Interfaces:**
- Consumes: Task 1 repository methods, Task 3 `PhotoStore`, `PhotoState`, `GalleryPhoto`, `PhotoAccess`
- Produces:
  - `interface Photos` — the subset of `PhotoStore` the view model uses (`state`, `takenBetween`, `access`, `newCameraTarget`, `commitCameraTarget`, `discardCameraTarget`), implemented by `PhotoStore`, faked in tests
  - `data class Thumb(val photoId: Long, val uri: String, val state: PhotoState)`
  - `class LandmarksViewModel(repo: HikeRepository, photos: Photos, hikeId: Long, savedState: SavedStateHandle) : ViewModel()` with:
    - `val landmarks: StateFlow<List<Landmark>>`, `val selectedId: StateFlow<Long?>`, `val thumbs: StateFlow<List<Thumb>>` (of the selected landmark), `val menuOpenFor: StateFlow<Long?>`
    - `fun select(id: Long?)`, `fun addHere(lat: Double, lon: Double, time: Long)` (live fix; opens menu), `fun addAt(lat: Double, lon: Double)` (long-press; opens menu), `fun delete(id: Long)`, `fun removePhoto(photoId: Long)`
    - `fun pickerWindow(hike: HikeEntity, now: Long): LongRange?` — `startTime..(endTime ?: now)`, null when `!hike.hasTimes`
    - `suspend fun pickerPhotos(window: LongRange): List<GalleryPhoto>`, `fun attach(landmarkId: Long, picked: List<GalleryPhoto>)`
    - `fun startCamera(landmarkId: Long): Uri` (stores `landmarkId` + target URI in `savedState` so it survives process death), `fun onCameraResult(success: Boolean)` (success → commit + attach with `takenAt = now`; failure → discard)
  - Composables: `PhotoStrip(thumbs: List<Thumb>, onThumb: (Int) -> Unit, onAdd: () -> Unit)`, `LandmarkMenu(expanded: Boolean, canCamera: Boolean, canPick: Boolean, onCamera: () -> Unit, onPick: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit)`, `HikePhotoPicker(window: LongRange, vm: LandmarksViewModel, landmarkId: Long, onDone: () -> Unit)`, `PhotoViewer(thumbs: List<Thumb>, start: Int, onRemove: (Long) -> Unit, onAllowAccess: () -> Unit, onClose: () -> Unit)`

- [ ] **Step 1: Write the failing tests** with an in-memory repo and a fake `Photos`:
  - `addAtOpensMenuForNewLandmark`: `addAt` → `menuOpenFor.value == ` the new id, `selectedId` the same
  - `thumbsReflectPhotoState`: two photos, fake says one `MISSING` → `thumbs` states `[AVAILABLE, MISSING]`
  - `pickerWindowUsesEndOrNow`: finished hike → `start..end`; recording hike (endTime null) → `start..now`; `hasTimes=false` → null
  - `attachSkipsPhotosAlreadyOnLandmark`: attach the same `GalleryPhoto` twice → one photo
  - `cancelledCameraDiscardsEntry`: `startCamera(id)` then `onCameraResult(false)` → fake recorded `discardCameraTarget(uri)`, no photo attached; a new `LandmarksViewModel` built with the same `SavedStateHandle` after `startCamera` and given `onCameraResult(true)` attaches the photo to the right landmark (process-death case)
  - `deleteRemovesLandmarkAndClearsSelection`

- [ ] **Step 2: Run to verify failure**: `./gradlew testDebugUnitTest --tests "com.walkwalk.ui.landmarks.*"` → compilation FAIL.

- [ ] **Step 3: Implement the view model**, then the composables:
  - **Strip:** a horizontally scrolling row on a background of `Color.White.copy(alpha = 0.45f)`, a 14 dp corner, and a 1 dp white border. Thumbnails are 44 dp circles with a 2 dp white border:
    - `AVAILABLE` thumbnails load via `ContentResolver.loadThumbnail(uri, Size(132, 132), null)`, or `ImageDecoder` for file URIs;
    - `MISSING` is a grey circle with ⊘;
    - `UNREADABLE` is a grey circle with a lock icon;
    - a trailing ＋ circle opens the menu.
  - **Menu:** offers 📷 Take photo, 🖼 Photos from this hike and 🗑 Delete landmark. Delete asks "Delete this landmark? Its photos stay in your gallery." first.
  - **Picker:**
    - a 3-column grid with checkmarks, newest first; photos already on the landmark start ticked; a **Done** button;
    - with `PARTIAL` access, a hint "Only photos you allowed are shown" plus **Allow more**, which re-requests `READ_MEDIA_IMAGES` + `READ_MEDIA_VISUAL_USER_SELECTED` (this shows Android's selection screen);
    - with `NONE`, the same button and the text "walkwalk needs access to your photos to list the ones from this hike";
    - with an empty result, "No photos taken during this hike".
  - **Viewer:**
    - `HorizontalPager` over the landmark's photos, with pinch zoom via `Modifier.transformable`;
    - on a `MISSING` page: "This photo is no longer on the phone" + **Remove**;
    - on an `UNREADABLE` page: "walkwalk can't read this photo" + **Allow access**.

- [ ] **Step 4: Run to verify pass**: `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** (with approval): `feat: landmark photo strip, menu, picker and viewer`

---

### Task 7: Landmarks on the recording and replay screens

**Files:**
- Modify: `app/src/main/java/com/walkwalk/ui/RecordingScreen.kt`
- Modify: `app/src/main/java/com/walkwalk/ui/ReplayScreen.kt`
- Modify: `app/src/main/java/com/walkwalk/MainActivity.kt` (create `LandmarksViewModel` per hike, keyed `"landmarks-$hikeId"`)

**Interfaces:**
- Consumes: Task 5 `TopoMap` parameters, Task 6 view model and composables
- Produces: `RecordingScreen(..., landmarks: LandmarksViewModel?)` (null until a hike id exists) and `ReplayScreen(vm, landmarks: LandmarksViewModel, onBack, onCorrectAltitude)`

- [ ] **Step 1: Recording screen**
  - Add a `SmallFloatingActionButton` with the pin icon, placed above the re-centre button. It's enabled only when the track has a last point, and calls `addHere(last.lat, last.lon, last.time)`.
  - Pass the `TopoMap` landmark parameters, with `onLongPress = vm::addAt`.
  - `selectedOverlay` shows `PhotoStrip` and `LandmarkMenu`, with `canCamera = status in (RECORDING, PAUSED)` and `canPick = pickerWindow(...) != null`.
  - Camera: `rememberLauncherForActivityResult(ActivityResultContracts.TakePicture())`, launched with `vm.startCamera(id)`; the result goes to `vm.onCameraResult`.

- [ ] **Step 2: Replay screen**: same map parameters and long-press, `canCamera = false`.

- [ ] **Step 3: Build and run the unit tests**: `./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 4: Check on the emulator (ask the user first; it may be in use)** using the debug **Demo** hike:
  1. Pin button → the menu opens at once.
  2. Take photo → the emulator camera saves a photo → its thumbnail appears.
  3. Long-press on the map → a pin appears and the menu opens.
  4. Photos from this hike lists the photo just taken.
  5. Tap a thumbnail → the viewer opens.
  6. Delete the photo from the Photos app → the thumbnail shows ⊘ and offers Remove.
  7. Stop the hike → replay shows the pins, and its menu has no Take photo.
  8. Recording carries on while the camera is open: distance keeps increasing.

- [ ] **Step 5: Commit** (with approval): `feat: landmarks on recording and replay screens`

---

### Task 8: Send hike, Import button, opening `.walkwalk` files

**Files:**
- Modify: `app/src/main/java/com/walkwalk/ui/HikeListScreen.kt` (menu entry **Send hike**; button label **Import**)
- Modify: `app/src/main/java/com/walkwalk/MainActivity.kt` (send, import dispatch, delete removes private photos)
- Modify: `app/src/main/AndroidManifest.xml` (add `application/zip` and `application/x-zip-compressed` to the VIEW/SEND filters)
- Modify: `app/src/main/res/xml/file_paths.xml` (`<cache-path name="send" path="send/" />`)
- Test: `app/src/test/java/com/walkwalk/share/ImportKindTest.kt`

**Interfaces:**
- Consumes: Task 4 `HikeBundle`, `BundleImporter`, `PhotoShrinker`; Task 3 `PhotoStore.deletePrivate`
- Produces: `fun importKind(head: ByteArray): ImportKind` with `enum class ImportKind { BUNDLE, GPX }` — zip magic `PK\u0003\u0004` → `BUNDLE`, else `GPX`

- [ ] **Step 1: Write the failing test** `ImportKindTest`: `byteArrayOf(0x50,0x4B,3,4)` → `BUNDLE`; `"<?xml".toByteArray()` → `GPX`; empty array → `GPX`.

- [ ] **Step 2: Run to verify failure**: `./gradlew testDebugUnitTest --tests "com.walkwalk.share.ImportKindTest"` → compilation FAIL.

- [ ] **Step 3: Implement**
  - **Send hike**, on `Dispatchers.IO`:
    1. `repo.ensureBundleId`;
    2. write `cacheDir/send/<HikeBundle.fileName>`, shrinking each photo with `PhotoShrinker` (missing or unreadable photos → null);
    3. back on the main thread, if `file.length() > WARN_BYTES`, show the dialog "This hike is X MB. Email may refuse files over 25 MB; messengers accept it." with **Send anyway** / **Cancel**;
    4. send `ACTION_SEND`, type `application/zip`, with `FLAG_GRANT_READ_URI_PERMISSION`, through a chooser titled "Send hike".
  - **Import**, used by both incoming intents and the button: read the first 4 bytes to get `importKind`. `BUNDLE` → `BundleImporter.import` → navigate to `replay/$id`. `GPX` → the existing path.
    - The button's picker MIME filter becomes `arrayOf("*/*")` (as today).
    - Snackbar on failure: "Not a readable GPX or walkwalk file".
  - **onDelete**: `repo.delete(id)` then `photos.deletePrivate(id)`.

- [ ] **Step 4: Run to verify pass**: `./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 5: Check on the emulator (ask first)**:
  1. **Send hike** on the demo hike with a photo → the share sheet opens.
  2. Delete the hike, then **Import** the file from Downloads (saved via the share sheet's "Save to Files", or `adb pull`/`push` of `cacheDir/send`) → the hike reappears with pins and photos.
  3. **Import** the same file again → the existing hike opens, with no duplicate in the list.

- [ ] **Step 6: Commit** (with approval): `feat: send and import walkwalk hikes`
