package com.example.alarmtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SleepSignalDao {

    @Insert
    suspend fun insert(signal: SleepSignal): Long

    /** The most recent bedtime signal strictly before [before] and no earlier than [notBefore]. */
    @Query(
        "SELECT * FROM sleep_signals WHERE occurredAt < :before AND occurredAt >= :notBefore " +
            "ORDER BY occurredAt DESC LIMIT 1"
    )
    suspend fun latestBetween(notBefore: Long, before: Long): SleepSignal?

    /** Most recent signal of any kind (used to throttle the once-per-evening app proxy). */
    @Query("SELECT * FROM sleep_signals ORDER BY occurredAt DESC LIMIT 1")
    suspend fun latest(): SleepSignal?

    /** Most recent signal of a given source (used to throttle the lock-status night proxy). */
    @Query("SELECT * FROM sleep_signals WHERE source = :source ORDER BY occurredAt DESC LIMIT 1")
    suspend fun latestOfSource(source: String): SleepSignal?

    @Query("SELECT * FROM sleep_signals ORDER BY occurredAt ASC")
    suspend fun getAll(): List<SleepSignal>

    @Query("DELETE FROM sleep_signals")
    suspend fun deleteAll()
}
