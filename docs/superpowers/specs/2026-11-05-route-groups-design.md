# Route groups — design

Date: 2026-11-05

## Intent

The user rides the same routes several times a day (A→B and B→A). The hike list fills up with nearly
identical entries and loses its overview. Hikes on the same route are grouped automatically in the list; the
user names a group once ("A nach B") and from then on sees routes instead of an endless list of single hikes.

## Goals

- Finished hikes whose tracks stay close to each other **over the whole track** form one group in the list.
- The closeness limit (the "bubble") is a setting in metres.
- A group can be named; the name sticks, and new hikes on that route show up under it without any action.

## Non-goals

- No change to replay, ghost or race: single hikes stay openable from inside a group exactly as today.
- No group-level statistics, maps or comparisons.
- No manual moving of hikes between groups.

## Anti-goals

- A→B and B→A ending up in the same group (the name "A nach B" would be wrong for half of it).
- The list becoming slow to open as hikes pile up.
- Losing a group name because one hike was deleted or the setting changed.

## Decisions made with the user

- A→B and B→A are separate groups.
- New hikes join the matching group automatically and show under its name.
- Hikes that match nothing stay single rows in the list.
- Hikes stay reachable inside a group (expand the group) with all existing actions.
- One global threshold in Settings, not per group.

## Calls I made

### Distance measure
- **Discrete Fréchet distance** between the two tracks: the smallest "leash" length with which you can walk
  both tracks start-to-end, never going backwards. It is the "bubble over the whole track" the user described,
  and it respects direction: A→B vs B→A is roughly the route length apart, so they never group.
- Tracks are first resampled along their length every 10 m (coarser for very long tracks, at most 500 points),
  so recording rate and stops do not matter. Segment gaps (manual pauses) are bridged.
- Cheap pre-check before the full computation: start-to-start and end-to-end must both be within the
  threshold (a necessary condition of Fréchet).

### Grouping
- Leader clustering, oldest hike first: a hike joins the group whose first (oldest) hike is nearest to it
  in Fréchet distance, if within the threshold; otherwise it starts a new group. Every member is therefore
  within the bubble of the group's leader, and groups cannot "chain" into one long blob.
- Only groups with ≥ 2 hikes are shown as groups; a group of one is an ordinary row.
- Groups are recomputed whenever the list of finished hikes or the threshold changes, off the main thread.
  Resampled shapes are cached in memory per hike id (a finished track's geometry never changes).

### Names
- New nullable column `HikeEntity.routeName` (DB v3, migration adds the column).
- "Name route" on a group writes the name to every hike in the group.
- A group shows the most common `routeName` among its hikes (newest wins a tie), else "Unnamed route".
  A new hike joining the group therefore shows under the existing name, and deleting a hike or changing the
  threshold never loses the name as long as some named hike remains in the group.

### Setting
- "Group hikes on the same route" in Settings: Off, 25 m, 50 m, 100 m, 200 m. Default 50 m (GPS noise is
  about 5–20 m; two lanes of one road stay well within 50 m). Off shows the plain list as today.

### List UI
- Group row: route name, "N hikes · <date of newest>", a chevron; tap expands/collapses in place.
  Overflow menu: "Name route". Expanded hikes are the usual rows, indented.
- Entries (groups and single hikes) are ordered by their newest hike, newest first, as today.
