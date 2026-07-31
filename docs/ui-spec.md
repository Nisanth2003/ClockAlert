# AlarmTracker — UI Spec (industry-level, XML Views + Material Components)

## Design Language

### Baseline: Material 3, with M3 Expressive accents
- The 2026 bar is Material 3 Expressive (Google Clock v8.1, Aug 2025 redesign: full-background highlight on active alarm rows, bottom-sheet editing, very tall time fonts, containerized grouping, larger touch targets).
- MDC-Android 1.14.0 (already in this project) adds `Theme.Material3Expressive.*` themes for the classic View system. Base theme: `Theme.Material3Expressive.DayNight.NoActionBar` (fallback `Theme.Material3.DayNight.NoActionBar`). Works with minSdk 28.

### Dynamic color
- Call `DynamicColors.applyToActivitiesIfAvailable(app)` in `Application.onCreate()`. Android 12+ tints to wallpaper; API 28–30 falls back to static M3 palette. Define a full static M3 color set (seed-generated) in `values/colors.xml` + `values-night/`.
- Use roles, not raw colors: `?attr/colorPrimary`, `colorPrimaryContainer`, `colorSecondaryContainer`, `colorSurface`, `colorSurfaceContainerHigh/Low`, `colorOnSurfaceVariant`. Active alarm card = `colorPrimaryContainer`; inactive = `colorSurfaceContainerLow`.

### Typography
- M3 type scale via `?attr/textAppearance*`:
  - Alarm time in list: `textAppearanceDisplaySmall` (36sp) or `DisplayMedium` (45sp).
  - Screen titles: `textAppearanceHeadlineMedium`/`Large` (collapsing toolbar).
  - Alarm label / days summary: `textAppearanceBodyMedium` in `colorOnSurfaceVariant`.
  - Stat numbers: `textAppearanceDisplaySmall`; stat captions `labelMedium`.
- `android:fontFeatureSettings="tnum"` (tabular numerals) on time text; AM/PM in smaller `titleMedium` span.

### Shape & motion
- Cards 12–16dp corner radius (expressive up to 28dp on large containers); bottom sheets 28dp top corners; pill buttons. FAB: large square-ish rounded, docked bottom-end.
- `MaterialSharedAxis`/`MaterialFadeThrough` between bottom-nav destinations; `TransitionManager` for card state changes.

### Navigation
- `BottomNavigationView` (Widget.Material3.BottomNavigation), 3 destinations: **Alarms, Stats, Settings**. Chips (not tabs) inside Stats for time-range switching.

### Dark theme & edge-to-edge
- `DayNight` mandatory; verify all surfaces use tonal `colorSurfaceContainer*` roles. Ringing screen readable at min brightness.
- Edge-to-edge enforced at targetSdk 36+: `enableEdgeToEdge()`, insets via `ViewCompat.setOnApplyWindowInsetsListener` — RecyclerView bottom padding (`clipToPadding=false`), app bar top padding, FAB margin above nav bar. Transparent system bars.

## Screen-by-Screen Spec

### 1. Alarm List (home)
- `CoordinatorLayout` → `AppBarLayout` + `CollapsingToolbarLayout` (large title "Alarms") → `RecyclerView` of alarm cards → large `FloatingActionButton` (bottom-end) → `BottomNavigationView`.
- **Alarm card** (`MaterialCardView`):
  - Row 1: time (`DisplaySmall`, tabular nums) + AM/PM + `MaterialSwitch` aligned end.
  - Row 2: label + repeat summary ("Mon, Tue, Wed" / "Tomorrow" / "Every day") in `bodyMedium`, `colorOnSurfaceVariant`.
  - Enabled: `colorPrimaryContainer` / `colorOnPrimaryContainer`. Disabled: `colorSurfaceContainerLow` / `colorOnSurfaceVariant`. Animate change.
  - Tap card → edit bottom sheet. Swipe-to-delete with `ItemTouchHelper` + Snackbar UNDO.
- **"Next alarm in…" header** under the title ("Alarm in 7 h 32 min"), live-updating.
- **Empty state**: centered outlined alarm icon (96dp, ~38% emphasis), "No alarms" (`titleLarge`), "Tap + to add your first alarm" body.

### 2. Add/Edit Alarm (`BottomSheetDialogFragment`)
- 28dp top corners, `BottomSheetDragHandleView`, expanded by default.
- Contents: 1) large tappable time (`DisplayLarge`) → `MaterialTimePicker` (honor `DateFormat.is24HourFormat`, clock + keyboard modes); 2) day-of-week: 7 circular checkable `MaterialButton`s in `MaterialButtonToggleGroup` (multi-select, `selectionRequired=false`) or `ChipGroup`; 3) rows (56dp min): Label, Sound, Vibrate (`MaterialSwitch`), Snooze duration, Mission; 4) Delete (text button, `colorError`) when editing; auto-save on dismiss + Snackbar.

