# AlarmTracker — v2 Full-Feature Scope (supersedes v1 trim)

The v1 decision (decision-v1.md) deliberately shipped a minimal subset and deferred most of the market-gap agent's 12 proposed features. **User directive: build them all.** This document expands the scope to the full feasible feature set, built on the v1 foundation (which passed 25/25 UI review). The Room schema was designed additive, so this is extend-not-rewrite.

## What ships in v2 (everything feasibility approved)

### From v1.1 bucket
1. **Pause-for-date-range** — schema already has `pausedFrom/pausedUntil`; add edit-sheet UI + scheduler honoring the pause window + list-card "paused until X" state.
2. **Ringtone picker** — `soundUri` reserved; RingtoneManager `ACTION_RINGTONE_PICKER` (TYPE_ALARM) from the edit sheet; service plays the chosen URI.
3. **QR/barcode mission** — CameraX + on-device ML Kit barcode scanning; register a "dismiss barcode" then require the same scan to dismiss.
4. **Steps mission** — `ACTIVITY_RECOGNITION` (29+) + `TYPE_STEP_DETECTOR`; count N steps to dismiss; graceful fallback if no sensor.
5. **Missed-Alarm Postmortem screen** — diagnosis over the forensic event log (phone off via boot-time > alarm-time, exact-alarm perm revoked, notifications off, FSI off, alarm volume 0, no battery exemption, fired-late delta, DND); one-tap fixes deep-linking to the checklist. Phrase OEM kills as "likely cause," never definitive.
6. **Morning Report Card** — post-dismiss screen: today's wake time vs 30-day avg, streak, consistency sparkline, one insight; shareable.
7. **Home-screen widget** — `AppWidgetProvider`/RemoteViews: next alarm + current streak/score; event-driven updates.
8. **Pre-flight alarm health check** — nightly WorkManager job: verify volume, permissions, next-alarm registration; post a reassuring/warning notification.
9. **OEM battery-killer heuristics** — `Build.MANUFACTURER` + `isIgnoringBatteryOptimizations()` + per-OEM settings deep-links; a permission/health checklist screen (reused by postmortem). Use settings deep-link, NOT the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog (Play-restricted).
10. **CSV export / privacy screen / data wipe** — SAF `CREATE_DOCUMENT` export of alarms+events; a privacy explainer; a destructive "wipe all data" with confirm.

### From v1.5 bucket
11. **Calendar-aware smart alarm** — `READ_CALENDAR`; set alarm relative to first event (user floor + prep buffer); recompute via ContentObserver + evening pre-flight recompute + fixed commit time; holiday auto-skip via device holiday calendar. Handle all-day/declined/multi-calendar edge cases.
12. **Shift-pattern alarms** — new `scheduleType="SHIFT"`; define a rotating pattern (e.g. 4-on-4-off) with an anchor date; alarms follow it; pattern editor + preview. Uses the reserved "rule → materialized nextTriggerAt" model.
13. **Photo mission** — capture a reference photo at setup; require a perceptual-hash-similar photo to dismiss (on-device, no cloud ML).
14. **Sunrise glow (honest gentle wake)** — schedule a silent pre-alarm N minutes early; ring activity animates a screen brightness/color sunrise. (The volume ramp already exists.)
15. **Snooze-coaching interventions** — opt-in: progressively shrinking snooze duration, a weekly snooze budget, zero-snooze streak rewards; surfaced in Stats.
16. **Honest sleep estimate** — zero-permission own-signal version: infer sleep opportunity from last app interaction / screen-off proxy + dismissal, clearly labeled an ESTIMATE (no fake sleep stages).
17. **Manual share-sheet** — share morning report / streak via ACTION_SEND.

## Explicitly NOT built (feasibility-rejected — do not implement as proposed)
- **Health Connect real-time smart-wake window** — HC exposes only historical sleep sessions, not real-time stages; "wake me in light sleep now" cannot be done truthfully and clashes with the no-pseudo-science positioning. The honest gentle-wake (#14) is the substitute.
- **Automatic SMS/contact accountability alarms** — `SEND_SMS` is effectively Play-banned for non-default-SMS apps; an SMS intent needs the sleeping user to tap Send. Replaced by the manual share-sheet (#17). No leaderboard (needs a backend; out of scope).
- **Wear OS tile** — separate module/build/Play listing; out of scope for this pass.
- **Monetization / Pro gating** — no paywall; nothing gated.

## Build approach — three sequential phases (shared files → no parallel edits), each ending green
- **Phase A (scheduling/model):** pause, shift patterns, calendar-aware, ringtone picker. Touches Alarm entity, AlarmTimes, AlarmScheduler, AlarmEditSheet, RescheduleReceiver, new calendar util.
- **Phase B (ring/missions):** QR, steps, photo missions; snooze coaching; sunrise glow + pre-alarm. Touches AlarmRingService, AlarmActivity, mission views, edit-sheet mission options, new deps (CameraX, ML Kit barcode).
- **Phase C (analytics/reliability/data):** postmortem screen, morning report card, widget, pre-flight worker, OEM heuristics + checklist screen, honest sleep estimate, CSV export/privacy/wipe, share-sheet.
- **Then:** re-run the UI review/rework loop over the whole expanded app against the 25-item checklist in ui-spec.md.

Each phase: keep v1's quality bar (theme attrs only, values-night complete, edge-to-edge insets, ≥48dp targets, contentDescriptions, no hardcoded strings), migrate the Room schema properly (additive columns/new tables + Migration or version bump with fallback), and finish with `./gradlew assembleDebug` green. Any Room schema change must ship a Migration (do not rely on destructive fallback for existing installs).
