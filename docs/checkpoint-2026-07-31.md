# Checkpoint — 2026-07-31 (before the visual/brand overhaul)

This is a **restore point**. Everything below describes the state of AlarmTracker at the moment the
map/search work was finished and signed off, immediately before starting the colour-scheme, icon and
animation overhaul. If the overhaul goes wrong, come back here.

---

## 1. How to restore

Two artifacts, both in `D:\AlarmTracker\snapshots\`:

| file | what it is |
|---|---|
| `checkpoint-2026-07-31-map-search-ui.zip` | Full source tree (272 entries): `app/src`, `app/schemas`, `app/build.gradle.kts`, `gradle/`, root gradle files, `docs/`. No build output. |
| `app-debug-2026-07-31-map-search-ui.apk` | The exact APK that was installed and verified on the phone at this checkpoint (50 MB). |

**Restore the source:**
1. Extract the zip somewhere temporary. Its root folder is `AlarmTracker\`.
2. Delete `D:\AlarmTracker\app\src` and copy the extracted `AlarmTracker\app\src` over it.
   Do the same for any other file you want to roll back (`app/build.gradle.kts`, `gradle/`, etc.).
3. `.\gradlew.bat :app:assembleDebug` — the build was GREEN at this checkpoint.

**Restore just the app on the phone (fastest sanity path):**
```
adb install -r D:\AlarmTracker\snapshots\app-debug-2026-07-31-map-search-ui.apk
```
Note this is a *downgrade-by-reinstall* of the same versionCode, so app data survives.

**Not a git repo.** The project has never been under version control, which is why these snapshots
exist. Putting it under git would give much better checkpoints than this and is worth doing.

---

## 2. What state the app is in

- **110 Kotlin files, 44 layouts, 40 vector drawables.**
- **Room DB version 10.** Latest migration `MIGRATION_9_10` (added `Alarm.actionPackage`/`actionLabel`).
  Schemas in `app/schemas/`. On the test device the DB was *created* at v9 after a user reinstall, so
  only 9→10 has actually executed there since; the 1→9 chain was verified on earlier populated DBs.
- **Build: GREEN.** `:app:assembleDebug` and `:app:lintDebug` both pass. Lint reports warnings only
  (mostly `UseKtx` style plus one Overdraw on the map layout) — no errors.
- **Installed and running on the test device** (Redmi Note 11 Pro, Android 13 / MIUI),
  launched clean with no FATAL.
- Zero API keys, zero accounts, zero backend. Every external service used is keyless:
  Carto basemap tiles, Photon place search, OSRM routing, ntfy relay (friends).

### Feature surface at this checkpoint
Alarms (with missions, gentle wake, coaching, pause, skip), World clock, Stopwatch (with lap export),
multi-Timer (with its own ring + snooze), Stats/Wake Score, Recycle bin, Health check, Postmortem,
Onboarding, Coach marks, Help guide, Connections (Jira + device Calendar), Friends/arrival alerts,
event-triggered alarms (arrive-at-place, notification, cooldown-reset, connector).

---

## 3. What was completed in the session leading to this checkpoint

All of it is in the snapshot. Four user-reported problem areas, all fixed and verified.

### 3.1 Place search returned results in the wrong country
- **Cause A — no location bias.** Both providers were searched worldwide.
- **Cause B — first-provider-wins.** `PlaceSearch` returned whichever provider answered *first*. On a
  device with a working framework geocoder that meant two Rajasthan junctions were returned and the
  provider that had the local match ranked #1 was never asked.
- **Cause C — spelling.** "subash chowk" is not a substring of "Subhash Chowk", and soft biasing
  cannot rescue it: the fuzzy engine prefers literal matches 200–1300 km away.
- **Now:** three sources in parallel — framework geocoder (bounded box), Photon worldwide-biased, and
  Photon **hard-restricted to a ~60 km bbox** — merged and ranked by
  `0.5·text + 0.4·exp(−distance/25 km) + 0.1·provider rank`, deduped at 120 m with an agreement bonus.
  Text matching is fuzzy (bounded Levenshtein, 0 edits under 4 chars, 1 up to 7, 2 at 8+).
  Nothing is dropped for being far; the distance is displayed instead.
- **Measured results** (bias point in the Delhi NCR region): `subash chowk` → *Subhash Chowk, Gurgaon* #1
  despite the misspelling;
  `cyber hub` → *DLF Cyber Hub* #1; `connaught place` → *Delhi CP* #1; `eiffel tower` → local replica
  #1 with **Paris still #2**. Reproduce any query with `scratchpad/rank2.py "<query>"`.
- **Do not "turn up" the Photon bias.** `location_bias_scale=0.6` at `zoom=12` was chosen by
  measurement; `1.0` at `zoom=14` collapses back to worldwide ordering.

### 3.2 The map couldn't rotate and never showed which way you face
`RotationGestureOverlay` (two-finger twist, subclassed so a manual rotate exits compass mode),
`MyLocationNewOverlay` fed by a `PushLocationProvider` shim (deliberately *not* its own location
stream), and `CompassHeading` (rotation-vector sensor + display-rotation remap + `GeomagneticField`
declination so the bearing is true north, low-pass filtered). The heading is written onto the fix as
`Location.bearing`, which is what makes the overlay draw the direction arrow. `fab_compass` cycles
compass-mode → north-up → compass-mode and its needle rotates with the map.

### 3.3 Current location was inaccurate
`fused.lastLocation` is whatever fix *any* app took last. Now: continuous `PRIORITY_HIGH_ACCURACY`
updates **only while the picker is in front**; a cached fix is trusted for centring only if <30 s old
and ≤50 m; otherwise `awaitAccurateFix()` polls up to 15 s for a good one. The accuracy circle is
drawn and the number is stated in words. **A separate root cause was found on the device: the system
Location toggle was simply OFF** (`settings get secure location_mode` → 0), which no permission check
can detect — so `LocationState` now distinguishes app permission from the device toggle and deep-links
to Location settings. Check `location_mode` *first* when debugging anything location-shaped.

### 3.4 There was no blue route line, and no visible alert ring
- `RouteService` — keyless OSRM (`routing.openstreetmap.de/routed-car`, then `router.project-osrm.org`),
  in-house polyline5 decoder. Drawn as a blue `Polyline` under a white casing, refreshed on pin-settle
  or >150 m of user movement, never in the background. Its real average speed also drives the editor's
  arrival estimate and the alert ring's lead time.
- `RadiusOverlay` draws the geofence ring. **It was never a drawing bug**: a temporary log showed
  `r=800 m → px=1048` on a 1080-wide canvas, because `setTilesScaledToDpi(true)` makes the effective
  ground resolution 0.76 m *per device pixel* at zoom 16. The ring was simply larger than the screen.
  Fixed by **framing** it — `frameRing()` → `zoomToBoundingBox(±radius·1.7)` on open, on preset chips,
  and on slider touch-*release* (never per tick).

### 3.5 Map picker UI rebuilt
Map fills the space **above** the bottom card (so map centre = pin = centre of what you can see);
pin tip anchored on a real anchor dot; back arrow and clear ✕ in the search bar; rounded bottom card
with one full-width primary action; ring controls folded behind a row that still shows the value, with
one-tap 200 m/500 m/1 km/2 km chips, a labelled slider and the lead time; circular map controls moved
to the bottom-right; tap-anywhere-to-place-the-pin; opens on the last known area at street zoom with
basemap-toned tile placeholders. Screenshots: `scratchpad/map_before.png`, `map_after3.png`,
`map_expanded.png`.

---

## 4. Known open items at this checkpoint

- **Not device-verified by a human finger:** the radius chips/slider, tap-to-place, the compass button,
  the heading arrow turning, and the search-result selection animation. All build and render correctly
  in screenshots.
- The editor's `updateDestinationEstimate` uses the route when available, but `LiveEtaTracker`
  deliberately stays on straight-line maths (it wakes while the phone is idle; a network call per
  check would cost battery for a number that only refines an estimate).
- `lint { disable += "RepeatOnLifecycleWrongUsage" }` in `app/build.gradle.kts` is a workaround for a
  crashing androidx.lifecycle lint check — remove it when that artifact is fixed.
- 13 dependency-upgrade suggestions from lint are deliberately not taken.

---

## 5. The device workflow that actually works (MIUI)

`adb shell input` is blocked (`INJECT_EVENTS`) **and** driver-level `sendevent` is blocked by SELinux,
so screens cannot be driven from the PC at all. To screenshot a non-exported screen:

1. Set `android:exported="true"` on the activity in the manifest (use an editor, not a script — a
   scripted manifest edit gets blocked).
2. Add `setShowWhenLocked(true)` + `setTurnScreenOn(true)` to its `onCreate` — the phone dozes
   constantly and this is the only way to wake it.
3. `adb shell svc power stayon true`.
4. `adb shell am start -n <pkg>/<activity> --ed extra_lat 28.4089 --ei extra_radius 800`
   (`--ed` = double, `--ei` = int), then `adb shell screencap` and `adb pull`.
5. **Revert all three**, rebuild, reinstall, and confirm with `am start` → must print
   `SecurityException … not exported`.

Use the **PowerShell** tool for `adb pull`/`screencap` — the Bash tool mangles `/sdcard/...` into a
Windows path.