### 3. Alarm Ringing (full-screen activity)
- Full-screen intent notification; `setShowWhenLocked`/`setTurnScreenOn`. Edge-to-edge, `colorSurfaceContainerLowest`.
- Center: current time (`DisplayLarge`, 96sp+), label below.
- Snooze and Dismiss visually + spatially very distinct: large filled tonal "Snooze" center-lower, swipe-to-dismiss slider or oversized dismiss button far from snooze at bottom. Min 64dp targets.
- Missions (if enabled): Dismiss routes to mission screen (math etc.) with `LinearProgressIndicator`, no easy escape.

### 4. Stats / Tracking
- `NestedScrollView` of cards, time-range selector at top: single-selection `ChipGroup` (Week / Month / 3 Months).
- Card stack: 1) summary/score card — hero number (`DisplaySmall`) + supporting stats row (avg snoozes, on-time %, streak); 2) wake-up time trend chart (custom View or MPAndroidChart, themed colors only); 3) snooze stats card (bar chart + "most snoozed alarm" callout); 4) weekday pattern (v2).
- Empty state: "No data yet — stats appear after your first alarm rings."

### 5. Settings
- `PreferenceFragmentCompat` M3-themed: time format, default snooze length, gradual volume increase, dismiss style, theme (System/Light/Dark), dynamic color toggle.

## Component Choices

| Need | Component |
|---|---|
| App theme | `Theme.Material3Expressive.DayNight.NoActionBar` (MDC 1.14.0) |
| Alarm card | `MaterialCardView`, 16dp radius, 8dp vertical gap |
| Toggle | `MaterialSwitch` (com.google.android.material.materialswitch — NOT legacy SwitchMaterial) |
| Time input | `MaterialTimePicker` dialog |
| Day-of-week | `MaterialButtonToggleGroup` w/ circular checkable `MaterialButton`s |
| Add alarm | Large `FloatingActionButton` bottom-end |
| Nav | `BottomNavigationView` (3 items), `MaterialFadeThrough` |
| Edit surface | `BottomSheetDialogFragment` + `BottomSheetDragHandleView` |
| App bar | `AppBarLayout` + `CollapsingToolbarLayout` (Large) |
| Stats range | Single-selection `ChipGroup` |
| Charts | Custom View or MPAndroidChart, themed via `MaterialColors.getColor` |
| Confirmations | `Snackbar` w/ UNDO; `MaterialAlertDialogBuilder` |
| Empty states | icon + `titleLarge` + `bodyMedium`, visibility swap |

## Quality Checklist (for review agent)

**Theming & color**
1. No hardcoded colors — every surface/text uses `?attr/color*` roles.
2. Dynamic color on 12+; static seed palette fallback correct on 28–30, light + dark.
3. Dark theme fully specified (`values-night`), tonal surfaces, night readability.
4. Active vs inactive alarm distinguishable by container color AND switch state AND text emphasis (not color alone).

**Layout & structure**
5. Edge-to-edge correct: transparent bars, insets on app bar / list bottom / FAB; nothing overlaps gesture nav; works with 3-button nav + cutout.
6. Alarm time uses Display-scale type with tabular figures; hierarchy matches M3 scale.
7. RecyclerView: `clipToPadding=false`, stable IDs, DiffUtil, no jank on toggle.
8. FAB doesn't cover last item's switch.
9. Landscape/large-screen sanity (constrain content width).

**Interaction & motion**
10. Card state change animated; bottom sheet standard M3 motion; fade-through between destinations.
11. Swipe-to-delete has UNDO; destructive actions never one-tap-final.
12. Time picker honors 12/24h + keyboard input mode.
13. Ringing screen: snooze/dismiss cannot be confused or hit accidentally; works locked; huge type.

**States**
14. Every screen has a designed empty state.
15. "Next alarm in X" present and live-updating.
16. Charts themed, labeled axes, handle 0/1 data points gracefully.

**Accessibility**
17. Touch targets ≥ 48dp; ringing screen ≥ 64dp.
18. contentDescription on switches/FAB/icons; alarm card announces as single focusable row.
19. Contrast ≥ 4.5:1 both themes; survives 200% font scale (autoSizeTextType / minHeight, no fixed heights).
20. State never by color alone.

**Polish signals**
21. Consistent 4/8dp spacing grid; consistent corner radii.
22. App icon, SplashScreen compat, themed monochrome icon.
23. No default-AppCompat leftovers (old switches, default purple colorPrimary, framework dialogs).
24. Haptics on switch toggle and alarm dismiss.
25. Alarm notification with actions, M3 icon, full-screen intent; missing SCHEDULE_EXACT_ALARM permission surfaced as in-list warning banner card, not silent failure.
