package com.calmcast.podcast.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ListenHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ListenHistory)

    @Query("SELECT * FROM listen_history ORDER BY lastListenedAt DESC")
    suspend fun getAll(): List<ListenHistory>

    @Query("DELETE FROM listen_history WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: String)

    @Query("DELETE FROM listen_history")
    suspend fun clearAll()
}
