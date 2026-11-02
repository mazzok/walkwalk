# Settings page: track color and ghost mode

## Intent

The user walks some routes again and again. When they do, they want to know, live and on the map,
whether they are faster or slower than on earlier walks of the same route. Ghost mode does this by
replaying every earlier walk of the route as a "ghost" that moves in real time from the moment
the new hike starts.

Separately, the recorded track is hard-coded red (`#CC3333` in `TopoMap.kt`). The user wants to pick
that color themselves.

Both live on a new Settings page.

## Goals

1. **Settings entry point.** A gear icon at the top right of the hike list's top bar (`HikeListScreen`)
   opens a new Settings screen. The screen has a back arrow to the list.
2. **Track color.** In Settings the user picks one color, used for all of their own tracks:
   the live track while recording and the track in the replay screen. The default stays `#CC3333`, so
   nothing changes for someone who never opens Settings.
3. **Ghost mode switch.** An on/off switch in Settings, off by default. It is a persistent preference,
   not something chosen per hike.
4. **Ghost matching.** With ghost mode on, when a new hike starts, every saved finished hike whose
   first point is within **150 m** of the new hike's first accepted fix becomes a ghost. All matches
   are shown, not only the fastest or the latest.
5. **Real-time ghosts.** Every ghost's clock starts when the new hike starts and runs 1:1 with the
   new hike's elapsed time, with no fast-forward and no compression. A ghost that is 20 minutes into
   its hike is wherever that hike was 20 minutes in.
6. **Ghost dot.** Each ghost is a dot on the live map in its own color, a little fainter than
   the user's position marker.
7. **Ahead/behind.** For each ghost the live screen shows how far ahead or behind the user is, as a
   time difference, e.g. `+2:30` (the user is behind, slower) or `−1:10` (the user is ahead, faster).
8. **Ghost trail on tap.** Tapping a ghost dot shows that ghost's full track as a line in the ghost's
   color at partial opacity (about 50%). Tapping it again, or tapping empty map, hides it.
