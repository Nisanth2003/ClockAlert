package com.example.alarmtracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alarms")
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val enabled: Boolean = true,
    /** "WEEKLY" | "ONCE" | "SHIFT" | "CALENDAR" | "EVENT" */
    val scheduleType: String = SCHEDULE_WEEKLY,
    /**
     * Bitmask Mon=1&lt;&lt;0 … Sun=1&lt;&lt;6; 0 = one-shot.
     * For WEEKLY: days the alarm repeats. For CALENDAR: days to consider
     * (0 = every day). Ignored for ONCE and SHIFT.
     */
    val daysOfWeek: Int = 0,
    val soundEnabled: Boolean = true,
    /** null = default alarm tone; otherwise a RingtoneManager content URI. */
    val soundUri: String? = null,
    /**
     * Off by default: a new alarm rings but doesn't buzz until the user asks for it.
     * Presented to the user as one three-way [vibrationMode] rather than a second switch that
     * could contradict [soundEnabled].
     */
    val vibrate: Boolean = false,
    val snoozeMinutes: Int = 10,
    /** "NONE" | "MATH" | "QR" | "STEPS" | "PHOTO" */
    val missionType: String = MISSION_NONE,
    /** 1–3. For MATH: problem difficulty. For STEPS: scales the step goal. */
    val missionDifficulty: Int = 1,
    /** QR mission: raw value of the barcode/QR that must be re-scanned to dismiss. */
    val missionBarcode: String? = null,
    /** PHOTO mission: perceptual (dHash) hex of the reference photo. The image itself is never stored. */
    val missionPhotoHash: String? = null,
    /** Sunrise glow: minutes before the real alarm to start a silent screen sunrise (0 = off). */
    val gentleWakeMinutes: Int = 0,
    /** Opt-in snooze coaching: each snooze in a session gets progressively shorter. */
    val snoozeCoaching: Boolean = false,
    /** Pause-for-date-range window (epoch millis); while now is inside it the
     * alarm is skipped and auto-resumes after [pausedUntil]. */
    val pausedFrom: Long? = null,
    val pausedUntil: Long? = null,
    /** SHIFT: consecutive work (on) days in the rotating cycle. */
    val shiftWorkDays: Int = 0,
    /** SHIFT: consecutive rest (off) days in the rotating cycle. */
    val shiftRestDays: Int = 0,
    /** SHIFT: epoch-day (LocalDate.toEpochDay) the pattern's first work day starts on. */
    val shiftAnchorDate: Long = 0,
    /** CALENDAR: minutes of prep time subtracted from the first event's start. */
    val prepBufferMinutes: Int = 30,
    /** CALENDAR: when a considered day has no events, true = skip the day, false = fall back to floor time. */
    val calendarSkipIfNoEvent: Boolean = false,
    /**
     * Skip-next-occurrence: occurrences at/before this epoch-millis are skipped, so the alarm
     * resumes at the first occurrence after it. 0 = not skipping. Cleared naturally once passed.
     */
    val skipUntil: Long = 0,
    /**
     * Epoch millis a pending snooze ring is armed for; 0 = not snoozed. Set when the user snoozes
     * and cleared when the alarm is dismissed, rings again or times out. A one-shot/event alarm is
     * flipped to `enabled = false` the moment it fires, so without this the row would read "off"
     * while a snooze was still counting down in the background.
     */
    val snoozedUntil: Long = 0,
    /**
     * Optional "open this app when I dismiss you" action: an installed package and its label.
     * The ring screen shows an "Open <app>" button that launches it and stops the alarm — a gym
     * alarm can drop you into your workout app, a study alarm into Duolingo. Null = just ring.
     *
     * A user-chosen app here beats the one a limit-reset / notification alarm derives from the app
     * it was tracking (see [ring.AlarmRingService.resolveRingAction]).
     */
    val actionPackage: String? = null,
    val actionLabel: String? = null,
    /** Materialized next occurrence (epoch millis) */
    val nextTriggerAt: Long? = null,
    /** Soft-delete: epoch millis the alarm was moved to the recycle bin; 0 = live. */
    val deletedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** Recycle-bin retention: repeating alarms are kept longer than transient ones. */
    fun retentionMs(): Long = when (scheduleType) {
        SCHEDULE_WEEKLY, SCHEDULE_SHIFT, SCHEDULE_CALENDAR -> 7L * 24 * 60 * 60 * 1000
        else -> 24L * 60 * 60 * 1000
    }
    /** True while [now] falls inside an active pause window. */
    fun isPausedAt(now: Long): Boolean {
        val from = pausedFrom
        val until = pausedUntil
        return from != null && until != null && now in from until until
    }

    /** True when the next occurrence is being skipped (a future skip boundary is set). */
    fun isSkippingNextAt(now: Long): Boolean = skipUntil > now

    /** True while a snooze ring is still pending — the alarm is live even if [enabled] is false. */
    fun isSnoozedAt(now: Long): Boolean = snoozedUntil > now

    /**
     * How this alarm alerts, derived from [soundEnabled] + [vibrate] so no migration is needed.
     * A legacy alarm that was saved fully silent (neither flag) reads back as [VIBRATE_OFF] —
     * an alarm that does nothing at all is a bug, not a setting worth preserving.
     */
    val vibrationMode: Int
        get() = when {
            vibrate && !soundEnabled -> VIBRATE_ONLY
            vibrate -> VIBRATE_WITH_SOUND
            else -> VIBRATE_OFF
        }

    companion object {
        const val SCHEDULE_WEEKLY = "WEEKLY"
        const val SCHEDULE_ONCE = "ONCE"
        const val SCHEDULE_SHIFT = "SHIFT"
        const val SCHEDULE_CALENDAR = "CALENDAR"

        /**
         * Event-triggered alarm (v3). Its clock time (hour/minute) mirrors the fallback
         * ETA for display, but it never participates in the single-slot NextTrigger
         * derivation: its scheduling is owned entirely by [scheduling.EventAlarmCoordinator]
         * via a dedicated per-alarm setAlarmClock. NextTrigger returns null for it so it
         * never competes with regular alarms for the earliest slot.
         */
        const val SCHEDULE_EVENT = "EVENT"
        const val MISSION_NONE = "NONE"
        const val MISSION_MATH = "MATH"
        const val MISSION_QR = "QR"
        const val MISSION_STEPS = "STEPS"
        const val MISSION_PHOTO = "PHOTO"

        /**
         * Puzzle mission (v6). A brain-waking tap puzzle, variant chosen by [missionDifficulty]:
         * Easy/Medium = tap shuffled tiles 1→N in order; Hard = tap the odd-one-out over a few
         * rounds. In-house (not a clone of any third-party game); math is always an escape.
         */
        const val MISSION_PUZZLE = "PUZZLE"

        // Vibration modes — indices into R.array.vibration_modes.
        /** Sound only; no buzzing. The default for a new alarm. */
        const val VIBRATE_OFF = 0

        /** Sound and vibration together. */
        const val VIBRATE_WITH_SOUND = 1

        /** Vibration alone — the ring stays silent. */
        const val VIBRATE_ONLY = 2

        /** The (soundEnabled, vibrate) pair a [vibrationMode] corresponds to. */
        fun soundAndVibrateFor(mode: Int): Pair<Boolean, Boolean> = when (mode) {
            VIBRATE_WITH_SOUND -> true to true
            VIBRATE_ONLY -> false to true
            else -> true to false
        }
    }
}
