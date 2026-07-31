package com.example.alarmtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FriendDao {

    @Query("SELECT * FROM friends ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Friend>>

    @Query("SELECT * FROM friends ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<Friend>

    @Query("SELECT * FROM friends WHERE id = :id")
    suspend fun getById(id: Long): Friend?

    @Query("SELECT * FROM friends WHERE topic = :topic LIMIT 1")
    suspend fun getByTopic(topic: String): Friend?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(friend: Friend): Long

    @Update
    suspend fun update(friend: Friend)

    @Query("DELETE FROM friends WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ---- Watches ----

    @Query("SELECT * FROM friend_watches ORDER BY createdAt")
    fun observeWatches(): Flow<List<FriendWatch>>

    @Query("SELECT * FROM friend_watches WHERE friendId = :friendId ORDER BY createdAt")
    suspend fun watchesFor(friendId: Long): List<FriendWatch>

    @Query("SELECT * FROM friend_watches WHERE id = :id")
    suspend fun watchById(id: Long): FriendWatch?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatch(watch: FriendWatch): Long

    @Update
    suspend fun updateWatch(watch: FriendWatch)

    @Query("DELETE FROM friend_watches WHERE id = :id")
    suspend fun deleteWatch(id: Long)

    @Query("DELETE FROM friend_watches WHERE friendId = :friendId")
    suspend fun deleteWatchesFor(friendId: Long)
}
