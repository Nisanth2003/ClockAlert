# AlarmTracker — Market Research (Android alarm / alarm-tracking apps, mid-2026)

## Landscape

**Google Clock (pre-installed, free)**
- Does well: clean UX, reliable-ish basics, Spotify/YT Music alarm sounds, "pause alarm for dates" feature users love, gradual volume ramp.
- Complaints: a recurring, well-documented reliability crisis. Through 2025 Pixel users flooded Reddit with "Missed alarm: Alarm did not fire due to an unknown reason" notifications — people missed work and flights, and Google reportedly couldn't reproduce it. Zero statistics/insights; no wake-up verification; no anti-snooze tools. This reliability failure is the single loudest pain point in the whole category.

**Samsung Clock (pre-installed, free)**
- Does well: custom snooze durations, Bedtime-mode integration, Sleep mode scheduling.
- Complaints: alarms turning themselves off randomly at night without notification; users migrating from Google Clock miss features like "pause alarm" for date ranges; limited customization.

**Alarmy (Delight Room) — category leader for "actually wake up"**
- Does well: mission-based dismissal (math, photo of a location, barcode scan, shake, squats, memory games); repeatedly voted the "most effective/most annoying" alarm; now bundles sleep tracking, snore detection, sleep sounds, and a beta "AI-powered deep analysis".
- Complaints: aggressive subscription shift (~3x old one-time price); core missions (barcode, steps, photo) moved behind the paywall; "an alarm app shouldn't require a subscription" is a common refrain. Perceived bloat.

**Sleep Cycle**
- Does well: sound-based sleep-cycle tracking, smart wake window, sleep score, tracks wake-time variability / "social jetlag."
- Complaints: previously free features silently moved behind premium; billing disputes; accuracy skepticism — phone-only trackers hit only ~50-70% stage-classification accuracy; constant upgrade nags; "unreliable alarms".

**Sleep as Android (Urbandroid)**
- Does well: power-user's choice — smart wake window, sonar contactless tracking, huge wearable support, anti-snooze CAPTCHA (QR code in another room, math, step count), great support.
- Complaints: monetization changes (ads + subscription pressure) angered long-term users; broken/unstable wearable tracking; UI "not very intuitive"; statistics shown without explanations; "lumbering and inflated."

**Newer / niche players**
- **RiseDaily — Streak Alarm**: daily wake streaks + "morning analytics" (wake times, snooze counts, trends). Pro is $7.99/year.
- **Nooze / SnoozeProof**: gamified no-snooze alarms with wake-streak milestones — iOS-first.
- **Galarm**: social alarms — friends notified if you miss your alarm. Mixed reviews (delayed alarms).
- **Fossify Clock / AMdroid / NFC Alarm Clock**: privacy/FOSS crowd — lightweight, ad-light, subscription-free.
- **Shift-worker tools**: shift-linked alarms exist but live in clunky calendar apps.

## Gaps

1. **Reliability as a feature.** #1 complaint across Google/Samsung Clock and Galarm is alarms silently not firing (Doze, OEM battery killers, SCHEDULE_EXACT_ALARM friction). No mainstream app markets *verified reliability* — self-tests, pre-sleep "alarm health checks," missed-alarm forensics.
2. **Nobody owns "wake-up analytics."** Sleep apps track *sleep* (dubious accuracy); alarm apps barely track anything. Data an alarm app can measure exactly — actual dismiss time, snooze count, time-to-dismiss, wake-time variance — is objective, private, cheap. Sleep regularity predicts longevity better than duration (WHOOP) — consistency scoring is proven demand with no alarm-native implementation.
3. **Subscription fatigue.** Every major player is bleeding goodwill moving features behind subscriptions. Fair one-time-purchase is itself a differentiator.
4. **Bloat.** Gap: powerful but *focused*.
5. **Honest tracking vs. pseudo-science.** Users distrust accelerometer "sleep stages" (50-70% accuracy). Nobody sells honest, behavior-based metrics.
6. **Schedule-aware alarms.** No polished alarm app does "wake me 90 min before my first calendar event, floor 6:30."
7. **Snooze behavior change.** Anti-snooze = punishment missions only. No app treats snoozing as a habit to be measured, visualized, coached down.
8. **Pause/vacation handling.** Date-range alarm pausing / holiday-aware alarms are a repeated ask.
9. **Privacy.** On-device, no-account options in demand.

