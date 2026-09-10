package com.calmcast.podcast.data

/**
 * Represents a single chapter within an episode.
 *
 * Sourced from ID3v2 CHAP frames embedded in the audio file (delivered by Media3
 * via Player.Listener.onMetadata) or, eventually, from RSS feed extensions
 * (Podlove Simple Chapters / podcast:chapters).
 */
data class Chapter(
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long? = null,
    val imageUrl: String? = null,
    val link: String? = null
)
