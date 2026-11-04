# Ghosts in replay, with a shared-range speed heatmap

## Intent

The user walks the same routes again and again. Ghost mode already shows, live, where earlier walks of
the route would be right now. They now want the same when *reviewing* a recorded hike: the other walks
that started at the same place move along beside the replay, so they can see where they were faster or
slower than on those days. A speed heatmap, which already exists for a single hike, then lets them see
*why*: all the hikes on screen are coloured on one common blue-to-red scale, so the same colour means
the same speed everywhere.

This builds on two things already in the app: ghost mode (settings switch, `ghost/` package) and the
single-hike speed heatmap (`SpeedHeatmap`, the "Show speed heatmap" switch in the expanded replay card).

## Goals

1. **Ghosts in replay.** When ghost mode is on in Settings and a recorded hike is replayed, every
   *other* saved hike whose first point is within 150 m of this hike's first point becomes a ghost
   (`MATCH_RADIUS_M`, same rule as live). Older and newer hikes both count. The replayed hike never
   ghosts itself. With ghost mode off, replay looks exactly as it does today.
2. **Synced to the start.** All ghosts start when this hike starts. A ghost is shown where its own hike
   was at the same *real elapsed time*, not the compressed replay clock: rests in this hike do not
   fast-forward or skip a ghost's walk. (A ghost's own rests keep their full length, as in live ghost
   mode: `GhostTrack` already works this way.)
3. **Scrubbing.** Dragging the timeline or the elevation profile moves every ghost to the matching spot.
   Ghost positions depend only on the current replay moment, never on which way the user dragged.
4. **Vanish when off route.** A ghost vanishes once this hike has been farther than the Settings
   drop distance from the ghost's whole track for 60 s in a row (`OFF_ROUTE_HOLD_MS`; drop setting
   default 200 m, with an Off choice that keeps every start-matched ghost). Dragging back to before that
   moment brings the ghost back. In other words, "vanished" is decided per replay moment, not remembered.
5. **Finished ghosts.** A ghost whose hike ended before the current moment stays on the map at its end
   point until the replay ends.
6. **Same look as live.** One dot per ghost in the palette colour `ghostColors` gives (kept distinct from
   the user's track colour), fainter than the user's marker. Tapping a dot opens that ghost's full trail
   at about 50% opacity; tapping it again or tapping empty map closes it. Landmark pins keep tap priority
   as they do live.
7. **Shared-range heatmap.** The existing "Show speed heatmap" switch in the expanded top card now
   colours this hike *and every open ghost trail* on a single blue-to-red range. That range runs from
   the slowest to the fastest moving speed across this hike and all currently open ghost trails
   (resting speeds excluded, as for a single hike). The range covers each open trail's *whole* track, not
   just the part up to the ghost's current position, so it only changes when a trail is opened, closed
   or vanishes, never while playing or scrubbing. A vanished ghost's trail is hidden and does not take
   part in the range. With no ghost trail open, the range is just this hike's, exactly as today.
   The legend in the top card shows the shared range.

## Non-goals

- Ahead/behind time numbers in replay (live ghost mode has them). Not requested; a later addition.
- Choosing which ghosts to show, or ranking them. All matches show.
- Ghosts for hikes without timestamps. Such a hike cannot be replayed at all today; imported routes
  without times are skipped as ghost candidates.
- Matching hikes that start elsewhere or run in reverse.
- Changing the existing live ghost mode or its settings.
- Heatmap clipping of outlier speeds, and remembering the heatmap switch between visits.

## Anti-goals

- A ghost shown at the wrong time is the core failure. Ghost position must be real elapsed time.
- Replay must stay smooth while playing at ×300: ghost work must not make the clock, the slider or the
  map stutter.
- Ghosts only read. Nothing about ghost mode may change what is stored for any hike.
- A failure loading or computing ghosts must never break the replay screen; the replay shows as it does
  without ghost mode.

## Constraints from the existing app

- Replay today: `ReplayScreen` + `ReplayViewModel`, one `ReplayTimeline` with stops compressed to 30 s.
  The frame already carries real elapsed time (`ReplayFrame.elapsedMs`).
- Live ghost mode (`GhostSession`) is built around a live, forward-only feed. Replay needs the same
  ghost data for arbitrary moments in any order, so it needs its own approach (for example, computing
  each ghost's vanish moment once when the replay opens) rather than reusing the forward-only
  per-second update. Reusing `matchGhosts`, `GhostTrack`, `ghostColors`, `OffRouteTracker`'s rule and the
  repository's `ghostCandidates()` is expected.
- Map drawing: `TopoMap` already has ghost dot and ghost trail layers and a `heat-line` layer; ghost
  trails currently take one colour per trail, so heat colouring needs per-edge colours for ghost trails.
- Stop data per hike is available from the repository (`stops(id)`); speeds come from
  `Stats.speedsKmh` (which now handles sparse fixes).
- Everything here is local and read-only; nothing is persisted.

## My calls (not discussed with the user, open to change)

- Ghost clock is real elapsed time, not the compressed replay clock (Goal 2).
- Vanish is a function of the replay moment, so it is reversible by scrubbing (Goal 4).
- Finished ghosts stay at their end point (Goal 5).
- Only *open* ghost trails take part in the shared heatmap range (Goal 7); ghosts whose trail is closed
  just show as dots, with nothing to colour.
- The heatmap range uses each open trail's whole track, so it is stable while playing (Goal 7).
- "Trail open" is the user's own choice (a tap) and is kept separately from "ghost has vanished". A trail
  is shown only while it is open *and* its ghost has not vanished at the current moment. If the user
  drags back to before the vanish moment, a trail they had opened comes back, and the range follows.
  This keeps the range a function of the current moment and the user's taps, never of scrub history.
- The replayed hike is excluded from its own ghost list by hike id.
- Ghost mode is read when the replay screen opens; flipping it in Settings takes effect the next time
  a hike is opened.

## Left to the builder

- How and when to compute vanish moments, ghost loading, and whether to load ghosts off the main thread
  (loading may be slow with many matches).
- Exact data flow into `TopoMap` and how ghost trails are drawn with per-edge colours.
- Test strategy, including how to exercise scrubbing and the shared range.
