# Racing mode — design

Date: 2026-11-04

## Intent

Ghost mode shows old hikes running alongside the live one, with a time difference (`+1:30`) on screen.
When racing, the phone is in a bag and the user wears earphones, so the screen is useless. Racing mode
turns one ghost — the *rival* — into sound, so the user can hear, without looking:

- whether the rival is ahead or behind,
- how close it is,
- whether the gap is closing or opening (who is catching whom),
- the moment of a pass.

The sound is a proximity beeper (think the motion tracker in *Alien*), with a Doppler-style pitch bend.

## Goals

- Audible, eyes-free race feedback against one rival ghost, through earphones, with the screen off.
- The four states are distinguishable by ear (see Sound design).
- Choosing to race is done per hike, at start.

## Non-goals

- No change to the visual ghost mode: all matched ghosts still show on the map and in the ghost list.
- No sound for ghosts other than the rival.
- No spoken announcements (voice / TTS).
- No racing in replay mode.
- No stereo/3D positioning of the sound.

## Anti-goals (a failure even if it technically works)

- The rival changing mid-race without the user passing anyone (the sound would "jump").
- Sound stopping when the screen turns off or the app goes to the background.
- Racing mode affecting the recording itself: a sound or ghost failure must never stop or corrupt a hike
  (same rule as ghost mode today).
- Music dipping in volume every few seconds while the rival is far away.

## Decisions made with the user

### Starting a race
- Racing is chosen each time a hike is started — a race start next to the normal "Start hike"
  (`HikeListScreen.kt`). A normal start never makes racing sounds.
- The exact UI (second button "Start race", or a toggle) is left to the builder, matching the existing start
  row. Racing uses the ghost matching rules (start within `MATCH_RADIUS_M`).
- "Start race" works even when the ghost-mode setting is off (decided): starting a race loads ghosts for that
  hike regardless of the setting. `GhostSession.startLoadLocked` currently skips loading when ghost mode is off,
  so racing needs a load path that ignores the setting. *My call:* in that case the ghosts also show on the map
  for that hike, as in ghost mode.

### Picking the rival
- Candidates: the ghosts matched to this hike's start (existing `matchGhosts`).
- Filter by route similarity: only ghosts whose route the user's track has stayed close to are eligible
  (reuse `OffRouteTracker` / `GhostTrack.distanceToTrackM`). *My call:* racing uses its own fixed off-route
  limit of 100 m (with the usual 60 s hold), independent of the user's `ghostDropM` setting, so the rival can
  always drop even when that setting is Off. Racing therefore keeps its own tracker per candidate.
- Among eligible ghosts, the rival is the one with the **highest average speed on the same clock as the gap**:
  `GhostTrack.totalDistanceM / GhostTrack.totalMs` (stops included, manual pauses excluded). *My call:* this
  instead of `HikeEntity.avgSpeedKmh` (moving-only), so the chosen rival is really the one that is hardest to
  beat by the gap the user hears.
- The rival is picked at the first GPS fix (decided). Where old routes share a start and split later, this can
  cause one early switch about a minute after the split; that is accepted, and the switch sound announces it.
- **The rival is locked once picked.** It is only replaced if it drops out because the user's route has left
  the rival's route (the off-route rule). On replacement a short distinct "switch" sound plays, then the new
  rival's beeping starts. The new rival is picked by the same rule from the ghosts still eligible.
- If no ghost matches at the first GPS fix (or all eligible ghosts drop), a short "no rival" sound plays once
  and the hike records normally, silently.

### Sound design
The gap is the existing ahead/behind time (`aheadBehindMs`: positive = user behind the rival).

| Cue | Encodes |
|---|---|
| Beep **rate** | closeness — fast when the gap is small, slow when large |
| Beep **pattern** | side — single beep = rival ahead of the user, double beep ("beep-beep") = rival behind |
| Beep **pitch bend** (Doppler) | trend — pitch raised while the gap is shrinking, lowered while it is growing, neutral when steady |

So: double + speeding up = rival catching the user; single + speeding up = user catching the rival;
rate slowing = gap opening. At a pass, the pattern flips and the pitch drops at the same moment.

*My calls (tunable, put the numbers in one place):*
- Rate maps the absolute gap 0 s → ~4 beeps/s down to 120 s → 1 beep every ~4 s; beyond 120 s the rate stays
  at the slowest so the user knows the race is alive.
- Doppler bend is driven by the rate of change of the gap (smoothed over a few seconds so GPS noise doesn't
  wobble the pitch), clamped to a modest range (order of ±3 semitones).
- No sound until the gap is defined (`aheadBehindMs` is null for the first `MIN_COMPARE_DISTANCE_M`).
- When the rival has finished its hike, the gap keeps growing as normal (user behind a finished rival).
- Sounds are synthesised in code (no audio assets).
- While paused, the race goes silent.

### Music / other audio
- Beeps play over the user's music/podcast.
- While the rival is **close** (gap under ~30 s — my call), the app requests transient audio focus with
  ducking so the music gets quieter; when the rival moves out of that range focus is released and the music
  returns to full volume. Far-away beeps play over the music without ducking.

## Constraints from the current code

- **Ghost updates are driven from the UI today**: `RecordingScreen.kt` calls `GhostSession.update` in a
  `LaunchedEffect`. With the phone in a bag and the screen off this does not run reliably. The race logic and
  audio must be driven from the recording side — `RecordingService` (a foreground `LifecycleService` that
  already collects `RecordingController.state`) or an app-scoped object it starts. The visual ghost list may
  keep its current path.
- Audio must play from the foreground service while the screen is off; usage should be a guidance/navigation
  style audio stream so it ducks music and routes to earphones.
- Everything ghost-related is "read only, failures logged" — keep that for racing.
- Pure logic (rival choice, gap → rate/pattern/bend mapping, lock/switch rules) should be plain Kotlin,
  unit-tested like `GhostRules` / `ReplayGhosts`.

## Left to the builder

- Waveform, beep length, base frequencies, envelope.
- Exact start-UI form, and showing on the recording screen which ghost is the rival.
- How the race flag is stored on the hike (if at all) and survives process restore (`restoreActive`).
- Audio implementation (`AudioTrack` synth vs. `ToneGenerator`/`SoundPool`), as long as the pitch bend works.