9. **Dropping ghosts of other routes.** The user may start different walks from the same place, so a
   start match alone can pull in ghosts of unrelated routes. A ghost is removed from the live map once
   the user has been more than a set distance away from that ghost's whole track for a while. The
   distance is a choosable option on the Settings screen, **default 200 m** (user's decision). The
   setting also has an **Off** choice, which keeps every start-matched ghost. A dropped ghost does not
   come back during that hike.
10. **Ghost colors.** Ghosts get colors automatically from a fixed palette. The palette must stay clearly
   different from the user's chosen track color. The dot and the trail of one ghost share its color.

## Non-goals

- Picking a single "best" ghost, or ranking ghosts. All matching hikes show, for now.
- Matching hikes that start partway along the route, or that are walked in reverse. The user confirmed
  that their repeat walks start at the same place and go the same direction.
- Ghosts in the replay screen (watching an old hike against another old hike).
- Per-hike track colors. One color for all of the user's tracks.
- Saving ghost results with the hike after it ends (a "you beat your best by 3 min" summary).

## Anti-goals

- Ghosts must never slow down or interfere with recording. Recording keeps writing every fix to storage
  as it does now, and ghost work must not block it.
- Ghost mode must not change what gets recorded or saved for the new hike.
- A ghost that runs faster or slower than the real old walk is the core failure. Real time must
  mean real time.

## How it behaves (decided)

### Ghost clock

- The ghost clock is the new hike's elapsed time as the live screen already counts it
  (`LiveStats.elapsedMs` plus the tick between fixes in `RecordingScreen`). This time excludes the
  user's own manual pauses, so pausing the new hike freezes all ghosts.
- A ghost's own position at clock `t` is where the old hike was after `t` of its own elapsed time,
  counted the same way: real time within segments, with the gaps between segments (its manual pauses)
  left out. Stops where the old hike stood still without pressing pause are kept, because
  that standing time really happened. **Note:** this does not use `ReplayTimeline`, which compresses
  stops to 30 s. Ghosts need an uncompressed timeline.
- When a ghost reaches the end of its hike, its dot stays at the finish. Its row is labeled
  "finished · <its total time>", and the `+/−` number keeps updating by the distance rule below.

### Matching

- Matching runs once, on the new hike's first accepted fix, because the phone may not have a position
  at the moment Start is pressed.
- Candidates are finished hikes that have timestamps (`hasTimes = true`) and at least 2 points. GPX
  imports without times are skipped. Imported hikes with times (bundles from other walkers or GPX
  with times) count.
- The setting is read when the hike starts. Turning ghost mode on or off mid-hike takes effect on the
  next hike. *(builder's call whether to also apply it live; not required)*
- If the app process is killed and the hike is restored (`RecordingController.restoreActive`), ghosts
  are rebuilt from the hike's first stored point and the elapsed time so far. Ghosts therefore do not
  need persisting.

### Ahead/behind

- Compare by **distance along the route**, not straight-line position: take the user's distance so
  far `d` and their elapsed `t`. Look up the elapsed time `g` at which the ghost had covered the same
  `d`. The difference is `t − g`. Positive means the user is slower (behind), shown as `+m:ss`.
  Negative means faster (ahead), shown as `−m:ss`. This holds up when a route doubles back on itself.
- If the user has walked further than the ghost's whole hike, compare against the ghost's total time.
- Before the user has moved a meaningful distance (under about 50 m), show no number yet, just the
  ghost.

### Live screen layout

- The ghosts' readouts appear as a compact list on the recording screen, one row per ghost: color
  dot, the old hike's date (or name), and the `+/−` time. *(Exact placement on the screen is the
  builder's call. It must not cover the existing controls, and with many ghosts the list must stay
  compact or scroll.)*
- Tapping a row does the same as tapping the ghost's dot: it toggles that ghost's trail.
- Several ghost trails can be open at once. Tapping empty map hides all of them. Landmark pin taps
  and the long-press keep working as now.

## Constraints from the existing app

- Kotlin, Jetpack Compose, Material 3, MapLibre (`TopoMap.kt`), Room. Navigation is the Compose
  NavHost in `MainActivity`. Build on these.
- `TopoMap` already draws the track and marker through GeoJSON sources. Ghost dots and trails belong
  in their own sources and layers there. Ghost dots must be hit-testable the way landmark pins are
  (`queryRenderedFeatures`), and they must not break landmark tapping or the long-press.
- The track color is used in `TopoMap`, which serves both the recording and replay screens.

## My calls (not discussed with the user, open to change)

- **150 m match radius** and the **50 m** "no number yet" threshold.
- **Off-route drop:** the choices are Off / 100 / 200 / 500 m (default 200). "For a while" means
  continuously beyond that distance for **60 s**, so a single bad GPS fix never drops a ghost.
  Distance is measured to the nearest segment of the ghost's whole track.
- **Track color scope:** the chosen color applies to every track the user's own map draws, in
  recording and replay, including imported hikes. It is one global value.
- **Ghost clock during GPS gaps:** it follows the live screen's elapsed time, which keeps ticking up to
  60 s past the last fix. In a longer gap, ghosts pause and then jump forward when the next fix
  arrives. This is accepted.
- **Color picker form:** a grid of about 12 preset swatches, not a free color wheel. Ghost colors
  come from a separate fixed palette. A ghost color that is too close to the chosen track color is
  skipped. With more ghosts than palette colors, colors repeat.
- **Storage:** a plain `SharedPreferences` file (the app has no preference store today) wrapped in a
  small settings class that exposes the values as flows, so screens update when they change.
- **Ghost dot look:** about 80% opacity with a white outline, smaller than the user's own marker.
- The default track color stays `#CC3333`.

## Left to the builder

- Exact visuals: icon choice, swatch layout, row styling, readout placement.
- How ghost tracks are loaded (load all candidates' first points cheaply, then full points only for
  matches) and how often ghost positions are recomputed (once per second is plenty).
- Test strategy. Clock math, matching and the ahead/behind lookup are pure functions and should be unit
  tested like `ReplayTimelineTest`.
