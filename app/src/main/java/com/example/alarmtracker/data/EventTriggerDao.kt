package com.example.alarmtracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EventTriggerDao {

    @Query("SELECT * FROM event_triggers")
    fun observeAll(): Flow<List<EventTrigger>>

    @Query("SELECT * FROM event_triggers")
    suspend fun getAll(): List<EventTrigger>

    @Query("SELECT * FROM event_triggers WHERE enabled = 1")
    suspend fun getEnabled(): List<EventTrigger>

    @Query("SELECT * FROM event_triggers WHERE alarmId = :alarmId LIMIT 1")
    suspend fun getByAlarmId(alarmId: Long): EventTrigger?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(trigger: EventTrigger): Long

    @Update
    suspend fun update(trigger: EventTrigger)

    @Delete
    suspend fun delete(trigger: EventTrigger)

    @Query("DELETE FROM event_triggers WHERE alarmId = :alarmId")
    suspend fun deleteByAlarmId(alarmId: Long)

    @Query("DELETE FROM event_triggers")
    suspend fun deleteAll()
}
