package com.example.alarmtracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {

    // Live alarms only (deletedAt = 0). Soft-deleted rows are hidden from every normal surface.
    @Query("SELECT * FROM alarms WHERE deletedAt = 0 ORDER BY hour, minute, id")
    fun observeAll(): Flow<List<Alarm>>

    /** Recycle bin: soft-deleted alarms, most-recently-deleted first. */
    @Query("SELECT * FROM alarms WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    fun observeDeleted(): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms WHERE deletedAt > 0")
    suspend fun getDeleted(): List<Alarm>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getById(id: Long): Alarm?

    @Query("SELECT * FROM alarms WHERE enabled = 1 AND deletedAt = 0")
    suspend fun getEnabled(): List<Alarm>

    @Query("SELECT * FROM alarms ORDER BY id")
    suspend fun getAll(): List<Alarm>

    @Query("DELETE FROM alarms")
    suspend fun deleteAll()

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "SELECT * FROM alarms WHERE enabled = 1 AND deletedAt = 0 AND nextTriggerAt IS NOT NULL " +
            "ORDER BY nextTriggerAt ASC LIMIT 1"
    )
    suspend fun getNextEnabled(): Alarm?

    @Query(
        "SELECT * FROM alarms WHERE enabled = 1 AND deletedAt = 0 AND nextTriggerAt IS NOT NULL " +
            "ORDER BY nextTriggerAt ASC LIMIT 1"
    )
    fun observeNextEnabled(): Flow<Alarm?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alarm: Alarm): Long

    @Update
    suspend fun update(alarm: Alarm)

    @Delete
    suspend fun delete(alarm: Alarm)
}
