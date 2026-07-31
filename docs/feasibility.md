# AlarmTracker — Technical Feasibility Assessment (July 2026, minSdk 28 / targetSdk 37)

## Core Infrastructure (required for any v1)

Budget ~L (3-5 weeks) before any differentiating feature works reliably.

1. **Alarm Scheduling Engine** (`AlarmManager`)
   - Primary API: **`setAlarmClock()`** — Doze/App Standby exempt, exact, shows status-bar alarm icon.
   - Declare **`USE_EXACT_ALARM`** (API 33+, auto-granted, Play-policy restricted to alarm/clock/calendar core-function apps — AlarmTracker qualifies). Also `SCHEDULE_EXACT_ALARM` with `maxSdkVersion="32"`. Gate with `canScheduleExactAlarms()`, deep-link to `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` fallback. Play Console exact-alarm declaration required.
   - One "next alarm" registered at a time; re-derive from DB on every change.

2. **Ring Pipeline** (BroadcastReceiver → Foreground Service → Full-screen Activity)
   - `AlarmReceiver` (manifest) → foreground service type **`systemExempted`** (allowed because app holds exact-alarm permission; API 34+ requires declared FGS type). FGS owns audio + vibration.
   - Audio: **`AudioAttributes.USAGE_ALARM`** — exempt path under Android 17 background-audio hardening; respects alarm volume stream, bypasses DND mostly.
   - **Full-screen intent notification** (`USE_FULL_SCREEN_INTENT`): API 34+ Play auto-grants only to calling/alarm apps — qualifies, but Play Console FSI declaration required. Check `NotificationManager.canUseFullScreenIntent()`, offer `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` deep-link.
   - `AlarmActivity` with `setShowWhenLocked(true)` / `setTurnScreenOn(true)`.
   - `POST_NOTIFICATIONS` runtime request on 33+ with rationale screen.

3. **Persistence** — Room DB: `alarms` (definition, schedule rules, enabled) + `alarm_events` (SCHEDULED / FIRED / SNOOZED / DISMISSED / MISSED / FIRED_LATE, wall-clock + elapsed timestamps, time-to-dismiss). Event log is the substrate for features 1, 2, 3, 7, 12 — design it first.

4. **Reschedule Receivers** — `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (31+).

5. **WorkManager** — nightly pre-flight checks, analytics rollups, calendar recompute. Never fires the alarm itself.

6. **Permission/health onboarding UI** — reusable checklist screen (notifications, exact alarm, FSI, battery optimization, volume); doubles as surface for features 1 and 12.

## Feature Assessments

1. **Alarm Reliability Guard** — **Yes-with-caveats. Effort L.** OEM battery-killer detection is heuristic only (Build.MANUFACTURER + isIgnoringBatteryOptimizations + per-OEM settings deep-links; warn, not verify). No permanent FGS watchdog (policy problem) — fallback = setAlarmClock() + setExactAndAllowWhileIdle() sentinel + FGS at ring time. Use settings deep-link, not REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog. Pre-flight check easy (evening WorkManager job: volume, permissions, next-alarm registration → notification). Forensic logging (expected vs actual fire time) straightforward, genuinely differentiating. Deps: entire core stack.

2. **Wake Score & Consistency Index** — **Yes, cleanly. Effort M.** Pure on-device math. Product risk: fair-feeling formula + plain-language explanations + cold-start design. Deps: event log (capture dismiss timestamps/snooze counts/target wake times from day one), chart component.

3. **Snooze Analytics & Coaching** — **Yes. Effort M.** Pure app logic in the ring pipeline. Interventions opt-in. Shares 80% of substrate with feature 2.

4. **Calendar-Aware Smart Alarm** — **Yes-with-caveats. Effort L.** READ_CALENDAR is runtime-dangerous but no special Play form. Hard part is recompute correctness (ContentObserver + evening recompute + fixed commit time; edge cases: all-day/declined events, multiple calendars). Holiday auto-skip: device's holiday calendar or bundled dataset. **"Pause for date range" is trivial and most-requested — ship it standalone.**

5. **Shift Pattern Alarms** — **Yes. Effort M.** No platform risk; rotating-pattern model + editor UI is fiddly UX. Design schedule model as "rule → materialized next occurrence" from the start.

6. **Wake-Up Missions** — **Yes-with-caveats. Effort L total.** Math: S. QR/barcode: M (CameraX + on-device ML Kit). Steps: M (ACTIVITY_RECOGNITION + TYPE_STEP_DETECTOR, flaky on some OEMs). Photo: M-L (perceptual hash). FGS keeps ringing until mission completes; can't prevent force-stop (log as forensic event). No dismiss action on notification during mission.

7. **Morning Report Card & Streaks** — **Yes-with-caveats. Effort M** for post-dismiss screen + home widget (AppWidgetProvider/RemoteViews, event-driven updates). **Wear OS tile is a separate module — L; defer.**

8. **Honest Sleep Estimate** — **Yes-with-caveats.** Phone-lock signal needs PACKAGE_USAGE_STATS (high-friction special access, Play scrutiny) — start with zero-permission own-signal version instead. Health Connect READ_SLEEP requires Play health-apps declaration + privacy policy + approval — later opt-in module, not v1. Effort M (own-signal) / L (full).

9. **Accountability Alarms (social)** — **Mostly NO.** SEND_SMS effectively banned for non-default-SMS apps; SMS intent requires the sleeping user to tap Send; leaderboard needs backend (out of scope). Only implementable slice: manual share-sheet of morning report/streak (S, zero risk).

10. **Local-First Privacy Mode** — **Yes. Effort S.** CSV export via SAF CREATE_DOCUMENT (no storage permissions), privacy screen, data-wipe. Makes Data Safety form trivial. Loud marketing, cheap engineering.

11. **Gentle Wake Stack** — Split: volume ramp **S** (animate player volume, table stakes). Sunrise glow **S-M** (pre-alarm silent alarm N min early + brightness/color animation). **Smart-wake via Health Connect: NOT implementable as advertised** — HC gives historical sleep sessions, not real-time stages. Cut it or bias wake time from historical patterns only.

12. **Missed-Alarm Postmortem** — **Yes. Effort M.** Cheapest high-differentiation item if feature 1's forensics exist. Reliably detectable: phone off (boot time > alarm time), exact-alarm permission revoked, notifications disabled, FSI revoked, alarm volume 0, no battery exemption, fired late (receive delta), DND total silence. Can't prove OEM kill — phrase as "likely cause."

## Overall Recommendation

**Safe bets for v1** (coherent, low-policy-risk, all on one Room event log):
- Core infrastructure (non-negotiable, ~L)
- 1. Reliability Guard — the moat; largely IS the core infra done properly
- 12. Missed-Alarm Postmortem — cheap on top of 1, high differentiation
- 2. Wake Score + 3. Snooze Analytics — the namesake, zero platform risk, shared substrate
- 7. Morning Report Card + widget (defer Wear OS tile)
- 10. Local-First Privacy + CSV export — nearly free
- 6. Math mission only (S); QR/steps/photo in v1.1
- "Pause for date range" slice of feature 4 (trivial, loudly requested)

**Defer to v1.5:** full calendar-aware alarms (4), shift patterns (5), remaining missions (6), gentle-wake ramp+glow (11; ramp can sneak into v1).

**Defer indefinitely / restructure:** 8 (zero-permission version later; Health Connect after Play health approval), 11's smart-wake (not honestly implementable — clashes with no-pseudo-science positioning), 9 (keep only manual share-sheet).

**Play Console paperwork:** exact-alarm declaration, FSI declaration, Data Safety form; health-apps declaration later if HC ships.
