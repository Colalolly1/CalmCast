package com.calmcast.podcast.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SavedEpisodeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(episode: SavedEpisode)

    // ASC = play-queue order: first queued plays first. Reordering swaps savedAt values.
    @Query("SELECT * FROM saved_episodes ORDER BY savedAt ASC, episodeId ASC")
    suspend fun getAll(): List<SavedEpisode>

    @Query("SELECT episodeId FROM saved_episodes")
    suspend fun getAllIds(): List<String>

    @Query("DELETE FROM saved_episodes WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM saved_episodes WHERE episodeId = :episodeId)")
    suspend fun isSaved(episodeId: String): Boolean
}
