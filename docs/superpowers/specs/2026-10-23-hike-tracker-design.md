# walkwalk — Hike Tracker for Android: Design

Date: 2026-10-23
Status: draft, awaiting approval

## Intent

A personal Android app that does the core of what Garmin's hiking software does:
record where you walked, when, and at what altitude and speed — and, above all,
lets you **reopen a past hike and relive it in time**: scrub or play back along a
timeline while a marker moves over a topographic map and the numbers (time,
altitude, speed, distance, climb) update for that moment.

The replay experience is the heart of the app. Recording exists to feed it.

The user hikes in Europe, uses Android, and will install the app on their own
phone only. It is not going to be published.

## Goals

1. **Record a hike** reliably, with the phone either in hand or in a pocket with
   the screen off.
2. **List past hikes** and open any of them.
3. **Replay a hike**: full-screen topographic map, the whole track drawn, a
   marker at the current replay moment, live numbers, an elevation profile, and
   a scrubber with play/pause and fast playback.
4. **Topographic maps with hiking trails** from free public providers.
5. **GPX import and export**, so tracks can move between this app and Garmin
   Connect, Komoot, Strava and similar apps.

## Non-goals (for this version)

- Route planning or turn-by-turn navigation
- Accounts, cloud sync, social sharing or a web backend
- Heart rate or other sensor data beyond location, altitude and pressure
- Publishing on the Play Store
- Bulk "download a whole region" offline maps
- FIT and KML formats
- iOS

## Anti-goals (it fails even if it "works")

- **A hike is lost or cut short** because Android killed recording in the
  pocket. This is the worst possible failure.
- **Altitude is visibly nonsense**: the profile jumps tens of metres while
  standing still, or total climb is wildly inflated by GPS noise.
- **Replay feels laggy**: the marker should follow the scrubber smoothly.

## Constraints

- **Platform:** native Android, Kotlin. *(my call)*
- **Distribution:** local install only, as a debug or self-signed build
  installed from Android Studio or with adb. No Play Store requirements.
