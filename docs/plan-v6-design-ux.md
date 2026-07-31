# AlarmTracker v6 — Design, Animation, Functionality & Feature Research + Roadmap

_Date: 2026-07-24. Positioning unchanged: "The alarm that never fails — and proves you're
actually waking up better." Pillars: verified reliability + behavioral wake analytics._

This document is the research + prioritized plan requested alongside the editor
Save/Cancel/Delete fix (shipped 2026-07-24, build green — see `testing-session` memory).

---

## 0. What shipped this turn (basic-functionality gap closed)

The alarm editor (`AlarmEditSheet`) previously **auto-saved silently in `onDismiss()`** with no
Save button, no Cancel button, and a Delete button buried at the bottom of a long scroll (below
the 55% peek fold — effectively invisible). Fixed:

- **Pinned header action bar**: `✕ Cancel · [title] · 🗑 Delete (edit only) · Save`, always visible.
- **Explicit commit**: Save is the only commit path. Cancel / back / swipe discards, prompting
  "Discard changes?" only when there are unsaved edits (field-signature dirty check).
- **Delete** now prominent (header trash) with a confirm dialog → existing undo-snackbar flow.

---

## 1. Design (visual system)

**Current:** Material 3 **Expressive** DayNight theme, static seed `#445E91` palette + optional
dynamic color, 24dp corner radius, `colorSurfaceContainerHigh` cards, `displayMedium` list time,
bottom nav (Alarms/Stats/Settings), stated "calm-night / warm-morning" direction (partly realized
only in the sunrise-glow overlay).

**Opportunities (highest leverage first):**

1. **Time-of-day adaptive accent.** Tie a subtle background wash / accent to each alarm's time
   (cool indigo for night, warm amber for dawn/morning). This is the app's own positioning and is
   currently only expressed on the ring glow. Apply to the list hero and alarm cards.
2. **Alarm card redesign.** Repeat days as a compact pill row (not a text subtitle), a leading
   time-of-day glyph, inline "next in Xh", and clearly **dim disabled alarms** (alpha/again on
   `colorSurfaceContainerLow`) so enabled vs off reads instantly.
3. **Expressive shape + type.** The theme is `Material3Expressive` but the app doesn't use
   expressive shape morphing, spring motion, or emphasized type. Adopt `MaterialShapes` / shape
   morph on the FAB and the day/schedule toggles; use emphasized display type for the time hero.
4. **Editor density.** Even with the new header, the sheet is very long. Apply progressive
   disclosure (backlog item 7): essentials (time, repeat, label, sound) visible; Missions / Gentle
   wake / Coaching / Pause / Tracking in collapsible sections.
5. **Warm empty & first-run states.** Illustrative empty state; Stats teaser copy ("Wake Score
   appears after your first wake-up").

## 2. Animation / motion

**Current (good baseline):** ring fade+rise, breathing-clock pulse, swipe-hint bob (all guarded by
`ANIMATOR_DURATION_SCALE==0` reduced-motion check); `MaterialFadeThrough` between fragments.

**Opportunities:**

1. **Swipe-to-delete affordance.** Swipe currently removes a row with no visual — add a red
   `colorErrorContainer` background + trash icon revealed under the swiped card (`ItemTouchHelper.
   onChildDraw`). Undo snackbar already exists.
2. **FAB → editor transition.** Shared-axis / container transform from the FAB into the sheet for a
   premium open. (Bottom-sheet container transform is limited; shared-axis Z is the safe choice.)
3. **Toggle micro-interactions.** Spring on enable/disable, subtle card scale/elevation; haptics
   already present on some switches — extend consistently.
4. **Real animated sunrise gradient.** The glow overlay swaps a solid color; animate a
   `GradientDrawable` dark→warm for an actual dawn. High emotional payoff on the ring screen.
5. **Save success micro-interaction.** Brief check animation on Save (optional; snackbar covers it).
6. **Keep the reduced-motion discipline** already established — every new animator must honor it.

## 3. Functionality (completeness of the basics)

| Gap | Notes | Effort |
|---|---|---|
| **Save/Cancel/Delete** | ✅ done 2026-07-24 | — |
| **"No snooze" option** | `snooze_values` has no 0/off; needs ring-path handling (hide snooze buttons when 0). Flagged as the one genuinely-standard gap. | S |
| **Duplicate alarm** | Standard in every alarm app; missing. Overflow/long-press → duplicate. | S |
| **Skip next occurrence** | Skip tomorrow without deleting a repeating alarm. | M |
| **Preview / Test alarm** | Backlog HIGH — lets users trust it will ring before relying on it. | M |
| **Bulk select** | Multi-select enable/disable/delete. | M |
| **Bedtime schedule** | Pairs with the wake-analytics pillar; bedtime reminder + wind-down. | M |

## 4. Features (bigger bets — from the backlog, standing instruction to fold in)

1. **Google Calendar OAuth login → calendar-driven alarms** (backlog #1). PKCE public-client OAuth;
   alarm references an event, auto-moves when the event moves, cancels when the event is cancelled.
   Optional login; app stays fully usable without it. Decide: extend existing device-calendar
   (`CalendarAlarm`) vs true Google account API.
2. **Connector framework polish** (v5 shipped Jira). Deferred: alarm-editor "From a connected
   service" mode; Google Calendar / Gmail connectors; `EncryptedSharedPreferences` for tokens (now
   plaintext); real Play Billing (Pro currently dev-unlock).
3. **Contextual ring action button** (backlog #6). Now unblocked — the ring path is device-verified.
   e.g. Jira alarm → "Open issue", delivery → "Track order". Stored in `EventTrigger.configJson`.
4. **Subscription / Pro tiering** (backlog #4): Free = core + notification triggers + stats;
   Pro = connectors + advanced analytics. Price/trial TBD.

## 5. Still needs REAL-HARDWARE verification (carried from testing-session)

- Geofence-crossing fires; live notification match fires/refines; delivery/arrival presets;
  cooldown/limit-reset (never even emulator-verified); full event create→fire cycle.

---

## Proposed sequencing

- **Phase 0 (done):** Editor Save/Cancel/Delete.
- **Phase 1 — quick functional wins (low risk, high daily-use value):** swipe-to-delete visual,
  "No snooze", duplicate alarm, skip-next, editor progressive disclosure, FAB→editor transition.
- **Phase 2 — design & motion polish:** time-of-day adaptive accent, alarm-card redesign, animated
  sunrise gradient, expressive shape/motion.
- **Phase 3 — feature bets:** Preview/Test alarm, bedtime, Google Calendar OAuth, connector editor
  integration + Calendar/Gmail + EncryptedSharedPreferences + Play Billing, contextual ring button.
- **Phase 4 — hardware verification** of all event-firing paths.

_Recommendation: start with Phase 1 — it directly answers "missing basic functionality" and is
device-verifiable quickly, before investing in the deeper visual/feature work._
