package com.example.alarmtracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "alarm_events",
    indices = [Index("alarmId")]
)
data class AlarmEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Soft reference — events are kept after alarm deletion. */
    val alarmId: Long,
    /** SCHEDULED | FIRED | FIRED_LATE | SNOOZED | DISMISSED | MISSED */
    val type: String,
    val scheduledFor: Long,
    val occurredAt: Long,
    /** SystemClock.elapsedRealtime() at the moment of the event. */
    val occurredElapsed: Long,
    val snoozeCount: Int = 0,
    val timeToDismissMs: Long? = null,
    val missionDurationMs: Long? = null,
    val detail: String? = null
) {
    companion object {
        const val TYPE_SCHEDULED = "SCHEDULED"
        const val TYPE_FIRED = "FIRED"
        const val TYPE_FIRED_LATE = "FIRED_LATE"
        const val TYPE_SNOOZED = "SNOOZED"
        const val TYPE_DISMISSED = "DISMISSED"
        const val TYPE_MISSED = "MISSED"
    }
}