- **Target phone:** Google Pixel 9a (stock Android 15/16). No vendor-specific
  background-killing workarounds are needed. `minSdk` 34 *(my call: one known
  device, and it unlocks Android's built-in altitude conversion API)*. Whether
  the 9a has a barometer is checked at runtime, and both altitude paths stay.
- **Connectivity:** mobile signal is usually available but can be sparse.
  Recording must never depend on network access.
- **Map providers:** free, public and keyless, with their attribution and tile
  usage policies respected. That means a proper User-Agent, no bulk
  prefetching, and attribution shown on the map.

## Decided by the user

- Android, kept local (not published).
- Replay screen layout **B**: full-screen map. The live numbers float in a card
  at the top. A slim card at the bottom holds the elevation profile with a
  cursor, a play/pause button, a scrubber and the playback speed. (Mockup:
  `.superpowers/brainstorm/2044-1792759825/content/replay-layout.html`, option B.)
- The phone may be in hand or in a pocket during a hike, so it needs both a live
  recording screen and screen-off recording.
- GPX import and export: yes.

## Screens and behaviour

### 1. Hike list (home)

- Past hikes, newest first. Each row shows the name, date, distance, duration
  and total ascent.
- Default name: date and start time, for example "Sat 12 Oct 2026, 09:00".
  Rename is available. *(my call)*
- Actions: open, rename, delete (with confirmation), and export GPX through
  Android's share sheet.
- A prominent **Start hike** button, plus **Import GPX**.

### 2. Recording (live)

- Same visual language as replay layout B: a full-screen map, the track so far
  drawn as a line, a current-position marker, and the map following the user
  (the user can pan away and re-centre).
- Floating card: elapsed time, distance, current altitude, current speed and
  ascent so far.
- Controls: **Pause / Resume** and **Stop**. Stop asks for confirmation, then
  saves and opens the hike in replay.
- **Automatic stop detection (user decision):** the app detects stops by itself,
  such as a lunch break where nobody pressed Pause. A stop is a stretch where
  you stay within a small radius for longer than a minimum time. The exact
  thresholds are left to the builder; something like "within ~20 m for over
  2 min" is a starting point. Recording continues during a detected stop. The
  stop is only marked, so no data is lost if detection is wrong. The manual
  Pause button stays and stops recording entirely.
- **Moving time** excludes both manual pauses and detected stops. Total time
  includes detected stops but not manual pauses.
- Recording runs in a **foreground service** of type `location`, with a
  persistent notification showing elapsed time and distance. It survives screen
  off, app switching and the app being swiped away.
- Points are **written to storage as they arrive**, not held in memory. If the
  app or phone dies, everything up to that moment is kept, and on next launch an
  unfinished hike is offered for resume or save. *(my call, driven by the
  anti-goal)*
- Sample rate: one GPS fix about every 2–5 s. The exact rate and distance filter
  are left to the builder, balancing smoothness against battery.
- **Recording state belongs to the user (user decision):** once started, a hike
  stays in the recording state until the user presses **Stop**. Swiping the
  app away, the screen turning off, or Android killing the process do not end
  it. Equally, **nothing runs when no hike is recording**: no service, no GPS,
  and no automatic start. Closing the app outside a recording closes it
  completely. The background-location permission is used only while recording.
- **Self-restart** *(my call, driven by the anti-goal)*: if Android kills the
  recording service, it restarts by itself (sticky service, plus restart after
  a reboot while a hike is active) and keeps appending to the same hike as a new
  segment. This needs:
  - fine location, then **"Allow all the time"** (`ACCESS_BACKGROUND_LOCATION`),
    because a service restarted from the background can't get location with
    "while in use" alone;
  - the notification permission;
  - an **exemption from battery optimisation**, requested once.

  The app asks for each one with a short explanation the first time a hike is
  started. If one is refused, recording still works while the app is in use,
  and the recording screen shows a warning that a pocket hike could be cut
  short.
- The resume-or-save offer on next launch remains as the last resort if
  self-restart fails.

### 3. Replay (layout B)

- Full-screen map, zoomed to fit the whole track. The track is drawn in one
  colour. *(Colouring the track by speed or altitude is a later idea, not now.)*
- A **marker** sits at the position for the current replay time. Between
  recorded points, the position is interpolated so motion is smooth.
- **Top floating card:** clock time (for example 10:42), elapsed time since
  start, altitude, speed at that moment, distance so far and ascent so far.
- **Bottom slim card:**
  - The elevation profile, with altitude on the vertical axis and **distance**
    on the horizontal axis *(my call)*, and a vertical cursor at the current
    moment. Dragging on the profile also scrubs.
  - Play/pause, a time scrubber covering the whole hike, and a playback speed
    selector, for example ×10, ×60 and ×300 *(my call; exact steps left to the
    builder)*.
- Manual pauses and detected stops are compressed in playback, so replay doesn't
  sit still for 30 minutes at lunch. Each one plays as a brief moment, and the
  clock in the top card jumps to show the real time. On the scrubber and
  profile, stops are marked so you can see where you rested. *(my call)*
- A summary (total distance, total time, moving time, ascent and descent, min
  and max altitude, average and max speed) is reachable from replay, for example
  by expanding the top card. *(my call; placement left to the builder)*

## Maps

- **Base map:** OpenTopoMap raster tiles (`https://tile.opentopomap.org/{z}/{x}/{y}.png`).
  This is a topographic map with contour lines and hill shading, built from
  OpenStreetMap data, with good coverage of Europe. Attribution: "Map data ©
  OpenStreetMap contributors, SRTM | Map style © OpenTopoMap (CC-BY-SA)".
- **Trail overlay:** Waymarked Trails hiking layer
  (`https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png`), a transparent
  raster drawn on top. Attribution: "© waymarkedtrails.org, OpenStreetMap
  contributors".
- **Map library:** MapLibre Native for Android with raster sources. *(my call.
  The builder may swap in osmdroid if MapLibre's raster handling or caching
  causes trouble. The requirement is "topo raster + overlay + cache", not the
  library.)*
- **Caching:** every tile viewed is kept on the phone in a generous cache
  (hundreds of MB, size left to the builder), so areas already seen still work
  when signal drops. There is no bulk region download (non-goal, and against the
  providers' policies).
- Without signal and without cached tiles, the track and marker still draw
  over a blank background. Replay never fails for lack of map.

## Altitude

GPS altitude alone is often 20–50 m off and noisy. The approach, decided as
direction and with the details left to the builder:

1. **During recording:** if the phone has a barometer, use it for relative
   altitude changes, because it is smooth and precise. Anchor it to an absolute
   altitude, using GPS altitude averaged over the first fixes as a start. Without
   a barometer, use GPS altitude, converted from ellipsoid height to sea-level
   height where Android provides that (API 34+ altitude conversion or
   equivalent).
2. **After recording (when online):** correct the track against a public digital
   elevation model. The suggested source is the **Open-Meteo Elevation API**
   (Copernicus DEM, free, no key, batches of 100 points). Use it at least to fix
   the absolute offset, so the hike sits at the right height. Whether to also
   use the DEM point by point is left to the builder. If offline, keep the
   recorded values and allow correction later.
3. **Ascent and descent** are computed from smoothed altitude with a threshold
   (hysteresis of a few metres), so noise doesn't inflate total climb.

Both raw and corrected altitude are stored, so the correction can be redone.

## Data and storage

- Everything is stored on the phone in a local database (Room/SQLite). There
  is no account and no cloud.
- **Hike:** id, name, start and end time, status (recording, finished), cached
  summary statistics, and the source (recorded or imported).
- **Track point:** hike id, timestamp, latitude, longitude, GPS altitude,
  barometric pressure or altitude if available, corrected altitude, horizontal
  accuracy, and a segment index (a new segment begins after each pause).
- **Stop:** hike id, start time, end time. Detected stops are computed from
  the points and stored. They can be recomputed, for example if thresholds
  change.
- **Speed** is derived from consecutive points and smoothed, not taken raw from
  GPS.

## GPX

- **Export:** GPX 1.1, one `<trk>`, one `<trkseg>` per recorded segment, with
  `<ele>` (corrected altitude when available) and `<time>` on each point. Shared
  through Android's share sheet.
- **Import:** open or share a `.gpx` file into the app. Points with timestamps
  replay normally. A track **without timestamps** (a planned route, for example)
  still imports and shows on the map with its profile, but replay is disabled.
  Multiple tracks or segments in one file become segments of one hike.
  *(my call)*
- Imported hikes go through the same altitude handling and the same stop
  detection. If the file has no `<ele>`, altitude comes from the DEM.

## Left to the builder

- Architecture inside the app (layers, DI, navigation library), with Jetpack
  Compose suggested for UI
- Exact GPS sample rate and filters, cache size, and playback speed steps
- The algorithms for altitude smoothing and fusion, within the direction above
- Visual styling beyond the layout B structure
- The test strategy, which should include a way to feed a recorded or GPX track
  through the pipeline without walking outside

## Notes

- `.superpowers/` (brainstorm mockups) should be added to `.gitignore`.
