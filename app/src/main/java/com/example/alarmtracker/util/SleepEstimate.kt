package com.example.alarmtracker.util

/**
 * Honest, zero-permission sleep-OPPORTUNITY estimate (feature 6). The window runs
 * from the last evening bedtime signal (last app activity in the evening, or a
 * manual "going to bed" tap) to the morning dismissal. It is explicitly an estimate
 * of opportunity, never sleep stages, and reads no health/usage-stats data.
 */
object SleepEstimate {

    /** Widest plausible night; a longer gap means we lost the bedtime signal. */
    const val WINDOW_MS = 16L * 60 * 60 * 1000

    /** Sleep opportunity in millis, or null when there is no usable bedtime signal. */
    fun opportunityMs(bedtimeAt: Long?, wakeAt: Long): Long? {
        if (bedtimeAt == null) return null
        val span = wakeAt - bedtimeAt
        if (span <= 0 || span > WINDOW_MS) return null
        return span
    }
}