## Proposed Features

1. **Alarm Reliability Guard** — Watchdog subsystem: setAlarmClock() + foreground fallback, detects OEM battery-killer settings, optional nightly "pre-flight check" notification ("Your 6:30 alarm is armed, volume OK, permission granted"), forensic logging if an alarm fires late. Gap #1. Differentiation: **High**.
2. **Wake Score & Sleep Consistency Index** — Daily/weekly 0-100 score from behavioral signals measured exactly: wake-time variance vs. target, snooze count, time-to-dismiss, weekend drift. Plain-language explanations for every stat. Gaps #2, #5. Differentiation: **High** — the app's namesake.
3. **Snooze Analytics & Coaching** — Track every snooze: count, total snoozed minutes per week, trend charts, progressive interventions (shrinking snooze duration, snooze budget, streak rewards for zero-snooze mornings). Gap #7. Differentiation: **High**.
4. **Calendar-Aware Smart Alarm** — Reads local calendar, sets/adjusts alarm relative to first event (user-defined floor + prep buffer), holiday/vacation auto-skip, "pause for date range." Gaps #6, #8. Differentiation: **Medium-High**.
5. **Shift Pattern Alarms** — First-class rotating-shift schedules. Gap #6. Differentiation: **Medium**.
6. **Wake-Up Verification Missions (fair-free tier)** — Math, QR/barcode, steps, photo with basics *free* (counters Alarmy's paywall). Mission completion time feeds Wake Score. Gap #3. Differentiation: **Low-Medium** as feature, **Medium** as pricing wedge.
7. **Morning Report Card & Streaks** — Single post-dismissal screen: today's wake time vs. 30-day average, streak count, consistency trend sparkline, one actionable insight. Widget + Wear OS tile. Gap #2. Differentiation: **Medium-High**.
8. **Honest Sleep Estimate (no pseudo-science)** — Infer sleep window from phone-lock time / last-use + alarm dismissal, clearly labeled as estimate. Optional Health Connect read. Gap #5. Differentiation: **Medium**.
9. **Accountability Alarms (lightweight social)** — Optional: missed/over-snoozed alarm notifies a chosen contact; weekly consistency leaderboard. Differentiation: **Medium**.
10. **Local-First Privacy Mode** — All tracking on-device by default, no account, CSV export. Gap #9. Differentiation: **Medium**.
11. **Gentle Wake Stack** — Gradual volume ramp + screen sunrise glow + optional smart-wake window (only with wearable data via Health Connect). Differentiation: **Low** (parity).
12. **Missed-Alarm Postmortem** — Clear diagnosis and one-tap fix when anything goes wrong (alarm delayed, phone off, DND ate it). Gap #1. Differentiation: **High**, cheap on top of feature 1.

## Positioning Notes

- **Positioning:** "The alarm that never fails — and proves you're actually waking up better." Pillars: *verified reliability* + *behavioral wake analytics*. The name AlarmTracker maps onto pillar two.
- **Monetization:** free tier with full alarm reliability + basic missions + 7-day stats; one-time "Pro" unlock or cheap annual (~$8-12/yr) for full analytics history, calendar/shift alarms, social. Never paywall a mission type users already had.
- **Technical moat:** handling SCHEDULE_EXACT_ALARM (API 31+/33 default-deny), Doze (setAlarmClock() exempt), OEM battery killers visibly well is defensible.
- **Trend:** drift from passive tracking to "participation" (missions, gamification, AI insights). Behavioral analytics + coaching rides that trend without dubious sensor claims.
