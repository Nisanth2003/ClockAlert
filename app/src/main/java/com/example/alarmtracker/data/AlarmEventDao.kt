package com.example.alarmtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmEventDao {

    @Insert
    suspend fun insert(event: AlarmEvent): Long

    @Query("SELECT * FROM alarm_events WHERE occurredAt >= :since ORDER BY occurredAt ASC")
    fun observeSince(since: Long): Flow<List<AlarmEvent>>

    @Query(
        "SELECT * FROM alarm_events WHERE alarmId = :alarmId AND type = :type " +
            "ORDER BY occurredAt DESC LIMIT 1"
    )
    suspend fun latestOfType(alarmId: Long, type: String): AlarmEvent?

    @Query(
        "SELECT COUNT(*) FROM alarm_events WHERE alarmId = :alarmId " +
            "AND scheduledFor = :scheduledFor AND type = 'SNOOZED'"
    )
    suspend fun snoozeCountFor(alarmId: Long, scheduledFor: Long): Int

    /** All SNOOZED events since [since] (for the weekly snooze budget). */
    @Query("SELECT COUNT(*) FROM alarm_events WHERE type = 'SNOOZED' AND occurredAt >= :since")
    suspend fun snoozeCountSince(since: Long): Int

    /** Problem events (missed / fired-late) since [since], newest first — for the postmortem. */
    @Query(
        "SELECT * FROM alarm_events WHERE occurredAt >= :since " +
            "AND type IN ('MISSED', 'FIRED_LATE') ORDER BY occurredAt DESC"
    )
    suspend fun problemsSince(since: Long): List<AlarmEvent>

    /** Dismissals since [since], newest first — for the morning report card. */
    @Query(
        "SELECT * FROM alarm_events WHERE occurredAt >= :since " +
            "AND type = 'DISMISSED' ORDER BY occurredAt DESC"
    )
    suspend fun dismissalsSince(since: Long): List<AlarmEvent>

    @Query("SELECT * FROM alarm_events ORDER BY occurredAt ASC")
    suspend fun getAll(): List<AlarmEvent>

    @Query("DELETE FROM alarm_events")
    suspend fun deleteAll()
}
