# AlarmTracker — v1 Decision Document (Build Session Commit)

**Positioning locked:** "The alarm that never fails — and proves you're actually waking up better." v1 must demonstrate both pillars: a bulletproof ring pipeline and the wake-analytics surface the app is named for.

## 1. v1 Scope — Build Exactly This

### A. Core alarm engine + ring pipeline
- Alarm CRUD with weekly repeat, label, sound on/off, vibrate, per-alarm snooze duration.
- Scheduling via `setAlarmClock()`, single next-alarm derivation from DB.
- `USE_EXACT_ALARM` (33+) + `SCHEDULE_EXACT_ALARM` (`maxSdkVersion=32`) with `canScheduleExactAlarms()` gate.
- Ring pipeline: `AlarmReceiver` → FGS (type `systemExempted`, owns MediaPlayer `USAGE_ALARM` + vibration) → full-screen `AlarmActivity` (`setShowWhenLocked`/`setTurnScreenOn`).
- Full-screen-intent notification with Snooze/Dismiss actions + `POST_NOTIFICATIONS` runtime request.
- Reschedule receivers: `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, exact-alarm-permission-changed (31+).
- Permission warning banner card on Alarm List (exact alarm / notifications / FSI / alarm volume 0) with settings deep-links.
- Gradual volume ramp (settings toggle, animate player volume over ~60s).

### B. Tracking / stats — the namesake differentiator
- `alarm_events` log from day one: SCHEDULED / FIRED / FIRED_LATE / SNOOZED / DISMISSED / MISSED with wall-clock + elapsed timestamps and time-to-dismiss.
- Stats screen: Wake Score card (0–100 from wake-time variance vs. target, snooze count, time-to-dismiss), supporting stat row (avg snoozes, on-time %, current zero-snooze streak).
- Wake-time trend chart + snooze bar chart as one lightweight custom View (NO MPAndroidChart dependency).
- Week / Month / 3-Months `ChipGroup` range selector; plain-language one-line explanation under the score.
- Designed empty state ("stats appear after your first alarm rings").

### C. Missions — minimum viable wedge
- Math mission only (difficulty per alarm; dismiss routes to mission screen; FGS keeps ringing until solved; completion time logged).

### D. Industry-level UI shell (per ui-spec)
- `Theme.Material3Expressive.DayNight.NoActionBar`, dynamic color + static seed fallback, full `values-night`, edge-to-edge insets.
- Bottom nav (Alarms / Stats / Settings), `MaterialFadeThrough`, collapsing large-title app bar, FAB, alarm cards with container-color active state, swipe-to-delete + UNDO, live "Alarm in 7 h 32 min" header.
- Add/Edit as `BottomSheetDialogFragment` with `MaterialTimePicker`, day-of-week `MaterialButtonToggleGroup`, auto-save on dismiss.
- Settings via `PreferenceFragmentCompat`: time format, default snooze, volume ramp, theme, dynamic-color toggle.
- App icon + monochrome themed icon + SplashScreen compat, haptics on toggle/dismiss.

## 2. Explicit v1 EXCLUSIONS (do not build; do not stub UI for)
- Calendar-aware smart alarm (v1.5) · Shift patterns (v1.5) · QR/steps/photo missions (v1.1) · Pre-flight WorkManager job (v1.1) · Missed-Alarm Postmortem screen (v1 logs the data; screen is v1.1) · Widget & Wear tile · Morning Report Card · CSV export/privacy screen · Pause-for-date-range (schema reserves columns) · Sunrise glow / smart-wake · Health Connect · Social/share · OEM battery-killer heuristics · Ringtone picker (row shows static "Default") · Monetization/Play declarations.

## 3. Screen List for v1
1. Alarm List (home) — ui-spec §1, incl. permission banner + live next-alarm header.
2. Add/Edit Alarm bottom sheet — §2; Mission row Off/Math only; Sound row static "Default".
3. Alarm Ringing activity — §3; snooze tonal button + oversized dismiss; ≥64dp targets.
4. Math Mission screen — hosted inside AlarmActivity (view swap), LinearProgressIndicator, no easy escape.
5. Stats — §4; score card, trend chart, snooze card.
6. Settings — §5; PreferenceFragmentCompat.

Quality bar: every ui-spec Quality Checklist item applies except those referencing excluded features.

## 4. Data Model (Room, v1 schema)

### Entity: `Alarm` (`alarms`)
```
id                  Long PK autoGenerate
hour, minute        Int
label               String ("" default)
enabled             Boolean
scheduleType        String  — "WEEKLY" | "ONCE"   (reserved: "SHIFT", "CALENDAR")
daysOfWeek          Int     — bitmask Mon=1<<0 … Sun=1<<6; 0 = one-shot
soundEnabled        Boolean
soundUri            String? — null = default alarm tone
vibrate             Boolean
snoozeMinutes       Int
missionType         String  — "NONE" | "MATH"     (reserved: "QR","STEPS","PHOTO")
missionDifficulty   Int     (1–3)
pausedFrom          Long?
pausedUntil         Long?
nextTriggerAt       Long?   — materialized next occurrence
createdAt           Long
```

### Entity: `AlarmEvent` (`alarm_events`)
```
id                  Long PK autoGenerate
alarmId             Long (indexed; soft ref — keep events after alarm deletion)
type                String — SCHEDULED | FIRED | FIRED_LATE | SNOOZED | DISMISSED | MISSED
scheduledFor        Long
occurredAt          Long
occurredElapsed     Long   — elapsedRealtime
snoozeCount         Int
timeToDismissMs     Long?
missionDurationMs   Long?
detail              String?
```

No third table: Wake Score, streaks, charts are query-time aggregations. Settings in SharedPreferences. exportSchema = true.

## 5. Milestone Plan (ordered; each gate must pass)

**M0 — Project skeleton & theme.** Gradle deps (Room + KSP, lifecycle/viewmodel, fragment-ktx, preference, splashscreen), Application class with DynamicColors, M3 Expressive theme + static seed palette (light/night), app icon + monochrome, edge-to-edge MainActivity with BottomNavigationView + 3 placeholder fragments + fade-through. *Done: themed 3-tab shell in light and dark.*

**M1 — Data layer.** Entities, DAOs (Flow-based, incl. next-enabled-alarm + event aggregations), database, repository, next-occurrence computation (bitmask weekly + one-shot → nextTriggerAt). *Done: compiles; logic unit-testable.*

**M2 — Alarm List + Add/Edit UI.** Collapsing app bar, RecyclerView (DiffUtil, stable IDs, clipToPadding=false), alarm card with animated container-color state, MaterialSwitch, swipe-to-delete + UNDO, FAB, empty state, live "Alarm in X" header, edit bottom sheet (MaterialTimePicker, day toggles, label, vibrate, snooze, mission row, delete), auto-save. *Done: full CRUD, matches ui-spec §1–2.*

**M3 — Scheduling engine.** AlarmScheduler (single setAlarmClock for earliest nextTriggerAt, re-derived on every DB change), permission gate + banner card, all five reschedule receivers, POST_NOTIFICATIONS flow. *Done: enabling alarm registers it; reboot re-registers.*

**M4 — Ring pipeline.** AlarmReceiver → FGS (systemExempted, MediaPlayer USAGE_ALARM, vibrator, volume ramp) → FSI notification with actions → AlarmActivity (locked-screen flags, huge clock, snooze/dismiss per spec). Snooze reschedules; dismiss stops FGS + computes next occurrence. Every transition writes an AlarmEvent (FIRED vs FIRED_LATE by receive delta; SNOOZED; DISMISSED with time-to-dismiss). *Done: end-to-end — set alarm 1 min out, rings full-screen, snooze + dismiss work, events in DB.*

**M5 — Math mission.** Mission view swap in AlarmActivity, difficulty-scaled problems, progress indicator, ringing continues until solved, missionDurationMs logged, notification dismiss suppressed during mission. *Done: math alarm can't be dismissed without solving.*

**M6 — Stats screen.** Aggregation queries → ViewModel, Wake Score formula (documented: variance vs. target + snooze penalty + time-to-dismiss, clamped 0–100), score card + stat row + streak, custom chart View themed via MaterialColors.getColor, ChipGroup ranges, explanation line, empty/1-point states. *Done: Stats renders correctly both themes (seed debug events if needed).*

**M7 — Settings + polish pass.** PreferenceFragment, haptics, contentDescriptions, 48/64dp audit, landscape sanity, run ui-spec Quality Checklist and fix violations. *Done: applicable checklist items pass.*

**M8 — Final gate.** Verify manifest (permissions, receivers, FGS type, exported flags). *Done: `gradlew assembleDebug` passes; APK demos M4 + M6 flows.*

## 6. Roadmap
- **v1.1:** pause-for-date-range UI, pre-flight check + OEM heuristics + permission checklist screen, Missed-Alarm Postmortem screen, Morning Report Card + widget, ringtone picker, QR/steps missions, CSV export/privacy, Play Console declarations.
- **v1.5:** calendar-aware alarm, shift patterns, photo mission, sunrise glow, snooze-coaching interventions, Wear tile, Pro one-time unlock (never gating mission types).
- **Deferred indefinitely:** Health Connect sleep estimate, smart-wake window, social/SMS accountability.

**Summary for the implementer:** Build M0–M8 in order, nothing else. Demo story: create an alarm in a Google-Clock-quality UI → it rings full-screen on a locked phone → solve a math problem to dismiss → open Stats and see your Wake Score. Every excluded feature has a reserved column or enum value — extend, never migrate-rewrite.
