package com.calmcast.podcast.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.id3.UrlLinkFrame
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.calmcast.podcast.CalmCastApplication
import com.calmcast.podcast.PlaybackService
import com.calmcast.podcast.PlaybackError
import com.calmcast.podcast.api.FeedGoneException
import com.calmcast.podcast.api.FeedNotFoundException
import com.calmcast.podcast.data.ListenHistory
import com.calmcast.podcast.data.ListenHistoryDao
import com.calmcast.podcast.data.OpmlManager
import com.calmcast.podcast.data.NowPlayingStorage
import com.calmcast.podcast.data.PodcastSnapshot
import com.calmcast.podcast.data.PlaybackPosition
import com.calmcast.podcast.data.PlaybackPositionDao
import com.calmcast.podcast.data.Podcast
import com.calmcast.podcast.data.PodcastDao
import com.calmcast.podcast.data.PodcastRepository
import com.calmcast.podcast.data.PodcastRepository.Episode
import com.calmcast.podcast.data.PodcastRepository.PodcastWithEpisodes
import com.calmcast.podcast.data.SavedEpisode
import com.calmcast.podcast.data.SavedEpisodeDao
import com.calmcast.podcast.data.SettingsManager
import com.calmcast.podcast.data.SubscriptionManager
import com.calmcast.podcast.data.DownloadLocation
import com.calmcast.podcast.data.download.Download
import com.calmcast.podcast.data.download.DownloadDao
import com.calmcast.podcast.data.download.DownloadManager
import com.calmcast.podcast.data.download.DownloadStatus
import com.calmcast.podcast.data.download.StorageManager
import com.calmcast.podcast.utils.DateTimeFormatter
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class PodcastViewModel(
    private val application: Application,
    private val podcastDao: PodcastDao,
    private val playbackPositionDao: PlaybackPositionDao,
    private val downloadDao: DownloadDao,
    private val listenHistoryDao: ListenHistoryDao,
    private val savedEpisodeDao: SavedEpisodeDao,
    private val downloadManager: DownloadManager,
    private val settingsManager: SettingsManager
) : ViewModel() {

    companion object {
        private const val TAG = "PodcastViewModel"
        private const val SEARCH_DEBOUNCE_MS = 500L
    }

    private val subscriptionManager = SubscriptionManager(application, podcastDao)
    private val repository = PodcastRepository(
        com.calmcast.podcast.api.ItunesApiService(createCachedHttpClient()),
        subscriptionManager,
        podcastDao,
        playbackPositionDao
    )

    private val nowPlayingStorage: NowPlayingStorage by lazy {
        (application as CalmCastApplication).nowPlayingStorage
    }

    private val _subscriptions = mutableStateOf<List<Podcast>>(emptyList())
    val subscriptions: State<List<Podcast>> = _subscriptions

    private val _searchResults = mutableStateOf<List<Podcast>>(emptyList())
    val searchResults: State<List<Podcast>> = _searchResults

    private val _searchQuery = mutableStateOf("")
    val searchQuery: State<String> = _searchQuery

    private val _isLoading = mutableStateOf(false)
    val isLoading: State<Boolean> = _isLoading

    private val _errorMessage = mutableStateOf<String?>(null)

    private val _currentPodcastDetails = mutableStateOf<PodcastWithEpisodes?>(null)
    val currentPodcastDetails: State<PodcastWithEpisodes?> = _currentPodcastDetails

    private val _metadataLoading = mutableStateOf(false)

    private val _episodesLoading = mutableStateOf(false)
    val episodesLoading: State<Boolean> = _episodesLoading

    private val _detailError = mutableStateOf<Pair<Int, String>?>(null)
    val detailError: State<Pair<Int, String>?> = _detailError

    private val _downloads = mutableStateOf<List<Download>>(emptyList())
    val downloads: State<List<Download>> = _downloads

    private val _playbackPositions = mutableStateOf<Map<String, PlaybackPosition>>(emptyMap())
    val playbackPositions: State<Map<String, PlaybackPosition>> = _playbackPositions

    // Playback state
    private val _currentEpisode = mutableStateOf<Episode?>(null)
    val currentEpisode: State<Episode?> = _currentEpisode

    private val _currentArtworkUri = mutableStateOf<Uri?>(null)
    val currentArtworkUri: State<Uri?> = _currentArtworkUri

    private val _isPlaying = mutableStateOf(false)
    val isPlaying: State<Boolean> = _isPlaying

    private val _currentPosition = mutableLongStateOf(0L)
    val currentPosition: State<Long> = _currentPosition

    private val _duration = mutableLongStateOf(0L)
    val duration: State<Long> = _duration

    private val _isPlayerReady = mutableStateOf(false)

    private val _isBuffering = mutableStateOf(false)
    val isBuffering: State<Boolean> = _isBuffering

    private val _showFullPlayer = mutableStateOf(false)
    val showFullPlayer: State<Boolean> = _showFullPlayer

    private val _playbackError = mutableStateOf<PlaybackError?>(null)
    val playbackError: State<PlaybackError?> = _playbackError

    private var mediaController: MediaController? = null
    private lateinit var playerListener: Player.Listener
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var positionUpdateJob: Job? = null

    // Settings
    private val _isPictureInPictureEnabled = mutableStateOf(false)
    val isPictureInPictureEnabled: State<Boolean> = _isPictureInPictureEnabled

    private val _isAutoDownloadEnabled = mutableStateOf(false)
    val isAutoDownloadEnabled: State<Boolean> = _isAutoDownloadEnabled

    private val _skipSeconds = mutableIntStateOf(10)
    val skipSeconds: State<Int> = _skipSeconds

    private val _isKeepScreenOnEnabled = mutableStateOf(false)
    val isKeepScreenOnEnabled: State<Boolean> = _isKeepScreenOnEnabled

    private val _removeHorizontalDividers = mutableStateOf(false)
    val removeHorizontalDividers: State<Boolean> = _removeHorizontalDividers

    private val _sleepTimerEnabled = mutableStateOf(false)
    val sleepTimerEnabled: State<Boolean> = _sleepTimerEnabled

    private val _sleepTimerMinutes = mutableIntStateOf(0)
    val sleepTimerMinutes: State<Int> = _sleepTimerMinutes

    private val _isSleepTimerActive = mutableStateOf(false)
    val isSleepTimerActive: State<Boolean> = _isSleepTimerActive

    private val _sleepTimerRemainingSeconds = mutableLongStateOf(0L)
    val sleepTimerRemainingSeconds: State<Long> = _sleepTimerRemainingSeconds

    private val _downloadLocation = mutableStateOf(DownloadLocation.INTERNAL)
    val downloadLocation: State<DownloadLocation> = _downloadLocation

    private val _isExternalStorageAvailable = mutableStateOf(false)
    val isExternalStorageAvailable: State<Boolean> = _isExternalStorageAvailable

    private val _isAddingRSSFeed = mutableStateOf(false)
    val isAddingRSSFeed: State<Boolean> = _isAddingRSSFeed

    private val _playbackSpeed = mutableStateOf(1f)
    val playbackSpeed: State<Float> = _playbackSpeed

    private val _isAutoPlayNextEpisodeEnabled = mutableStateOf(false)
    val isAutoPlayNextEpisodeEnabled: State<Boolean> = _isAutoPlayNextEpisodeEnabled

    private val _isWiFiOnlyDownloadsEnabled = mutableStateOf(false)
    val isWiFiOnlyDownloadsEnabled: State<Boolean> = _isWiFiOnlyDownloadsEnabled

    private val _isSkipSilencesEnabled = mutableStateOf(false)
    val isSkipSilencesEnabled: State<Boolean> = _isSkipSilencesEnabled

    private val _isAutoDeletePlayedEnabled = mutableStateOf(false)
    val isAutoDeletePlayedEnabled: State<Boolean> = _isAutoDeletePlayedEnabled

    private val _isBackgroundSyncEnabled = mutableStateOf(true)
    val isBackgroundSyncEnabled: State<Boolean> = _isBackgroundSyncEnabled

    private val _episodeSortOrder = mutableStateOf("newest")
    val episodeSortOrder: State<String> = _episodeSortOrder

    private val _episodeFilter = mutableStateOf("all")
    val episodeFilter: State<String> = _episodeFilter

    private val _chapters = mutableStateOf<List<com.calmcast.podcast.data.Chapter>>(emptyList())
    val chapters: State<List<com.calmcast.podcast.data.Chapter>> = _chapters

    private val _currentChapterIndex = mutableIntStateOf(-1)
    val currentChapterIndex: State<Int> = _currentChapterIndex

    private val _isFetchingLatestEpisodes = mutableStateOf(false)
    val isFetchingLatestEpisodes: State<Boolean> = _isFetchingLatestEpisodes

    private val _listenHistory = mutableStateOf<List<ListenHistory>>(emptyList())
    val listenHistory: State<List<ListenHistory>> = _listenHistory

    private val _savedEpisodes = mutableStateOf<List<SavedEpisode>>(emptyList())
    val savedEpisodes: State<List<SavedEpisode>> = _savedEpisodes

    private val _savedEpisodeIds = mutableStateOf<Set<String>>(emptySet())
    val savedEpisodeIds: State<Set<String>> = _savedEpisodeIds

    private var searchJob: Job? = null
    private var recoveryJob: Job? = null
    private var detailFetchJob: Job? = null
    private val queueMutex = kotlinx.coroutines.sync.Mutex()

    private enum class AutoplayContext {
        PODCAST,
        DOWNLOADS
    }

    private var lastAutoplayContext: AutoplayContext = AutoplayContext.PODCAST

    private fun createCachedHttpClient(): OkHttpClient {
        val cacheDir = File(application.cacheDir, "http_rss_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val cache = Cache(cacheDir, 50L * 1024 * 1024) // 50MB

        return OkHttpClient.Builder()
            .cache(cache)
            .addInterceptor(Interceptor { chain ->
                val requestWithUserAgent = chain.request().newBuilder()
                    .header("User-Agent", "CalmCast/1.0 (Android; Podcast Player)")
                    .build()
                chain.proceed(requestWithUserAgent)
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    init {
        cleanupInvalidDownloads()
        loadInitialData()
        restoreState()
        observeDownloads()
        initializeMediaController()
        registerPlaybackServiceErrorCallback()
        loadSettings()
        loadListenHistory()
        loadSavedEpisodes()
    }

    private fun cleanupInvalidDownloads() {
        viewModelScope.launch {
            try {
                downloadDao.deleteInvalidDownloads()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up invalid downloads", e)
            }
        }
    }

    private fun loadSettings() {
        _isPictureInPictureEnabled.value = settingsManager.isPictureInPictureEnabledSync()
        _skipSeconds.intValue = settingsManager.getSkipSecondsSync()
        _isAutoDownloadEnabled.value = settingsManager.isAutoDownloadEnabled()
        _isKeepScreenOnEnabled.value = settingsManager.isKeepScreenOnEnabledSync()
        _removeHorizontalDividers.value = settingsManager.removeHorizontalDividersSync()
        _sleepTimerEnabled.value = settingsManager.isSleepTimerEnabledSync()
        _sleepTimerMinutes.intValue = settingsManager.getSleepTimerMinutesSync()
        _downloadLocation.value = settingsManager.getDownloadLocationSync()
        _playbackSpeed.value = settingsManager.getPlaybackSpeedSync()
        _isWiFiOnlyDownloadsEnabled.value = settingsManager.isWiFiOnlyDownloadsEnabledSync()
        // Storage probe touches the filesystem — keep it off the main thread during startup
        viewModelScope.launch(Dispatchers.IO) {
            val available = StorageManager.isExternalStorageAvailable(application)
            withContext(Dispatchers.Main) {
                _isExternalStorageAvailable.value = available
            }
        }

        viewModelScope.launch {
            settingsManager.isPictureInPictureEnabled.collect {
                _isPictureInPictureEnabled.value = it
            }
        }
        viewModelScope.launch {
            settingsManager.skipSeconds.collect {
                _skipSeconds.intValue = it
            }
        }
        viewModelScope.launch {
            settingsManager.autoDownloadEnabled.collect {
                _isAutoDownloadEnabled.value = it
            }
        }
        viewModelScope.launch {
            settingsManager.isKeepScreenOnEnabled.collect {
                _isKeepScreenOnEnabled.value = it
            }
        }
        viewModelScope.launch {
            settingsManager.removeHorizontalDividers.collect {
                _removeHorizontalDividers.value = it
            }
        }
        viewModelScope.launch {
            settingsManager.sleepTimerEnabled.collect {
                _sleepTimerEnabled.value = it
                if (!it) {
                    stopSleepTimer()
                }
            }
        }
        viewModelScope.launch {
            settingsManager.sleepTimerMinutes.collect {
                _sleepTimerMinutes.intValue = it
            }
        }
        // The timer runs in PlaybackService so it still fires with the app's screen gone.
        viewModelScope.launch {
            PlaybackService.sleepTimerRemainingSeconds.collect { seconds ->
                _sleepTimerRemainingSeconds.longValue = seconds
                _isSleepTimerActive.value = seconds > 0
            }
        }
        viewModelScope.launch {
            settingsManager.playbackSpeed.collect {
                _playbackSpeed.value = it
                applyPlaybackSpeed(it)
            }
        }
        viewModelScope.launch {
            settingsManager.isAutoPlayNextEpisodeEnabled.collect {
                _isAutoPlayNextEpisodeEnabled.value = it
            }
        }
        viewModelScope.launch {
            settingsManager.isWiFiOnlyDownloadsEnabled.collect {
                _isWiFiOnlyDownloadsEnabled.value = it
            }
        }
        viewModelScope.launch {
            settingsManager.isSkipSilencesEnabled.collect {
                _isSkipSilencesEnabled.value = it
                PlaybackService.setSkipSilenceEnabled(it)
            }
        }
        viewModelScope.launch {
            settingsManager.isAutoDeletePlayedEnabled.collect {
                _isAutoDeletePlayedEnabled.value = it
            }
        }
        viewModelScope.launch {
            settingsManager.isBackgroundSyncEnabled.collect {
                _isBackgroundSyncEnabled.value = it
            }
        }
        viewModelScope.launch {
            settingsManager.episodeSortOrder.collect {
                _episodeSortOrder.value = it
            }
        }
        viewModelScope.launch {
            settingsManager.episodeFilter.collect {
                _episodeFilter.value = it
            }
        }
    }

    fun setSkipSilencesEnabled(enabled: Boolean) {
        settingsManager.setSkipSilencesEnabled(enabled)
    }

    fun setAutoDeletePlayedEnabled(enabled: Boolean) {
        settingsManager.setAutoDeletePlayedEnabled(enabled)
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        settingsManager.setBackgroundSyncEnabled(enabled)
        if (enabled) {
            com.calmcast.podcast.work.EpisodeSyncWorker.schedule(
                application,
                settingsManager.isWiFiOnlyDownloadsEnabledSync()
            )
        } else {
            com.calmcast.podcast.work.EpisodeSyncWorker.cancel(application)
        }
    }

    fun setEpisodeSortOrder(order: String) {
        settingsManager.setEpisodeSortOrder(order)
    }

    fun setEpisodeFilter(filter: String) {
        settingsManager.setEpisodeFilter(filter)
    }

    private fun restoreState() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                nowPlayingStorage.load()
            } ?: return@launch

            val episode = snapshot.episode
            val contextType = snapshot.contextType
            val positionMs = snapshot.positionMs
            val speed = snapshot.playbackSpeed
            val artworkUrl = snapshot.artworkUrl

            // Don't resurrect an essentially-finished episode (a kill during the final
            // seconds can leave a stale snapshot behind even though we clear on ENDED).
            val durationMs = DateTimeFormatter.parseDuration(episode.duration)?.times(1000L) ?: 0L
            if (durationMs > 0 && positionMs >= durationMs * 99 / 100) {
                withContext(Dispatchers.IO) {
                    try {
                        nowPlayingStorage.clear()
                    } catch (_: Exception) {
                    }
                }
                return@launch
            }

            _currentEpisode.value = episode
            _currentPosition.longValue = positionMs
            _duration.longValue = DateTimeFormatter.parseDuration(episode.duration)?.times(1000L) ?: 0L
            setPlaybackSpeed(speed)

            if (artworkUrl != null) {
                _currentArtworkUri.value = artworkUrl.toUri()
            }

            if (contextType == AutoplayContext.PODCAST.name) {
                lastAutoplayContext = AutoplayContext.PODCAST
                launch {
                    val details = repository.getPodcastDetails(episode.podcastId).first().getOrNull()
                    if (details != null) {
                        _currentPodcastDetails.value = details
                        if (_currentArtworkUri.value == null) {
                            _currentArtworkUri.value = details.podcast.imageUrl?.toUri()
                        }
                    }
                }
            } else {
                lastAutoplayContext = AutoplayContext.DOWNLOADS
            }
        }
    }

    // In memory only, so progress in the lists stays current. PlaybackService writes the database.
    private fun rememberPosition(episodeId: String, position: Long) {
        _playbackPositions.value = _playbackPositions.value +
            (episodeId to PlaybackPosition(episodeId, position, System.currentTimeMillis()))
    }

    private fun persistSnapshot() {
        val episode = _currentEpisode.value ?: return
        val controller = mediaController
        val position = if (controller != null && controller.currentMediaItem?.mediaId == episode.id) {
            controller.currentPosition
        } else {
            _currentPosition.longValue
        }
        val speed = _playbackSpeed.value
        val context = lastAutoplayContext.name
        val artwork = _currentArtworkUri.value?.toString()

        viewModelScope.launch(Dispatchers.IO) {
            nowPlayingStorage.save(
                PodcastSnapshot(
                    episode = episode,
                    contextType = context,
                    positionMs = position,
                    playbackSpeed = speed,
                    artworkUrl = artwork
                )
            )
        }
    }

    private inner class PlayerListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                startPositionUpdates()
            } else {
                stopPositionUpdates()
                // PlaybackService saves the position; this only refreshes the lists on screen.
                val episodeId = mediaController?.currentMediaItem?.mediaId
                val position = mediaController?.currentPosition ?: 0L
                if (episodeId != null && position > 0) {
                    _currentPosition.longValue = position
                    rememberPosition(episodeId, position)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _isPlayerReady.value = playbackState == Player.STATE_READY
            _isBuffering.value = playbackState == Player.STATE_BUFFERING

            com.calmcast.podcast.util.CrashLogger.log(
                TAG,
                "VM.onPlaybackStateChanged state=$playbackState ep=${_currentEpisode.value?.title?.take(40)} pos=${_currentPosition.longValue}ms"
            )

            // Auto-delete played episode if setting enabled
            if (playbackState == Player.STATE_ENDED && settingsManager.isAutoDeletePlayedEnabledSync()) {
                _currentEpisode.value?.let { episode ->
                    val download = _downloads.value.find { it.episode.id == episode.id }
                    if (download != null && download.status == com.calmcast.podcast.data.download.DownloadStatus.DOWNLOADED) {
                        com.calmcast.podcast.util.CrashLogger.log(TAG, "Auto-deleting played episode: ${episode.title}")
                        downloadManager.deleteDownload(episode)
                    }
                }
            }

            if (playbackState == Player.STATE_ENDED) {
                val endedId = mediaController?.currentMediaItem?.mediaId ?: _currentEpisode.value?.id
                if (endedId != null) {
                    // PlaybackService stores the full length; mirror it so the lists show it played.
                    val controllerDuration = mediaController?.duration ?: 0L
                    val fullMs = if (controllerDuration > 0) controllerDuration
                    else (DateTimeFormatter.parseDuration(_currentEpisode.value?.duration) ?: 0L) * 1000L
                    if (fullMs > 0) rememberPosition(endedId, fullMs)
                }
                // PlaybackService plays the next queued episode itself, so this works with
                // the app's screen gone; the queue outranks autoplay.
                if (endedId != null && PlaybackService.queueAdvancedFrom == endedId) return
            }

            if (playbackState == Player.STATE_ENDED && settingsManager.isAutoPlayNextEpisodeEnabledSync()) {
                var handled = false
                when (lastAutoplayContext) {
                    AutoplayContext.PODCAST -> {
                        val episodes = _currentPodcastDetails.value?.episodes
                        val currentId = _currentEpisode.value?.id
                        val currentIndex = episodes?.indexOfFirst { it.id == currentId } ?: -1
                        val nextEpisode = if (currentIndex > 0) episodes?.get(currentIndex - 1) else null
                        if (nextEpisode != null) {
                            playEpisode(nextEpisode, context = AutoplayContext.PODCAST)
                            handled = true
                        }
                    }
                    AutoplayContext.DOWNLOADS -> {
                        val downloadsOrdered = _downloads.value
                            .filter { download ->
                                download.status != com.calmcast.podcast.data.download.DownloadStatus.DELETED &&
                                        download.episode.id.isNotBlank() &&
                                        download.episode.title.isNotBlank() &&
                                        download.episode.audioUrl.isNotBlank()
                            }
                            .sortedByDescending { it.episode.publishDateMillis }

                        val currentId = _currentEpisode.value?.id
                        val currentIndex = downloadsOrdered.indexOfFirst { it.episode.id == currentId }
                        val nextDownload = if (currentIndex > 0) downloadsOrdered.getOrNull(currentIndex - 1) else null
                        if (nextDownload != null) {
                            playEpisode(nextDownload.episode, context = AutoplayContext.DOWNLOADS)
                            handled = true
                        }
                    }
                }
                if (handled) {
                    return
                }
            }

            if (playbackState == Player.STATE_ENDED) {
                // Episode finished naturally (and autoplay didn't take over).
                _currentEpisode.value = null
                stopPositionUpdates()
                // Drop the persisted now-playing snapshot, or the next app launch
                // restores the finished episode into the Continue Listening strip.
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        nowPlayingStorage.clear()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clearing now-playing snapshot", e)
                    }
                }
            } else if (playbackState == Player.STATE_IDLE) {
                // IDLE here means an error or stop — keep the episode so the player bar
                // survives and the user can hit play to re-prepare and resume.
                stopPositionUpdates()
            }
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onMetadata(metadata: Metadata) {
            // ID3v2 CHAP frames arrive here as the player decodes the audio.
            // Wrapped defensively so malformed metadata can never crash playback.
            val newChapters = mutableListOf<com.calmcast.podcast.data.Chapter>()
            try {
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i)
                    if (entry is ChapterFrame) {
                        var title = ""
                        var imageUrl: String? = null
                        var link: String? = null
                        for (j in 0 until entry.subFrameCount) {
                            val sub = entry.getSubFrame(j)
                            when (sub) {
                                is TextInformationFrame -> {
                                    if (sub.id == "TIT2" && title.isEmpty()) {
                                        title = sub.values.firstOrNull() ?: ""
                                    }
                                }
                                is UrlLinkFrame -> {
                                    if (link == null) link = sub.url
                                }
                                is ApicFrame -> {
                                    // We could decode the embedded image but skip for now
                                }
                            }
                        }
                        newChapters.add(
                            com.calmcast.podcast.data.Chapter(
                                title = title.ifBlank { "Chapter ${newChapters.size + 1}" },
                                startTimeMs = entry.startTimeMs.toLong(),
                                endTimeMs = if (entry.endTimeMs > 0) entry.endTimeMs.toLong() else null,
                                imageUrl = imageUrl,
                                link = link
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing chapter metadata", e)
                return
            }
            if (newChapters.isNotEmpty()) {
                val sorted = newChapters.sortedBy { it.startTimeMs }
                // Merge with existing chapters in case more arrive in subsequent metadata events
                val merged = (_chapters.value + sorted)
                    .distinctBy { it.startTimeMs }
                    .sortedBy { it.startTimeMs }
                _chapters.value = merged
                Log.d(TAG, "Loaded ${_chapters.value.size} chapter(s) from ID3 metadata")
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            handleMediaItemChange(mediaItem)
        }
    }

    private fun handleMediaItemChange(mediaItem: MediaItem?) {
        val controller = mediaController ?: return
        if (mediaItem == null) {
            if (_currentEpisode.value != null && controller.playbackState == Player.STATE_IDLE) {
                val restoredEp = _currentEpisode.value!!
                val restoredPos = _currentPosition.longValue
                preparePlayerForRestoredItem(restoredEp, restoredPos)
                return
            }
            return
        }

        val mediaId = mediaItem.mediaId

        // A different episode, possibly started by PlaybackService from the queue.
        if (mediaId != _currentEpisode.value?.id) {
            _chapters.value = emptyList()
            _currentChapterIndex.intValue = -1
            mediaItem.mediaMetadata.artworkUri?.let { _currentArtworkUri.value = it }
            loadListenHistory()
        }

        // 1) Prefer a fully populated episode from downloads
        val fromDownload = _downloads.value.find { it.episode.id == mediaId }?.episode
        if (fromDownload != null) {
            _currentEpisode.value = fromDownload
            return
        }

        // 2) Fall back to the currently loaded podcast details, if present
        val fromCurrentPodcast = _currentPodcastDetails.value?.episodes?.find { it.id == mediaId }
        if (fromCurrentPodcast != null) {
            _currentEpisode.value = fromCurrentPodcast
            return
        }

        val extras = mediaItem.mediaMetadata.extras
        if (extras != null && extras.containsKey("episodeId")) {
            val reconstructedEpisode = Episode(
                id = extras.getString("episodeId") ?: "",
                podcastId = extras.getString("podcastId") ?: "",
                podcastTitle = extras.getString("podcastTitle") ?: "",
                title = mediaItem.mediaMetadata.title.toString(),
                description = extras.getString("description"),
                publishDate = extras.getString("publishDate") ?: "",
                publishDateMillis = extras.getLong("publishDateMillis"),
                duration = extras.getString("duration") ?: "",
                audioUrl = extras.getString("audioUrl") ?: "",
                downloadedPath = extras.getString("downloadedPath")
            )
            _currentEpisode.value = reconstructedEpisode
            _currentArtworkUri.value = mediaItem.mediaMetadata.artworkUri
            return
        }

        // 4) If we still can't map this media item to a known episode, stop playback
        controller.stop()
        _isPlaying.value = false
        _currentEpisode.value = null
        stopPositionUpdates()
    }

    // Only a finished download is a playable file; a failed or paused one is a partial.
    private fun downloadedFileUri(episodeId: String): String? =
        _downloads.value.find { it.episode.id == episodeId }
            ?.takeIf { it.status == DownloadStatus.DOWNLOADED }
            ?.downloadUri

    private fun preparePlayerForRestoredItem(episode: Episode, startPositionMs: Long) {
        val controller = mediaController ?: return

        // Ensure we have correct URL (downloaded or stream)
        var episodeToPlay = episode
        val downloadedUri = downloadedFileUri(episode.id)
        if (downloadedUri != null) {
            episodeToPlay = episode.copy(audioUrl = downloadedUri)
        } else if (episode.downloadedPath != null) {
            episodeToPlay = episode.copy(audioUrl = episode.downloadedPath)
        }

        val artworkUri = _currentArtworkUri.value

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(episode.podcastTitle)
            .setArtworkUri(artworkUri)
            .setExtras(android.os.Bundle().apply {
                putString("episodeId", episode.id)
                putString("podcastId", episode.podcastId)
                putString("podcastTitle", episode.podcastTitle)
                putString("description", episode.description)
                putString("publishDate", episode.publishDate)
                putLong("publishDateMillis", episode.publishDateMillis)
                putString("duration", episode.duration)
                putString("audioUrl", episode.audioUrl)
                putString("downloadedPath", episode.downloadedPath)
            })
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(episodeToPlay.audioUrl)
            .setMediaId(episode.id)
            .setMediaMetadata(mediaMetadata)
            .build()

        controller.setMediaItem(mediaItem, startPositionMs)
        controller.prepare()
        controller.pause() // Start paused on restore

        // Restore speed
        val params = PlaybackParameters(_playbackSpeed.value)
        controller.playbackParameters = params
    }

    private fun initializeMediaController() {
        playerListener = PlayerListener()
        val sessionToken = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(application, sessionToken).buildAsync()
        controllerFuture?.addListener({
            val controller = controllerFuture?.get() ?: return@addListener
            mediaController = controller
            controller.addListener(playerListener)

            // Apply skip silences setting (lives on the underlying ExoPlayer)
            PlaybackService.setSkipSilenceEnabled(settingsManager.isSkipSilencesEnabledSync())

            if (controller.playbackState != Player.STATE_IDLE && controller.currentMediaItem != null) {
                // Player is already alive (service running), sync UI to it
                _currentPosition.longValue = controller.currentPosition
                _duration.longValue = controller.duration
                _isPlaying.value = controller.isPlaying
                _isBuffering.value = controller.playbackState == Player.STATE_BUFFERING
                handleMediaItemChange(controller.currentMediaItem)
            } else {
                // Player is fresh/idle. If we restored state, push it to player now.
                val restoredEp = _currentEpisode.value
                if (restoredEp != null) {
                    preparePlayerForRestoredItem(restoredEp, _currentPosition.longValue)
                }
            }

            if (controller.isPlaying) {
                startPositionUpdates()
            } else {
                stopPositionUpdates()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun observeDownloads() {
        var lastLoadedIds: Set<String> = emptySet()
        downloadManager.downloads.onEach { downloadList ->
            _downloads.value = downloadList

            val currentIds = downloadList.map { it.episode.id }.toSet()
            if (currentIds != lastLoadedIds && currentIds.isNotEmpty()) {
                lastLoadedIds = currentIds
                try {
                    val positions = playbackPositionDao.getPlaybackPositions(currentIds.toList())
                    val newPositions = positions.associateBy { it.episodeId }
                    _playbackPositions.value = newPositions + _playbackPositions.value
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading playback positions for downloads", e)
                }
            }
        }.launchIn(viewModelScope)
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            while (_isPlaying.value) {
                _currentPosition.longValue = mediaController?.currentPosition ?: 0L
                _duration.longValue = mediaController?.duration ?: 0L

                // Update current chapter index based on position
                val chs = _chapters.value
                if (chs.isNotEmpty()) {
                    val pos = _currentPosition.longValue
                    val idx = chs.indexOfLast { it.startTimeMs <= pos }
                    if (idx != _currentChapterIndex.intValue) {
                        _currentChapterIndex.intValue = idx
                    }
                }

                // Saving is PlaybackService's job; this loop only feeds the screen.
                // 1s tick: e-ink refresh is slow; faster updates just burn CPU on recomposition
                delay(1000)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    private fun loadInitialData() {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                // Load from database immediately
                val cachedPodcasts = subscriptionManager.getSubscriptions()
                _subscriptions.value = cachedPodcasts.sortedBy { it.title }
                _isLoading.value = false

                // Run refresh in background
                _isFetchingLatestEpisodes.value = true
                val refreshedPodcasts = refreshSubscribedPodcastEpisodes()
                _subscriptions.value = refreshedPodcasts.sortedBy { it.title }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading initial data", e)
                _errorMessage.value = e.message
            } finally {
                _isFetchingLatestEpisodes.value = false
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query

        searchJob?.cancel()

        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isLoading.value = false
        } else {
            searchJob = viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                _isLoading.value = true
                repository.searchPodcasts(query).collect { result ->
                    result.onSuccess { podcasts ->
                        _searchResults.value = podcasts
                        _isLoading.value = false
                    }.onFailure { exception ->
                        _errorMessage.value = exception.message
                        _isLoading.value = false
                    }
                }
            }
        }
    }

    fun subscribeToPodcast(podcast: Podcast) {
        viewModelScope.launch {
            repository.subscribeToPodcast(podcast).onSuccess {
                if (!_subscriptions.value.any { it.id == podcast.id }) {
                    val updatedList = (_subscriptions.value + podcast).sortedBy { it.title }
                    _subscriptions.value = updatedList
                }
            }.onFailure { _errorMessage.value = it.message }
        }
    }

    fun unsubscribeFromPodcast(podcastId: String) {
        viewModelScope.launch {
            repository.unsubscribeFromPodcast(podcastId).onSuccess {
                _subscriptions.value = _subscriptions.value.filter { it.id != podcastId }
            }.onFailure { error ->
                Log.e(TAG, "unsubscribeFromPodcast failed for $podcastId", error)
                _errorMessage.value = error.message
            }
        }
    }

    fun subscribeToRSSUrl(feedUrl: String, onResult: (Result<Podcast>) -> Unit = {}) {
        if (_isAddingRSSFeed.value) return
        _isAddingRSSFeed.value = true
        viewModelScope.launch {
            try {
                val result = repository.subscribeToRSSUrl(feedUrl)
                result.onSuccess { podcast ->
                    if (!_subscriptions.value.any { it.id == podcast.id }) {
                        val updatedList = (_subscriptions.value + podcast).sortedBy { it.title }
                        _subscriptions.value = updatedList
                    }
                }.onFailure {
                    _errorMessage.value = it.message
                }
                onResult(result)
            } finally {
                _isAddingRSSFeed.value = false
            }
        }
    }

    fun isSubscribed(podcastId: String): Boolean = _subscriptions.value.any { it.id == podcastId }

    fun fetchPodcastDetails(podcastId: String) {
        _metadataLoading.value = true
        _episodesLoading.value = true
        _detailError.value = null
        // Cancel any in-flight fetch for a different podcast — otherwise its late
        // emission overwrites the screen with the wrong show.
        detailFetchJob?.cancel()
        detailFetchJob = viewModelScope.launch {
            var podcast = podcastDao.getPodcast(podcastId)

            if (podcast == null) {
                val searchResult = _searchResults.value.find { it.id == podcastId }
                if (searchResult != null) {
                    podcastDao.insertPodcast(searchResult)
                    podcast = searchResult
                } else {
                    Log.e(TAG, "Podcast not found in search results either")
                }
            }

            _currentPodcastDetails.value = PodcastWithEpisodes(podcast, emptyList())
            _metadataLoading.value = false

            try {
                repository.getPodcastDetails(podcastId).collect { result ->
                    result.onSuccess { podcastWithEpisodes ->
                        if (podcastWithEpisodes != null) {
                            _currentPodcastDetails.value = podcastWithEpisodes

                            val episodeIds = podcastWithEpisodes.episodes.map { it.id }
                            val positions = playbackPositionDao.getPlaybackPositions(episodeIds)
                            _playbackPositions.value =
                                _playbackPositions.value + positions.associateBy { it.episodeId }

                            if (podcastWithEpisodes.episodes.isNotEmpty()) {
                                podcastDao.updateLastSeenEpisodeId(
                                    podcast.id,
                                    podcastWithEpisodes.episodes[0].id
                                )
                            }

                            if (podcast.newEpisodeCount > 0) {
                                podcastDao.updateNewEpisodeCount(podcast.id, 0)

                                val index = _subscriptions.value.indexOf(podcast)
                                if (index != -1) {
                                    // get updated podcast from database
                                    val updatedPod = podcastDao.getPodcast(podcast.id)

                                    _subscriptions.value =
                                        _subscriptions.value.toMutableList().apply {
                                            set(index, updatedPod)
                                        }
                                }
                            }
                        }
                        _episodesLoading.value = false
                    }.onFailure { exception ->
                        Log.e(TAG, "Error fetching episodes", exception)
                        when (exception) {
                            is FeedNotFoundException -> _detailError.value = 404 to "Podcast feed not found"
                            is FeedGoneException -> {
                                // Check if it's a 403 error
                                val errorCode = if (exception.message?.contains("403") == true) 403 else 410
                                val message = exception.message ?: "Podcast feed unavailable"
                                _detailError.value = errorCode to message
                            }
                            else -> _errorMessage.value = exception.message
                        }
                        _episodesLoading.value = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in fetchPodcastDetails", e)
                _errorMessage.value = e.message
                _episodesLoading.value = false
            }
        }
    }

    fun refreshPodcastEpisodes(podcastId: String) {
        _episodesLoading.value = true
        viewModelScope.launch {
            try {
                // Invalidate cache for this specific podcast (refresh button clicked)
                repository.invalidatePodcastEpisodeCache(podcastId)
                repository.getPodcastDetails(podcastId).collect { result ->
                    result.onSuccess { podcastWithEpisodes ->
                        if (podcastWithEpisodes != null) {
                            _currentPodcastDetails.value = podcastWithEpisodes
                            // Load playback positions for the refreshed episodes
                            val episodeIds = podcastWithEpisodes.episodes.map { it.id }
                            val positions = playbackPositionDao.getPlaybackPositions(episodeIds)
                            _playbackPositions.value = _playbackPositions.value + positions.associateBy { it.episodeId }
                        }
                        _episodesLoading.value = false
                    }.onFailure { exception ->
                        _errorMessage.value = "Failed to refresh episodes: ${exception.message}"
                        _episodesLoading.value = false
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error refreshing episodes: ${e.message}"
                _episodesLoading.value = false
            }
        }
    }

    fun reloadData() {
        loadInitialData()
    }

    private suspend fun refreshSubscribedPodcastEpisodes(): List<Podcast> {
        return try {
            // Invalidate cache for all podcasts (app init/foreground)
            repository.invalidateAllEpisodeCache()
            var subscribedPodcasts = repository.getSubscribedPodcasts().first().getOrNull() ?: emptyList()

            // Fetch all podcast details concurrently
            coroutineScope {
                subscribedPodcasts.map { podcast ->
                    async {
                        try {
                            val episodes = repository.getPodcastDetails(podcast.id).first().getOrNull()?.episodes

                            episodes?.let {
                                var index = 0
                                val lastSeenEpisode = it.find { ep -> ep.id == podcast.lastSeenEpisodeId }
                                if (lastSeenEpisode != null) {
                                    index = it.indexOf(lastSeenEpisode)
                                    podcastDao.updateNewEpisodeCount(podcast.id, index)
                                }

                                if (settingsManager.isAutoDownloadEnabled() && index > 0) {
                                    // The Wi-Fi-only setting is applied by the download job as a
                                    // network constraint, so new episodes wait for Wi-Fi.
                                    val newEpisodes = it.slice(0 until index)

                                    newEpisodes.forEach { episode ->
                                        val status = _downloads.value.find { dl -> dl.episode.id == episode.id }?.status
                                        // Skip only what the user deleted or what is already done. Paused
                                        // and failed still need downloading; a pending job is left alone.
                                        if (status != DownloadStatus.DELETED && status != DownloadStatus.DOWNLOADED) {
                                            downloadManager.startAutoDownload(episode)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Exception refreshing episodes for ${podcast.title}", e)
                        }
                    }
                }.awaitAll()
            }
            // After updating newEpisodeCount in database, refetch podcasts to get fresh data
            subscribedPodcasts = repository.getSubscribedPodcasts().first().getOrNull() ?: emptyList()
            subscribedPodcasts
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing all podcast episodes", e)
            emptyList()
        }
    }

    fun playEpisodeFromPodcast(episode: Episode, restart: Boolean = false) {
        playEpisode(episode, restart, AutoplayContext.PODCAST)
    }

    fun playEpisodeFromDownloads(episode: Episode, restart: Boolean = false) {
        playEpisode(episode, restart, AutoplayContext.DOWNLOADS)
    }

    private fun playEpisode(
        episode: Episode,
        restart: Boolean = false,
        context: AutoplayContext,
        autoPlay: Boolean = true,
        showFullPlayer: Boolean = true
    ) {
        lastAutoplayContext = context

        // A user-initiated play supersedes any in-flight network recovery
        recoveryJob?.cancel()
        recoveryJob = null

        // Clear chapters from previous episode; new ones arrive via onMetadata
        _chapters.value = emptyList()
        _currentChapterIndex.intValue = -1

        // The outgoing episode's position is saved by PlaybackService as it is replaced.
        viewModelScope.launch {
            var episodeToPlay = episode

            val downloadedUri = downloadedFileUri(episode.id)
            if (downloadedUri != null) {
                episodeToPlay = episode.copy(audioUrl = downloadedUri)
            } else if (episode.downloadedPath != null) {
                episodeToPlay = episode.copy(audioUrl = episode.downloadedPath)
            }

            var details = currentPodcastDetails.value
            if (details == null || details.podcast.id != episode.podcastId) {
                // Fetch podcast details if not available
                details = repository.getPodcastDetails(episode.podcastId).first().getOrNull()
                if (details != null && context == AutoplayContext.PODCAST) {
                    _currentPodcastDetails.value = details
                }
            }

            var lastPosition = 0L
            if (!restart) {
                lastPosition = repository.getPlaybackPosition(episode.id)?.position ?: 0L
                // Already loaded: the live position is newer than the last periodic save.
                val controller = mediaController
                if (controller != null && controller.currentMediaItem?.mediaId == episode.id) {
                    lastPosition = controller.currentPosition
                }
                val durationInSeconds = DateTimeFormatter.parseDuration(episode.duration)
                if (durationInSeconds != null && lastPosition > 0) {
                    val durationInMilliseconds = durationInSeconds * 1000
                    if (lastPosition.toDouble() / durationInMilliseconds > 0.99) {
                        lastPosition = 0L
                    }
                }
            }

            val artworkUri = details?.podcast?.imageUrl?.toUri()
            _currentArtworkUri.value = artworkUri

            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(episode.title)
                .setArtist(details?.podcast?.title)
                .setArtworkUri(artworkUri)
                .setExtras(android.os.Bundle().apply {
                    putString("episodeId", episode.id)
                    putString("podcastId", episode.podcastId)
                    putString("podcastTitle", details?.podcast?.title ?: episode.podcastTitle)
                    putString("description", episode.description)
                    putString("publishDate", episode.publishDate)
                    putLong("publishDateMillis", episode.publishDateMillis)
                    putString("duration", episode.duration)
                    putString("audioUrl", episode.audioUrl)
                    putString("downloadedPath", episode.downloadedPath)
                })
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(episodeToPlay.audioUrl)
                .setMediaId(episode.id)
                .setMediaMetadata(mediaMetadata)
                .build()

            mediaController?.setMediaItem(mediaItem, lastPosition)
            mediaController?.prepare()
            applyPlaybackSpeed(_playbackSpeed.value)

            if (autoPlay) {
                mediaController?.play()
                // Sleep timer now only runs when the user explicitly starts it from the
                // player (it no longer silently auto-applies a 30-min pause to every episode).
            } else {
                mediaController?.pause()
            }

            _currentEpisode.value = episodeToPlay
            _currentPosition.longValue = lastPosition
            _showFullPlayer.value = showFullPlayer
            persistSnapshot()
            recordListenHistory(episode)
        }
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            mediaController?.pause()
        } else {
            val controller = mediaController
            val ep = _currentEpisode.value
            if (controller != null && ep != null &&
                (controller.playbackState == Player.STATE_IDLE || controller.currentMediaItem == null)
            ) {
                // Player died (network error or process restart). play() alone cannot leave
                // STATE_IDLE — it must be re-prepared, or the button spins forever.
                com.calmcast.podcast.util.CrashLogger.log(
                    TAG, "togglePlayPause: player IDLE, re-preparing at ${_currentPosition.longValue}ms"
                )
                preparePlayerForRestoredItem(ep, _currentPosition.longValue)
            }
            mediaController?.play()
        }
    }

    fun seekForward() {
        seekTo((mediaController?.currentPosition ?: 0L) + (_skipSeconds.intValue * 1000L))
    }

    fun seekBackward() {
        seekTo((mediaController?.currentPosition ?: 0L) - (_skipSeconds.intValue * 1000L))
    }

    // PlaybackService writes the new position to the database when the seek lands.
    fun seekTo(positionMs: Long) {
        val duration = mediaController?.duration ?: 0L
        val target = if (duration > 0) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        mediaController?.seekTo(target)
        // Show it straight away; the 1s update loop only runs while playing.
        _currentPosition.longValue = target
    }

    fun seekToChapter(index: Int) {
        val chs = _chapters.value
        if (index < 0 || index >= chs.size) return
        seekTo(chs[index].startTimeMs)
        _currentChapterIndex.intValue = index
    }

    fun nextChapter() {
        val chs = _chapters.value
        if (chs.isEmpty()) return
        val pos = _currentPosition.longValue
        val nextIdx = chs.indexOfFirst { it.startTimeMs > pos }
        if (nextIdx >= 0) seekToChapter(nextIdx)
    }

    fun previousChapter() {
        val chs = _chapters.value
        if (chs.isEmpty()) return
        val pos = _currentPosition.longValue
        // Go to previous chapter (or restart current if more than 3s in)
        val currentIdx = chs.indexOfLast { it.startTimeMs <= pos }
        val targetIdx = when {
            currentIdx < 0 -> 0
            pos - chs[currentIdx].startTimeMs > 3000L -> currentIdx
            else -> (currentIdx - 1).coerceAtLeast(0)
        }
        seekToChapter(targetIdx)
    }

    fun showFullPlayer() {
        _showFullPlayer.value = true
    }

    fun minimizePlayer() {
        _showFullPlayer.value = false
    }

    fun downloadEpisode(episode: Episode) {
        downloadManager.startDownload(episode)
    }

    // Starts the job again; it resumes from whatever partial file is on disk.
    fun retryDownload(episode: Episode) {
        downloadManager.startDownload(episode)
    }

    fun pauseDownload(episodeId: String) {
        downloadManager.pauseDownload(episodeId)
    }

    fun cancelDownload(episodeId: String) {
        downloadManager.cancelDownload(episodeId)
    }

    fun resumeDownload(episodeId: String) {
        downloadManager.resumeDownload(episodeId)
    }

    fun deleteEpisode(episode: Episode) {
        downloadManager.deleteDownload(episode)
    }

    /**
     * Disk usage of downloads, grouped per podcast, largest first. Includes PAUSED and
     * FAILED partials — they hold real bytes on disk and would otherwise be invisible.
     */
    fun computeStorageUsage(): List<PodcastStorageUsage> =
        _downloads.value
            .filter {
                it.status == com.calmcast.podcast.data.download.DownloadStatus.DOWNLOADED ||
                        it.status == com.calmcast.podcast.data.download.DownloadStatus.PAUSED ||
                        it.status == com.calmcast.podcast.data.download.DownloadStatus.FAILED
            }
            .groupBy { it.episode.podcastId }
            .map { (podcastId, group) ->
                PodcastStorageUsage(
                    podcastId = podcastId,
                    podcastTitle = group.first().episode.podcastTitle.ifBlank { "Unknown podcast" },
                    episodeCount = group.size,
                    totalBytes = group.sumOf { d ->
                        when {
                            d.downloadedBytes > 0 -> d.downloadedBytes
                            // Older finished downloads only recorded the total.
                            d.status == DownloadStatus.DOWNLOADED -> d.totalBytes.coerceAtLeast(0L)
                            else -> 0L
                        }
                    }
                )
            }
            .sortedByDescending { it.totalBytes }

    /**
     * Deletes a podcast's downloads (completed, paused, and failed partials) from disk.
     * The currently playing episode is skipped so we never yank audio mid-listen.
     */
    fun deletePodcastDownloads(podcastId: String) {
        val playingId = _currentEpisode.value?.id
        _downloads.value
            .filter { d ->
                d.episode.podcastId == podcastId &&
                        d.episode.id != playingId &&
                        d.status in listOf(
                    com.calmcast.podcast.data.download.DownloadStatus.DOWNLOADED,
                    com.calmcast.podcast.data.download.DownloadStatus.PAUSED,
                    com.calmcast.podcast.data.download.DownloadStatus.FAILED
                )
            }
            .forEach { downloadManager.deleteDownload(it.episode) }
    }

    fun clearPlaybackError() {
        _playbackError.value = null
    }

    fun clearPodcastDetails() {
        detailFetchJob?.cancel()
        _currentPodcastDetails.value = null
        _detailError.value = null
    }

    fun setPictureInPictureEnabled(enabled: Boolean) {
        settingsManager.setPictureInPictureEnabled(enabled)
    }

    fun setAutoDownloadEnabled(enabled: Boolean) {
        settingsManager.setAutoDownloadEnabled(enabled)
    }

    fun setSkipSeconds(seconds: Int) {
        settingsManager.setSkipSeconds(seconds)
    }

    fun setKeepScreenOnEnabled(enabled: Boolean) {
        settingsManager.setKeepScreenOnEnabled(enabled)
    }

    fun setRemoveHorizontalDividers(enabled: Boolean) {
        settingsManager.setRemoveHorizontalDividers(enabled)
    }

    fun setSleepTimerEnabled(enabled: Boolean) {
        settingsManager.setSleepTimerEnabled(enabled)
    }

    fun setSleepTimerMinutes(minutes: Int) {
        settingsManager.setSleepTimerMinutes(minutes)
        _sleepTimerMinutes.intValue = minutes
    }

    fun setDownloadLocation(location: DownloadLocation) {
        settingsManager.setDownloadLocation(location)
        _downloadLocation.value = location
    }

    fun setPlaybackSpeed(speed: Float) {
        settingsManager.setPlaybackSpeed(speed)
        _playbackSpeed.value = speed
        applyPlaybackSpeed(speed)
        // The snapshot carries the speed a cold start restores.
        persistSnapshot()
    }

    fun cyclePlaybackSpeed() {
        val speeds = SettingsManager.PLAYBACK_SPEEDS
        val currentIndex = speeds.indexOf(_playbackSpeed.value)
        val nextIndex = if (currentIndex >= 0 && currentIndex < speeds.size - 1) currentIndex + 1 else 0
        val nextSpeed = speeds[nextIndex]
        setPlaybackSpeed(nextSpeed)
    }

    private fun applyPlaybackSpeed(speed: Float) {
        if (mediaController != null) {
            val params = PlaybackParameters(speed)
            mediaController?.setPlaybackParameters(params)
        }
    }

    fun startSleepTimer() {
        PlaybackService.startSleepTimer(_sleepTimerMinutes.intValue)
    }

    fun stopSleepTimer() {
        PlaybackService.stopSleepTimer()
    }

    fun setAutoPlayEpisodeEnabled(enabled: Boolean) {
        settingsManager.setAutoPlayNextEpisodeEnabled(enabled)
    }

    fun setWiFiOnlyDownloadsEnabled(enabled: Boolean) {
        settingsManager.setWiFiOnlyDownloadsEnabled(enabled)
    }


    private fun loadListenHistory() {
        viewModelScope.launch {
            try {
                _listenHistory.value = listenHistoryDao.getAll()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading listen history", e)
            }
        }
    }

    private fun recordListenHistory(episode: Episode) {
        viewModelScope.launch {
            try {
                val entry = ListenHistory(
                    episodeId = episode.id,
                    podcastId = episode.podcastId,
                    podcastTitle = episode.podcastTitle,
                    episodeTitle = episode.title,
                    description = episode.description,
                    publishDate = episode.publishDate,
                    publishDateMillis = episode.publishDateMillis,
                    duration = episode.duration,
                    audioUrl = episode.audioUrl,
                    downloadedPath = episode.downloadedPath,
                    lastListenedAt = System.currentTimeMillis()
                )
                listenHistoryDao.insert(entry)
                _listenHistory.value = listenHistoryDao.getAll()
            } catch (e: Exception) {
                Log.e(TAG, "Error recording listen history", e)
            }
        }
    }

    fun removeFromHistory(episodeId: String) {
        viewModelScope.launch {
            try {
                listenHistoryDao.delete(episodeId)
                _listenHistory.value = listenHistoryDao.getAll()
            } catch (e: Exception) {
                Log.e(TAG, "Error removing from history", e)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            try {
                listenHistoryDao.clearAll()
                _listenHistory.value = emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing history", e)
            }
        }
    }

    // Observed, not loaded once: PlaybackService consumes finished episodes from the queue.
    private fun loadSavedEpisodes() {
        savedEpisodeDao.observeAll()
            .onEach { saved ->
                _savedEpisodes.value = saved
                _savedEpisodeIds.value = saved.map { it.episodeId }.toSet()
            }
            .catch { e -> Log.e(TAG, "Error loading saved episodes", e) }
            .launchIn(viewModelScope)
    }

    fun toggleSaveEpisode(episode: Episode) {
        viewModelScope.launch {
            try {
                if (savedEpisodeDao.isSaved(episode.id)) {
                    savedEpisodeDao.delete(episode.id)
                } else {
                    savedEpisodeDao.insert(
                        SavedEpisode(
                            episodeId = episode.id,
                            podcastId = episode.podcastId,
                            podcastTitle = episode.podcastTitle,
                            episodeTitle = episode.title,
                            description = episode.description,
                            publishDate = episode.publishDate,
                            publishDateMillis = episode.publishDateMillis,
                            duration = episode.duration,
                            audioUrl = episode.audioUrl,
                            downloadedPath = episode.downloadedPath,
                            savedAt = System.currentTimeMillis()
                        )
                    )
                    // Queued means "play this soon": fetch it, waiting for Wi-Fi if the setting says so.
                    val status = _downloads.value.find { it.episode.id == episode.id }?.status
                    if (status != DownloadStatus.DOWNLOADED && status != DownloadStatus.DOWNLOADING) {
                        downloadManager.startAutoDownload(episode)
                    }
                }
                _savedEpisodes.value = savedEpisodeDao.getAll()
                _savedEpisodeIds.value = savedEpisodeDao.getAllIds().toSet()
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling saved episode", e)
            }
        }
    }

    fun removeSavedEpisode(episodeId: String) {
        viewModelScope.launch {
            try {
                savedEpisodeDao.delete(episodeId)
                _savedEpisodes.value = savedEpisodeDao.getAll()
                _savedEpisodeIds.value = savedEpisodeDao.getAllIds().toSet()
            } catch (e: Exception) {
                Log.e(TAG, "Error removing saved episode", e)
            }
        }
    }

    /**
     * Moves a queued episode one slot up or down. Order is savedAt ASC, so a move is
     * a swap of the two rows' savedAt values (no schema migration needed).
     */
    fun moveQueueItem(episodeId: String, up: Boolean) {
        viewModelScope.launch {
            try {
                // Serialize mutations and work from a FRESH db read: the in-memory
                // snapshot can be stale (concurrent remove/consume), and REPLACE-insert
                // from a stale row would resurrect a just-deleted episode.
                queueMutex.withLock {
                    val list = savedEpisodeDao.getAll()
                    val idx = list.indexOfFirst { it.episodeId == episodeId }
                    if (idx == -1) return@withLock
                    val swapIdx = if (up) idx - 1 else idx + 1
                    if (swapIdx !in list.indices) return@withLock
                    val a = list[idx]
                    val b = list[swapIdx]
                    var newASavedAt = b.savedAt
                    var newBSavedAt = a.savedAt
                    if (newASavedAt == newBSavedAt) {
                        // Identical timestamps can't express an order — nudge one key.
                        if (up) newASavedAt -= 1 else newASavedAt += 1
                    }
                    savedEpisodeDao.insert(a.copy(savedAt = newASavedAt))
                    savedEpisodeDao.insert(b.copy(savedAt = newBSavedAt))
                    _savedEpisodes.value = savedEpisodeDao.getAll()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reordering queue", e)
            }
        }
    }

    fun isEpisodeSaved(episodeId: String): Boolean = _savedEpisodeIds.value.contains(episodeId)

    fun markAsPlayed(episode: Episode) {
        viewModelScope.launch {
            val durationSeconds = com.calmcast.podcast.utils.DateTimeFormatter.parseDuration(episode.duration) ?: 0L
            val positionMs = durationSeconds * 1000L
            repository.savePlaybackPosition(episodeId = episode.id, position = positionMs)
            val updatedPosition = PlaybackPosition(
                episodeId = episode.id,
                position = positionMs,
                lastPlayed = System.currentTimeMillis()
            )
            _playbackPositions.value = _playbackPositions.value + (episode.id to updatedPosition)
        }
    }

    fun markAsUnplayed(episode: Episode) {
        viewModelScope.launch {
            repository.savePlaybackPosition(episodeId = episode.id, position = 0L)
            _playbackPositions.value = _playbackPositions.value - episode.id
        }
    }

    fun exportOpml(outputStream: java.io.OutputStream) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val podcasts = subscriptionManager.getSubscriptions()
                OpmlManager.exportToOpml(podcasts, outputStream)
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting OPML", e)
            }
        }
    }

    fun importOpml(uri: Uri, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    OpmlManager.parseOpml(application, uri)
                }
                var importedCount = 0
                val existingFeedUrls = _subscriptions.value
                    .mapNotNull { it.feedUrl?.trim()?.trimEnd('/') }
                    .toHashSet()
                for (entry in entries) {
                    if (entry.feedUrl.isNotBlank()) {
                        // Skip feeds we're already subscribed to (search-added shows have
                        // different ids than RSS-added ones, so compare by feed URL).
                        if (entry.feedUrl.trim().trimEnd('/') in existingFeedUrls) continue
                        try {
                            val result = repository.subscribeToRSSUrl(entry.feedUrl)
                            if (result.isSuccess) {
                                val podcast = result.getOrNull()
                                if (podcast != null && !_subscriptions.value.any { it.id == podcast.id }) {
                                    _subscriptions.value = (_subscriptions.value + podcast).sortedBy { it.title }
                                }
                                importedCount++
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to import ${entry.feedUrl}", e)
                        }
                    }
                }
                onResult(importedCount)
            } catch (e: Exception) {
                Log.e(TAG, "Error importing OPML", e)
                onResult(0)
            }
        }
    }

    fun historyEntryToEpisode(entry: ListenHistory): Episode = Episode(
        id = entry.episodeId,
        podcastId = entry.podcastId,
        podcastTitle = entry.podcastTitle,
        title = entry.episodeTitle,
        description = entry.description,
        publishDate = entry.publishDate,
        publishDateMillis = entry.publishDateMillis,
        duration = entry.duration,
        audioUrl = entry.audioUrl,
        downloadedPath = entry.downloadedPath
    )

    fun savedEpisodeToEpisode(saved: SavedEpisode): Episode = Episode(
        id = saved.episodeId,
        podcastId = saved.podcastId,
        podcastTitle = saved.podcastTitle,
        title = saved.episodeTitle,
        description = saved.description,
        publishDate = saved.publishDate,
        publishDateMillis = saved.publishDateMillis,
        duration = saved.duration,
        audioUrl = saved.audioUrl,
        downloadedPath = saved.downloadedPath
    )

    private fun registerPlaybackServiceErrorCallback() {
        PlaybackService.setErrorCallback { error ->
            com.calmcast.podcast.util.CrashLogger.logError(
                TAG, "Error callback: ${error.errorCodeName}", error
            )
            val message = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                    // Transient network failure (e-ink devices drop Wi-Fi in deep sleep and
                    // take a while to reconnect). Auto-recover instead of dying.
                    startPlaybackRecovery()
                    null
                }
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Server returned an error. Try again later."
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Unsupported or corrupted audio file."
                else -> "Playback error occurred."
            }
            if (message != null) {
                _playbackError.value = PlaybackError.GeneralError(message)
                _isPlaying.value = false
                _isBuffering.value = false
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = application.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true // if we can't tell, let the player try anyway
        }
    }

    /**
     * After a mid-stream network failure, poll for connectivity and re-prepare the player
     * at the last known position. Covers the Mudita's slow Wi-Fi wake (30-90s) after deep
     * sleep. Gives up after ~2 minutes and falls back to a paused, resumable state.
     */
    private fun startPlaybackRecovery() {
        val episode = _currentEpisode.value
        if (episode == null) {
            _playbackError.value = PlaybackError.NetworkError("No internet connection")
            _isPlaying.value = false
            _isBuffering.value = false
            return
        }
        if (recoveryJob?.isActive == true) return

        val resumePos = _currentPosition.longValue
        // Only auto-resume if playback was actually in motion when the error hit
        // (not when a cold paused restore failed to pre-buffer).
        val shouldResume = mediaController?.playWhenReady ?: false
        recoveryJob = viewModelScope.launch {
            _isBuffering.value = true
            com.calmcast.podcast.util.CrashLogger.log(TAG, "recovery: started at ${resumePos}ms resume=$shouldResume")
            repeat(24) { attempt ->
                delay(5000)
                if (!isNetworkAvailable()) {
                    com.calmcast.podcast.util.CrashLogger.log(TAG, "recovery: no network yet (attempt ${attempt + 1})")
                    return@repeat
                }
                com.calmcast.podcast.util.CrashLogger.log(TAG, "recovery: network up, re-preparing (attempt ${attempt + 1})")
                preparePlayerForRestoredItem(episode, resumePos)
                if (shouldResume) {
                    mediaController?.play()
                }

                // Wait up to 15s for the player to reach READY (or fail back to IDLE)
                val deadline = System.currentTimeMillis() + 15000
                while (System.currentTimeMillis() < deadline) {
                    delay(500)
                    when (mediaController?.playbackState) {
                        Player.STATE_READY -> {
                            com.calmcast.podcast.util.CrashLogger.log(TAG, "recovery: success")
                            _playbackError.value = null
                            _isBuffering.value = false
                            return@launch
                        }
                        Player.STATE_IDLE -> {
                            com.calmcast.podcast.util.CrashLogger.log(TAG, "recovery: attempt failed (back to IDLE)")
                            return@repeat // errored again; next outer attempt
                        }
                        else -> { /* still buffering, keep waiting */ }
                    }
                }
            }
            com.calmcast.podcast.util.CrashLogger.log(TAG, "recovery: gave up after ~2 min")
            _isBuffering.value = false
            _isPlaying.value = false
            _playbackError.value = PlaybackError.NetworkError("No internet connection")
        }
    }

    override fun onCleared() {
        super.onCleared()
        mediaController?.removeListener(playerListener)
        PlaybackService.setErrorCallback(null)
        recoveryJob?.cancel()
        // No final save here: PlaybackService keeps saving the position, and the sleep
        // timer keeps running, after this screen is gone.
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        positionUpdateJob?.cancel()
    }
}
/** Per-podcast disk usage of completed downloads, for the Settings storage manager. */
data class PodcastStorageUsage(
    val podcastId: String,
    val podcastTitle: String,
    val episodeCount: Int,
    val totalBytes: Long
)
