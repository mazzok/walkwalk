# walkwalk — Photo Landmarks: Design

Date: 2026-10-29
Status: approved
Builds on: `2026-10-23-hike-tracker-design.md`

## Intent

Let the user pin **landmarks** to places on a hike and attach photos to them.
The user named three uses:

- a **memory** of the hike;
- a hint showing **the right way** at a tricky passage;
- a record of an **animal** seen there.

There are no landmark types or categories. A landmark is simply a place with
photos, and every landmark looks the same on the map.

Landmarks can be added **live** while recording and **afterwards** on a
finished hike. A whole hike, including its landmarks and photos, can be **sent
privately to another walkwalk user**, who sees everything after opening it.

## Goals

1. Add a landmark at the **current position** while recording, in one tap.
2. Add a landmark at **any spot on the map**, on a live hike or on a finished
   hike's replay.
3. Attach photos to a landmark:
   - **Camera:** only while the hike is being recorded.
   - **Photos from this hike:** the phone's photos taken between the hike's
     start and its end (or "now" while recording). The filter is by time only,
     not by location.
4. Show a landmark's photos as **round thumbnails** in a see-through strip
   under the pin. Tapping a thumbnail opens the photo.
5. **Send a complete hike** (track, landmarks, photos) as one file through
   Android's Share menu, and open such a file in walkwalk.

## Non-goals

- Landmark types, categories, names, colours or different icons
- Notes or text on a landmark *(my call: not asked for; the photos carry the
  meaning)*
- A general gallery picker. Only the two ways above exist.
- Matching photos to landmarks by their GPS location. Only time is used.
- Proximity alerts ("you are near a landmark") when walking a route again
- Showing landmark photos in other apps (Komoot, Garmin, …). Only walkwalk
  users matter.
- A server, accounts or online sharing. Sending a file is the only way.

## Anti-goals (it fails even if it "works")

- **Existing hikes are lost** when the app updates to the version with
  landmarks. The database upgrade must keep all existing data.
- **A deleted gallery photo breaks the screen** or crashes the app. It must
  show as a "missing" thumbnail instead.
- **A received hike arrives without its photos**, or the share file is too big
  to send through a messenger.
- **Pins appear by accident** while moving the map around.
- **Adding a landmark disturbs the recording** (for example, opening the camera
  stops GPS).

## Constraints

- Builds on the existing app: Kotlin, Jetpack Compose, MapLibre, Room
  (currently schema **version 1, no migrations**), `minSdk` 34, Pixel 9a.
- Recording keeps running in its foreground service while the camera app or
  the photo picker is in front.
- Android 14+ photo access: the user may grant **full** or **partial**
  ("selected photos only") access. Both must work, as explained below.
- No network is needed for any of this.

## Decided by the user

- Landmarks have **no type**. Every landmark uses the **same generic pin
  icon**, which is always visible on the map.
- Photos are shown in **layout A**: a small see-through strip of round
  thumbnails floating directly under the tapped pin, moving with the map, so
  the map stays visible underneath. (Mockup:
  `.superpowers/brainstorm/571-1793313433/content/landmark-panel-v2.html`.)
- There are exactly **two ways to add a photo**: the camera, or a pick from the
  phone's photos filtered by the hike's time window.
- **Camera only on an active hike.** On a finished hike it makes no sense, so
  the menu doesn't offer it.
- The **current-position pin button** drops the pin and **immediately opens
  the landmark menu**.
- If a photo is later **deleted from the phone**, the landmark keeps its place
  and shows a **"missing" thumbnail**.
- Sharing is **private, whole-hike, walkwalk to walkwalk**: one file sent
  through any messenger, email or Quick Share.
- A landmark is added on the map with a **long-press**.
- **Waypoints in an imported GPX file become landmarks.**
- The import button becomes **Import** and takes `.gpx` and `.walkwalk`.
  Opening the same `.walkwalk` file twice opens the existing hike instead of
  importing it again.

## Screens and behaviour

### Pins on the map

- Landmarks show as the same blue pin on both the recording map and the
  replay map, and they're always visible.
- Tapping a pin highlights it (orange) and opens its **photo strip**:
  - a see-through, slightly blurred panel just under the pin, holding round
    thumbnails and a ＋ at the end;
  - it moves with the map;
  - with many photos it scrolls sideways;
  - tapping the map elsewhere, or another pin, closes it *(my call)*.
- Only one strip is open at a time.

### Adding a landmark

- **Pin button** (recording screen only, on the right side next to the
  re-centre button) adds a landmark at the **last accepted GPS fix** and opens
  the menu for it right away. While there is no fix yet, the button is
  disabled *(my call)*.
- **Long-press on the map** (recording screen and replay screen) adds a
  landmark at the pressed spot and opens its menu. This works on every hike,
  whether recorded, imported from GPX or received. The user chose a long-press
  over a plain tap, so that pins don't appear while panning, and a plain tap
  can keep its job of closing the photo strip.
- Each landmark stores a time *(my call)*:
  - live: the fix time;
  - long-press: the time of the nearest track point;
  - **no time** when the hike's track has no timestamps (an imported planned
    route).

  The time puts landmarks in order along the hike and goes into the shared
  file.

### The landmark menu (＋, and right after adding)

- 📷 **Take photo**: only while the hike is recording or paused. It opens the
  phone's camera app. The photo is saved to the gallery
  (`Pictures/walkwalk`) and attached.
