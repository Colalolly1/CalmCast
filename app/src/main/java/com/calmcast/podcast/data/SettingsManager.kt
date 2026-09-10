package com.calmcast.podcast.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit
import com.calmcast.podcast.data.PodcastRepository.Episode

enum class DownloadLocation(val value: String) {
    INTERNAL("internal"),
    EXTERNAL("external")
}

class SettingsManager(context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isPictureInPictureEnabled = MutableStateFlow(isPictureInPictureEnabledSync())
    val isPictureInPictureEnabled = _isPictureInPictureEnabled.asStateFlow()

    private val _autoDownloadEnabled = MutableStateFlow(isAutoDownloadEnabled())
    val autoDownloadEnabled = _autoDownloadEnabled.asStateFlow()

    private val _skipSeconds = MutableStateFlow(getSkipSecondsSync())
    val skipSeconds = _skipSeconds.asStateFlow()

    private val _isKeepScreenOnEnabled = MutableStateFlow(isKeepScreenOnEnabledSync())
    val isKeepScreenOnEnabled = _isKeepScreenOnEnabled.asStateFlow()

    private val _removeHorizontalDividers = MutableStateFlow(removeHorizontalDividersSync())
    val removeHorizontalDividers = _removeHorizontalDividers.asStateFlow()

    private val _sleepTimerEnabled = MutableStateFlow(isSleepTimerEnabledSync())
    val sleepTimerEnabled = _sleepTimerEnabled.asStateFlow()

    private val _sleepTimerMinutes = MutableStateFlow(getSleepTimerMinutesSync())
    val sleepTimerMinutes = _sleepTimerMinutes.asStateFlow()

    private val _isSleepTimerActive = MutableStateFlow(isSleepTimerActiveSync())
    val isSleepTimerActive = _isSleepTimerActive.asStateFlow()

    private val _downloadLocation = MutableStateFlow(getDownloadLocationSync())
    val downloadLocation = _downloadLocation.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(getPlaybackSpeedSync())
    val playbackSpeed = _playbackSpeed.asStateFlow()

    private val _isAutoPlayNextEpisodeEnabled = MutableStateFlow(isAutoPlayNextEpisodeEnabledSync())
    val isAutoPlayNextEpisodeEnabled = _isAutoPlayNextEpisodeEnabled.asStateFlow()

    private val _isWiFiOnlyDownloadsEnabled = MutableStateFlow(isWiFiOnlyDownloadsEnabledSync())
    val isWiFiOnlyDownloadsEnabled = _isWiFiOnlyDownloadsEnabled.asStateFlow()

    private val _isSkipSilencesEnabled = MutableStateFlow(isSkipSilencesEnabledSync())
    val isSkipSilencesEnabled = _isSkipSilencesEnabled.asStateFlow()

    private val _isAutoDeletePlayedEnabled = MutableStateFlow(isAutoDeletePlayedEnabledSync())
    val isAutoDeletePlayedEnabled = _isAutoDeletePlayedEnabled.asStateFlow()

    private val _episodeSortOrder = MutableStateFlow(getEpisodeSortOrderSync())
    val episodeSortOrder = _episodeSortOrder.asStateFlow()

    private val _episodeFilter = MutableStateFlow(getEpisodeFilterSync())
    val episodeFilter = _episodeFilter.asStateFlow()

    private val _isBackgroundSyncEnabled = MutableStateFlow(isBackgroundSyncEnabledSync())
    val isBackgroundSyncEnabled = _isBackgroundSyncEnabled.asStateFlow()


    fun setPictureInPictureEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_PIP, enabled) }
        _isPictureInPictureEnabled.value = enabled
    }

    fun isPictureInPictureEnabledSync(): Boolean {
        return sharedPreferences.getBoolean(KEY_PIP, true)
    }

    fun setSkipSeconds(seconds: Int) {
        sharedPreferences.edit().putInt(KEY_SKIP_SECONDS, seconds).apply()
        _skipSeconds.value = seconds
    }

    fun getSkipSecondsSync(): Int {
        return sharedPreferences.getInt(KEY_SKIP_SECONDS, 30)
    }

    fun setAutoDownloadEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_AUTO_DOWNLOAD, enabled) }
        _autoDownloadEnabled.value = enabled
    }

    fun isAutoDownloadEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_AUTO_DOWNLOAD, false)
    }

    fun setKeepScreenOnEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_KEEP_SCREEN_ON, enabled) }
        _isKeepScreenOnEnabled.value = enabled
    }

    fun isKeepScreenOnEnabledSync(): Boolean {
        return sharedPreferences.getBoolean(KEY_KEEP_SCREEN_ON, false)
    }

    fun setRemoveHorizontalDividers(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_REMOVE_DIVIDERS, enabled) }
        _removeHorizontalDividers.value = enabled
    }

    fun removeHorizontalDividersSync(): Boolean {
        return sharedPreferences.getBoolean(KEY_REMOVE_DIVIDERS, false)
    }

    fun setSleepTimerEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_SLEEP_TIMER_ENABLED, enabled) }
        _sleepTimerEnabled.value = enabled
    }

    fun isSleepTimerEnabledSync(): Boolean {
        return sharedPreferences.getBoolean(KEY_SLEEP_TIMER_ENABLED, false)
    }

    fun setSleepTimerMinutes(minutes: Int) {
        sharedPreferences.edit { putInt(KEY_SLEEP_TIMER_MINUTES, minutes) }
        _sleepTimerMinutes.value = minutes
    }

    fun getSleepTimerMinutesSync(): Int {
        return sharedPreferences.getInt(KEY_SLEEP_TIMER_MINUTES, 30)
    }

    fun setSleepTimerActive(active: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_SLEEP_TIMER_ACTIVE, active) }
        _isSleepTimerActive.value = active
    }

    fun isSleepTimerActiveSync(): Boolean {
        return sharedPreferences.getBoolean(KEY_SLEEP_TIMER_ACTIVE, false)
    }

    fun setDownloadLocation(location: DownloadLocation) {
        sharedPreferences.edit { putString(KEY_DOWNLOAD_LOCATION, location.value) }
        _downloadLocation.value = location
    }

    fun getDownloadLocationSync(): DownloadLocation {
        val value = sharedPreferences.getString(KEY_DOWNLOAD_LOCATION, DownloadLocation.INTERNAL.value)
        return if (value == DownloadLocation.EXTERNAL.value) DownloadLocation.EXTERNAL else DownloadLocation.INTERNAL
    }

    fun setPlaybackSpeed(speed: Float) {
        sharedPreferences.edit { putFloat(KEY_PLAYBACK_SPEED, speed) }
        _playbackSpeed.value = speed
    }

    fun getPlaybackSpeedSync(): Float {
        return sharedPreferences.getFloat(KEY_PLAYBACK_SPEED, 1f)
    }

    fun setAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_AUTOPLAY_NEXT_EPISODE, enabled) }
        _isAutoPlayNextEpisodeEnabled.value = enabled
    }

    fun isAutoPlayNextEpisodeEnabledSync(): Boolean {
        return sharedPreferences.getBoolean(KEY_AUTOPLAY_NEXT_EPISODE, true)
    }

    fun setWiFiOnlyDownloadsEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_WIFI_ONLY_DOWNLOADS, enabled) }
        _isWiFiOnlyDownloadsEnabled.value = enabled
    }

    fun isWiFiOnlyDownloadsEnabledSync(): Boolean {
        return sharedPreferences.getBoolean(KEY_WIFI_ONLY_DOWNLOADS, true)
    }

    fun setSkipSilencesEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_SKIP_SILENCES, enabled) }
        _isSkipSilencesEnabled.value = enabled
    }

    fun isSkipSilencesEnabledSync(): Boolean {
        return sharedPreferences.getBoolean(KEY_SKIP_SILENCES, false)
    }

    fun setAutoDeletePlayedEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_AUTO_DELETE_PLAYED, enabled) }
        _isAutoDeletePlayedEnabled.value = enabled
    }

    fun isAutoDeletePlayedEnabledSync(): Boolean {
        return sharedPreferences.getBoolean(KEY_AUTO_DELETE_PLAYED, false)
    }

    fun setEpisodeSortOrder(order: String) {
        sharedPreferences.edit { putString(KEY_EPISODE_SORT_ORDER, order) }
        _episodeSortOrder.value = order
    }

    fun getEpisodeSortOrderSync(): String {
        return sharedPreferences.getString(KEY_EPISODE_SORT_ORDER, "newest") ?: "newest"
    }

    fun setEpisodeFilter(filter: String) {
        sharedPreferences.edit { putString(KEY_EPISODE_FILTER, filter) }
        _episodeFilter.value = filter
    }

    fun getEpisodeFilterSync(): String {
        return sharedPreferences.getString(KEY_EPISODE_FILTER, "all") ?: "all"
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_BACKGROUND_SYNC, enabled) }
        _isBackgroundSyncEnabled.value = enabled
    }

    fun isBackgroundSyncEnabledSync(): Boolean {
        return sharedPreferences.getBoolean(KEY_BACKGROUND_SYNC, true)
    }

    fun saveLastPlayedEpisode(episode: Episode, contextName: String) {
        sharedPreferences.edit {
            putString(KEY_LAST_EP_ID, episode.id)
            putString(KEY_LAST_EP_PODCAST_ID, episode.podcastId)
            putString(KEY_LAST_EP_PODCAST_TITLE, episode.podcastTitle)
            putString(KEY_LAST_EP_TITLE, episode.title)
            putString(KEY_LAST_EP_DESC, episode.description)
            putString(KEY_LAST_EP_PUB_DATE, episode.publishDate)
            putLong(KEY_LAST_EP_PUB_DATE_MILLIS, episode.publishDateMillis)
            putString(KEY_LAST_EP_DURATION, episode.duration)
            putString(KEY_LAST_EP_AUDIO_URL, episode.audioUrl)
            putString(KEY_LAST_EP_DOWNLOAD_PATH, episode.downloadedPath)
            putString(KEY_LAST_CONTEXT, contextName)
        }
    }

    fun getLastPlayedEpisode(): Pair<Episode, String>? {
        val id = sharedPreferences.getString(KEY_LAST_EP_ID, null) ?: return null
        val podcastId = sharedPreferences.getString(KEY_LAST_EP_PODCAST_ID, "") ?: ""
        val podcastTitle = sharedPreferences.getString(KEY_LAST_EP_PODCAST_TITLE, "") ?: ""
        val title = sharedPreferences.getString(KEY_LAST_EP_TITLE, "") ?: ""
        val description = sharedPreferences.getString(KEY_LAST_EP_DESC, null)
        val pubDate = sharedPreferences.getString(KEY_LAST_EP_PUB_DATE, "") ?: ""
        val pubDateMillis = sharedPreferences.getLong(KEY_LAST_EP_PUB_DATE_MILLIS, 0L)
        val duration = sharedPreferences.getString(KEY_LAST_EP_DURATION, "") ?: ""
        val audioUrl = sharedPreferences.getString(KEY_LAST_EP_AUDIO_URL, "") ?: ""
        val downloadPath = sharedPreferences.getString(KEY_LAST_EP_DOWNLOAD_PATH, null)
        val context = sharedPreferences.getString(KEY_LAST_CONTEXT, "PODCAST") ?: "PODCAST"

        val episode = Episode(
            id = id,
            podcastId = podcastId,
            podcastTitle = podcastTitle,
            title = title,
            description = description,
            publishDate = pubDate,
            publishDateMillis = pubDateMillis,
            duration = duration,
            audioUrl = audioUrl,
            downloadedPath = downloadPath
        )
        return episode to context
    }

    companion object {
        private const val PREFS_NAME = "calmcast_settings"
        private const val KEY_PIP = "pip_enabled"
        private const val KEY_SKIP_SECONDS = "skip_seconds"
        private const val KEY_AUTO_DOWNLOAD = "auto_download_enabled"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on_enabled"
        private const val KEY_REMOVE_DIVIDERS = "remove_horizontal_dividers"
        private const val KEY_SLEEP_TIMER_ENABLED = "sleep_timer_enabled"
        private const val KEY_SLEEP_TIMER_MINUTES = "sleep_timer_minutes"
        private const val KEY_SLEEP_TIMER_ACTIVE = "sleep_timer_active"
        private const val KEY_DOWNLOAD_LOCATION = "download_location"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_AUTOPLAY_NEXT_EPISODE = "auto_play_next_episode"
        private const val KEY_WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
        private const val KEY_SKIP_SILENCES = "skip_silences_enabled"
        private const val KEY_AUTO_DELETE_PLAYED = "auto_delete_played_enabled"
        private const val KEY_EPISODE_SORT_ORDER = "episode_sort_order"
        private const val KEY_EPISODE_FILTER = "episode_filter"
        private const val KEY_BACKGROUND_SYNC = "background_sync_enabled"

        private const val KEY_LAST_EP_ID = "last_played_ep_id"
        private const val KEY_LAST_EP_PODCAST_ID = "last_played_ep_podcast_id"
        private const val KEY_LAST_EP_PODCAST_TITLE = "last_played_ep_podcast_title"
        private const val KEY_LAST_EP_TITLE = "last_played_ep_title"
        private const val KEY_LAST_EP_DESC = "last_played_ep_description"
        private const val KEY_LAST_EP_PUB_DATE = "last_played_ep_pub_date"
        private const val KEY_LAST_EP_PUB_DATE_MILLIS = "last_played_ep_pub_date_millis"
        private const val KEY_LAST_EP_DURATION = "last_played_ep_duration"
        private const val KEY_LAST_EP_AUDIO_URL = "last_played_ep_audio_url"
        private const val KEY_LAST_EP_DOWNLOAD_PATH = "last_played_ep_download_path"
        private const val KEY_LAST_CONTEXT = "last_played_context"

        val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 2.5f)
        val SLEEP_TIMER_OPTIONS = listOf(5, 10, 15, 30, 45, 60)
    }
}