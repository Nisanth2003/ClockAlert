package com.example.alarmtracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A zero-permission bedtime signal used to estimate the previous night's sleep
 * OPPORTUNITY window (never sleep stages). Sources:
 *  - APP_EVENING: the user last interacted with the app in the evening (a proxy
 *    for "still awake / heading to bed"), recorded at most once per evening.
 *  - MANUAL: the user tapped an explicit "I'm going to bed" action.
 */
@Entity(tableName = "sleep_signals")
data class SleepSignal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Long,
    /** SOURCE_APP_EVENING | SOURCE_MANUAL */
    val source: String
) {
    companion object {
        const val SOURCE_APP_EVENING = "APP_EVENING"
        const val SOURCE_MANUAL = "MANUAL"

        /** Night-time screen-off — a "phone put down for the night" bedtime proxy (lock-status fallback). */
        const val SOURCE_SCREEN_OFF = "SCREEN_OFF"

        /** Morning unlock — a wake proxy paired with the screen-off bedtime. */
        const val SOURCE_SCREEN_ON = "SCREEN_ON"

        /** Read from another health app via Health Connect (a real sleep session, most accurate). */
        const val SOURCE_HEALTH_CONNECT = "HEALTH_CONNECT"
    }
}