- 🖼 **Photos from this hike**: opens the picker described below. It is not
  offered for a hike without timestamps, because there is no time window
  *(my call)*.
- 🗑 **Delete landmark**: asks for confirmation first. The photos stay in the
  gallery.
- If the menu is dismissed without adding a photo, the landmark stays as a
  pin without photos *(my call: a bare pin is still useful, for example to
  mark a junction)*.

### Photos from this hike (picker)

- A grid of the phone's photos whose **taken time** lies between the hike's
  start and its end (or now, while recording), newest first. The user ticks
  photos and confirms.
- For an imported hike with timestamps, the window is that hike's own start
  and end, even if it was years ago. That's intended: it finds the photos from
  that day.
- Photos already on this landmark show as ticked. A photo may be on more than
  one landmark *(my call)*.
- **Partial photo access:** if the user only allowed "selected photos", the
  grid shows only those, plus a hint with a button that opens Android's own
  picker to allow more photos. With no access at all, the hint explains why
  the list is empty *(my call)*.
- The camera route needs no photo permission.

### Viewing a photo

- Tapping a thumbnail opens the photo full screen, with pinch zoom and
  swiping between that landmark's photos *(my call)*.
- A **missing** photo shows as a grey circle with ⊘. Tapping it explains
  "This photo is no longer on the phone" and offers **Remove** *(my call)*.
- A photo that still exists but **can't be read**, because photo access was
  withdrawn or the photo is no longer among the "selected photos", shows a
  **lock** instead. Tapping it offers to allow access again, not Remove.
  Existing photos must never be offered for removal as if they were gone
  *(my call)*.

### Sending and receiving a hike

- The hike's ⋮ menu in the list gets **Send hike** next to the existing Export
  GPX. It builds one file, `<hike name>.walkwalk`, and opens Android's Share
  menu.
- The file is a **zip** containing *(my call on the details)*:
  - `hike.gpx`: GPX 1.1 with the track as today, plus one `<wpt>` per
    landmark, carrying its time and a `<link href="photos/<id>.jpg">` per
    photo. Other apps can read the track and the landmark positions from it.
  - `photos/`: the photos as JPEG, scaled down so the long edge is at most
    1600 px (about 300 KB each). Missing photos are left out.
- **Opening** a `.walkwalk` file (tapping it in a messenger, a file manager, or
  through Share) imports it as a new hike with all landmarks and photos. It
  then behaves like any other finished hike, and landmarks can be added or
  deleted on it.
- **How it travels:** walkwalk never goes online for this. The file is
  built in the app's cache and handed to Android's Share menu, the same way
  Export GPX works today. The chosen messenger or email app does the transfer.
  The receiver needs walkwalk installed; it's sideloaded, so they get the APK
  once.
- **Three ways in on the receiving phone**, all leading to the same import:
  - tapping the file and choosing walkwalk in "Open with";
  - sharing the file from the chat into walkwalk;
  - the list's import button, which becomes **Import** (instead of Import
    GPX) and accepts both `.gpx` and `.walkwalk`, for example from Downloads.
    This is the reliable fallback, because some apps label the file too
    vaguely for "Open with" to offer walkwalk.
- **The same file opened twice** doesn't create a second copy. walkwalk
  recognises the hike and opens the one it already has.
- **Received photos stay inside walkwalk.** They don't appear in the
  receiver's gallery.
- **Size:** there's no hard limit. Before the Share menu opens, the app shows
  the file size, and warns when it is over 25 MB that email may refuse it
  (messengers take much more) *(my call)*.
- **Export GPX** also writes landmarks as `<wpt>`, without photos *(my call)*.
- **Importing a plain GPX file** turns its `<wpt>` waypoints into landmarks
  (decided by the user), for example the points of interest of a Komoot route:
  - position and time come from the waypoint, if it has a time;
  - name, description and symbol are ignored, since landmarks have none;
  - photo links in a plain GPX file are ignored.
  
  Exporting a hike as GPX and importing it again therefore keeps its
  landmarks (without photos).

## Data and storage

- **Landmark:** id, hike id, latitude, longitude, time (may be empty).
- **Landmark photo:** id, landmark id, a reference to the image, and the photo's
  taken time.
  - The phone's own photos are **referenced, not copied** (a MediaStore
    `content://` link). That's why a photo deleted from the gallery shows as
    missing.
  - Photos that arrived in a received hike have no gallery copy, so they're
    stored in the app's private storage. Deleting the hike deletes them.
- **Database upgrade** from version 1 to 2 adds the two tables with a real
  migration. Never wipe the database.
- Deleting a hike deletes its landmarks and their photo references, but never
  anything in the gallery.

## Left to the builder

- How pins and the strip are drawn: a MapLibre symbol layer for pins and a
  Compose overlay positioned from the map projection is the obvious route.
- The thumbnail loading and caching approach
- The zip and photo scaling details within the limits above
- The intent filters for `.walkwalk` files, given that Android matches file
  extensions unreliably
- How a received hike is recognised as already imported (for example an id
  written into the file)
- The test strategy, which should cover:
  - the v1→v2 database migration with real v1 data;
  - the time-window photo query;
  - a full send → receive round trip of a hike with photos;
  - a missing photo and an unreadable one;
  - GPX waypoint import, with and without times.
- Rebuilding the v1 schema for the migration test. The project has
  `exportSchema = false`, so no v1 schema file exists.
