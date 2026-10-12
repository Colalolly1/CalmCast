package com.calmcast.podcast.data

import android.content.Context
import android.content.SharedPreferences
import com.calmcast.podcast.data.PodcastRepository.Episode

/**
 * Persists the current playback state (Episode, position, context) to disk
 * so it can be restored on a cold app start.
 */
data class PodcastSnapshot(
    val episode: Episode,
    val contextType: String, // "PODCAST" or "DOWNLOADS"
    val positionMs: Long,
    val playbackSpeed: Float,
    val artworkUrl: String?
)

class NowPlayingStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(snapshot: PodcastSnapshot) {
        val e = snapshot.episode
        prefs.edit()
            // Episode Data
            .putString(KEY_EP_ID, e.id)
            .putString(KEY_EP_PODCAST_ID, e.podcastId)
            .putString(KEY_EP_PODCAST_TITLE, e.podcastTitle)
            .putString(KEY_EP_TITLE, e.title)
            .putString(KEY_EP_DESC, e.description)
            .putString(KEY_EP_PUB_DATE, e.publishDate)
            .putLong(KEY_EP_PUB_DATE_MILLIS, e.publishDateMillis)
            .putString(KEY_EP_DURATION, e.duration)
            .putString(KEY_EP_AUDIO_URL, e.audioUrl)
            .putString(KEY_EP_DOWNLOAD_PATH, e.downloadedPath)

            // Playback State
            .putString(KEY_CONTEXT_TYPE, snapshot.contextType)
            .putLong(KEY_POSITION_MS, snapshot.positionMs)
            .putFloat(KEY_PLAYBACK_SPEED, snapshot.playbackSpeed)
            .putString(KEY_ARTWORK_URL, snapshot.artworkUrl)
            .apply()
    }

    fun load(): PodcastSnapshot? {
        val id = prefs.getString(KEY_EP_ID, null) ?: return null

        val episode = Episode(
            id = id,
            podcastId = prefs.getString(KEY_EP_PODCAST_ID, "") ?: "",
            podcastTitle = prefs.getString(KEY_EP_PODCAST_TITLE, "") ?: "",
            title = prefs.getString(KEY_EP_TITLE, "") ?: "",
            description = prefs.getString(KEY_EP_DESC, null),
            publishDate = prefs.getString(KEY_EP_PUB_DATE, "") ?: "",
            publishDateMillis = prefs.getLong(KEY_EP_PUB_DATE_MILLIS, 0L),
            duration = prefs.getString(KEY_EP_DURATION, "") ?: "",
            audioUrl = prefs.getString(KEY_EP_AUDIO_URL, "") ?: "",
            downloadedPath = prefs.getString(KEY_EP_DOWNLOAD_PATH, null)
        )

        return PodcastSnapshot(
            episode = episode,
            contextType = prefs.getString(KEY_CONTEXT_TYPE, "PODCAST") ?: "PODCAST",
            positionMs = prefs.getLong(KEY_POSITION_MS, 0L),
            playbackSpeed = prefs.getFloat(KEY_PLAYBACK_SPEED, 1f),
            artworkUrl = prefs.getString(KEY_ARTWORK_URL, null)
        )
    }

    /** Updates only the position, and only if the snapshot is for this episode. */
    fun savePosition(episodeId: String, positionMs: Long) {
        if (prefs.getString(KEY_EP_ID, null) != episodeId) return
        prefs.edit().putLong(KEY_POSITION_MS, positionMs).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "calmcast_now_playing"

        // Episode fields
        private const val KEY_EP_ID = "ep_id"
        private const val KEY_EP_PODCAST_ID = "ep_podcast_id"
        private const val KEY_EP_PODCAST_TITLE = "ep_podcast_title"
        private const val KEY_EP_TITLE = "ep_title"
        private const val KEY_EP_DESC = "ep_desc"
        private const val KEY_EP_PUB_DATE = "ep_pub_date"
        private const val KEY_EP_PUB_DATE_MILLIS = "ep_pub_date_millis"
        private const val KEY_EP_DURATION = "ep_duration"
        private const val KEY_EP_AUDIO_URL = "ep_audio_url"
        private const val KEY_EP_DOWNLOAD_PATH = "ep_download_path"

        // State fields
        private const val KEY_CONTEXT_TYPE = "context_type"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_ARTWORK_URL = "artwork_url"
    }
}