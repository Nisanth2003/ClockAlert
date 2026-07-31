# AlarmTracker — v3 Event-Triggered Alarms (scope)

New capability: alarms that fire on a real-world EVENT reaching a threshold, not a fixed clock time — with a smart ETA fallback so they stay reliable, and an event-driven (never-polling) design so they're battery-cheap. User confirmed: build BOTH trigger sources on ONE shared engine; the "Claude finished" case is LOCAL notification only (no account, no backend — stays local-first).

## Core principle — single alarm + refined estimate + guaranteed fallback

Every event-alarm is, under the hood, ONE `setAlarmClock()` scheduled at the current best-estimate time (reuse the existing NextTrigger/AlarmScheduler model). It is refined and fired by sparse, OS-delivered signals — never a polling loop:

1. **On setup**, capture an ETA (from the source if available — Maps ETA, or a user-entered "arrive by ~HH:MM" / "in ~N min"). Schedule the Doze-proof fallback alarm at that ETA immediately.
2. **Guaranteed fallback**: if the signal never arrives, location/notification access is lost, the phone was off, or the user disables the source — the alarm STILL rings at the estimate. This is the reliability pillar; the event refinement only ever makes it *better*.
3. **Refine on signal**: each geofence transition / notification update reschedules that one alarm earlier or later. No loop.
4. **Hard event fires immediately**: reaching the destination ring / a matching "done" notification → ring now, cancel the fallback.
5. **Bounded convergence only**: extra location sampling is allowed ONLY briefly near the target (e.g. after crossing the outer geofence ring), never continuously. Notification updates are free (pushed by the OS), so the notification source needs no sampling at all.

## Two sources (Phase split)

### Phase 1 — Engine + Geofence (destination) source
- **Engine**: an `EventTrigger` concept attached to an alarm — type (`GEOFENCE` | `NOTIFICATION`), source config, current ETA, fallback state. A coordinator (receiver/service) receives signals, updates ETA, reschedules via AlarmScheduler, fires on hard event, and cancels cleanly on disable/delete. Written so a 3rd source could plug in later.
- **Destination without a map SDK / API key**: user enters a place/address; resolve to lat/lng with `android.location.Geocoder` (no key, no map dependency). Show a confirm + radius slider (arrival ring, e.g. 150–500 m; plus an outer "getting close" ring, e.g. 2 km).
- **Geofencing**: `com.google.android.gms:play-services-location` `GeofencingClient`. Register outer + arrival rings. OS wakes the app on transition (battery-native). Outer-ring entry → one-shot location fix → recompute ETA + optional "≈X km to go" notification; arrival-ring entry → trigger the alarm.
- **Permissions**: `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION` (background is a heavy Play permission — request with a clear rationale and a two-step flow: foreground first, then background). Gate everything; degrade to pure ETA fallback if denied.
- **Fallback ETA**: user-entered expected arrival (or rough distance/assumed-speed estimate) drives the guaranteed alarm.

### Phase 2 — Notification-listener source + discovery
- **NotificationListenerService** (`BIND_NOTIFICATION_LISTENER_SERVICE`); grant flow deep-links to `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` with rationale. Gate + degrade if not granted.
- **Discovery / "Trackable right now"**: enumerate active notifications (`getActiveNotifications` / cached posts) and offer the trackable ones as one-tap triggers ("Google Maps is navigating — alarm on arrival?", "Claude — alarm when done", downloads, timers). This is the "just show the user what's there" experience — no manual setup.
- **Match rules** (stored per trigger): source package + one of: text/title keyword or regex matches (e.g. "done", "complete", "arrived"); OR the tracked ongoing notification is REMOVED (a task/download finishing); OR a parsed metric crosses a threshold.
- **Maps ETA parsing**: from the tracked nav notification text parse remaining distance/time and refine the estimate live (free — pushed on every update). Brittle by nature (locale/format) → the ETA fallback is what keeps it trustworthy; never rely on the parse alone.
- **Local "Claude done"**: match the Claude app package + a completion keyword, or on-removal of its ongoing task notification. No cloud, no account.

## Data / build rules
- **Room migration 4→5** (DB is at v4): new `event_triggers` table (or additive columns on `alarms` — pick the clean, migratable option) keyed to the alarm; store source type, config JSON, current ETA, last-signal timestamp. Additive only, real Migration, export schema, no destructive fallback.
- **New dep**: play-services-location (geofencing). No map SDK, no API key.
- **UI**: an "Event alarm" creation path; the alarm list shows event-alarms with live status ("Arriving · ≈12 km" / "Waiting for: Claude · done" / "Fallback 8:15 if no signal"); reuse the existing ring pipeline + missions for the actual alarm. Keep the M3 quality bar (theme attrs, strings.xml, values-night, insets, ≥48dp, contentDescriptions, empty states).
- **Battery discipline**: geofence + listener are push/event-driven — NO background polling, NO always-on location. Any location sampling must be one-shot and bounded to the near-target window. Document this in code.
- Keep local-first: no account, no server, no network calls for triggering. Do not create a git repo or commit.

## Test plan (Phase 3)
- Build green (`./gradlew assembleDebug`), install on the Pixel_10 emulator (already used for device verification).
- Geofence: use the emulator's mock-location (`adb emu geo fix` / extended controls) to cross rings and confirm ETA refine + arrival trigger, plus the disable→fallback path.
- Notification: post a test notification (or use a real app) matching a rule and confirm the trigger; confirm on-removal trigger.
- Then UI review/rework of the new surfaces against ui-spec.md.
