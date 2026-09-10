package com.calmcast.podcast.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_episodes")
data class SavedEpisode(
    @PrimaryKey val episodeId: String,
    val podcastId: String,
    val podcastTitle: String,
    val episodeTitle: String,
    val description: String?,
    val publishDate: String,
    val publishDateMillis: Long,
    val duration: String,
    val audioUrl: String,
    val downloadedPath: String?,
    val savedAt: Long
)
