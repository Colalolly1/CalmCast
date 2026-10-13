package com.calmcast.podcast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.calmcast.podcast.data.ListenHistory
import com.calmcast.podcast.data.NowPlayingStorage
import com.calmcast.podcast.data.PlaybackPosition
import com.calmcast.podcast.data.Podcast
import com.calmcast.podcast.data.PodcastDatabase
import com.calmcast.podcast.data.PodcastRepository.Episode
import com.calmcast.podcast.data.PodcastSnapshot
import com.calmcast.podcast.data.SavedEpisode
import com.calmcast.podcast.data.SettingsManager
import com.calmcast.podcast.data.download.DownloadStatus
import com.calmcast.podcast.util.CrashLogger
import com.calmcast.podcast.utils.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class PlaybackError {
    data class NetworkError(val message: String) : PlaybackError()
    data class GeneralError(val message: String) : PlaybackError()
}

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var audioManager: android.media.AudioManager? = null
    private var deviceCallback: android.media.AudioDeviceCallback? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var settings: SettingsManager
    private lateinit var database: PodcastDatabase
    private lateinit var nowPlayingStorage: NowPlayingStorage
    private var queue: List<SavedEpisode> = emptyList()
    private var positionSaveJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var lastQueueAdvanceAtMs = 0L
    private var rapidQueueAdvances = 0

    companion object {
        private const val NOTIFICATION_ID = 123
        private const val CHANNEL_ID = "playback_channel"
        private const val TAG = "PlaybackService"
        private const val POSITION_SAVE_INTERVAL_MS = 10_000L

        private var errorCallback: ((PlaybackException) -> Unit)? = null
        private var playerInstance: ExoPlayer? = null
        private var instance: PlaybackService? = null

        // Outlives the service, so the last position write still lands after onDestroy.
        private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val _sleepTimerRemainingSeconds = MutableStateFlow(0L)
        /** Seconds left on the sleep timer, or 0 when it isn't running. */
        val sleepTimerRemainingSeconds: StateFlow<Long> = _sleepTimerRemainingSeconds.asStateFlow()

        /** The episode whose end the service answered by playing the next one in the queue. */
        @Volatile
        var queueAdvancedFrom: String? = null
            private set

        fun setErrorCallback(callback: ((PlaybackException) -> Unit)?) {
            errorCallback = callback
        }

        @androidx.annotation.OptIn(UnstableApi::class)
        fun setSkipSilenceEnabled(enabled: Boolean) {
            playerInstance?.skipSilenceEnabled = enabled
        }

        fun startSleepTimer(minutes: Int) {
            instance?.runSleepTimer(minutes)
        }

        fun stopSleepTimer() {
            instance?.cancelSleepTimer()
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Retry transient load failures ~8 times with backoff before surfacing an error,
        // so brief Wi-Fi drops (e-ink deep sleep) self-heal without killing playback.
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setLoadErrorHandlingPolicy(
                androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(8)
            )

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        playerInstance = player
        exoPlayer = player
        instance = this

        settings = SettingsManager(this)
        database = PodcastDatabase.getDatabase(this)
        nowPlayingStorage = (application as CalmCastApplication).nowPlayingStorage

        // A fresh service has no timer running, whatever the saved setting says.
        finishSleepTimer()

        // Apply skip silences setting
        player.skipSilenceEnabled = settings.isSkipSilencesEnabledSync()

        // Configure audio attributes for podcast content
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        // Set audioAttributes with handleAudioFocus=true to let ExoPlayer manage audio focus
        player.setAudioAttributes(audioAttributes, true)

        // Handle headphone unplug and audio becoming noisy
        player.setHandleAudioBecomingNoisy(true)

        // ACTION_AUDIO_BECOMING_NOISY, which the line above listens for, is only
        // broadcast for the wired jack on some ROMs — Bluetooth earbuds could
        // disconnect and leave the episode playing out of the phone's speaker.
        // Watching the audio devices directly is what actually catches that.
        registerOutputDeviceWatcher(player)

        // Hold a Wi-Fi + partial wake lock while playing so streaming doesn't stall
        // when the screen turns off and the device enters deep sleep (common cause of
        // playback cutting out a few minutes / ~30 min after screen-off on Wi-Fi).
        player.setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
        // A network wake mode also holds a Wi-Fi lock, which keeps the radio at
        // full power for the whole episode. That is only worth paying for a
        // stream; a downloaded file needs the CPU awake and nothing more.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val scheme = mediaItem?.localConfiguration?.uri?.scheme?.lowercase()
                val streaming = scheme == "http" || scheme == "https"
                player.setWakeMode(
                    if (streaming) androidx.media3.common.C.WAKE_MODE_NETWORK
                    else androidx.media3.common.C.WAKE_MODE_LOCAL
                )
            }
        })

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                com.calmcast.podcast.util.CrashLogger.logError(
                    "PlaybackService",
                    "Player error: ${error.errorCodeName} (code ${error.errorCode})",
                    error
                )
                errorCallback?.invoke(error)
                super.onPlayerError(error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val name = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                CrashLogger.log(
                    "PlaybackService",
                    "state=$name playWhenReady=${player.playWhenReady} pos=${player.currentPosition}ms dur=${player.duration}ms"
                )
            }
        })

        // Saving lives here so a pause from the earbuds or notification is kept with no screen open.
        // Added before the session is built, so it runs before any controller hears of an event.
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startPositionSaving()
                } else {
                    positionSaveJob?.cancel()
                    if (player.playbackState != Player.STATE_ENDED) saveCurrentPosition()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                when (reason) {
                    Player.DISCONTINUITY_REASON_SEEK ->
                        savePosition(newPosition.mediaItem?.mediaId, newPosition.positionMs)
                    // Another episode is replacing this one: keep where it was left.
                    Player.DISCONTINUITY_REASON_REMOVE ->
                        savePosition(oldPosition.mediaItem?.mediaId, oldPosition.positionMs)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEpisodeEnded(player)
            }
        })

        serviceScope.launch {
            database.savedEpisodeDao().observeAll().collect { queue = it }
        }

        // Wrap player so headphone double-tap (KEYCODE_MEDIA_NEXT) and triple-tap
        // (KEYCODE_MEDIA_PREVIOUS) seek by the user's skipSeconds setting instead
        // of jumping to next/previous track. Moving on at STATE_ENDED (queue here,
        // autoplay in PodcastViewModel) is handled separately and unaffected.
        val skipForwardingPlayer = object : ForwardingPlayer(player) {
            private fun skipMs(): Long = settings.getSkipSecondsSync() * 1000L

            override fun seekToNext() {
                val dur = duration
                val newPos = if (dur > 0) {
                    (currentPosition + skipMs()).coerceAtMost(dur)
                } else {
                    currentPosition + skipMs()
                }
                seekTo(newPos)
            }

            override fun seekToNextMediaItem() {
                seekToNext()
            }

            override fun seekToPrevious() {
                val newPos = (currentPosition - skipMs()).coerceAtLeast(0L)
                seekTo(newPos)
            }

            override fun seekToPreviousMediaItem() {
                seekToPrevious()
            }

            override fun hasNextMediaItem(): Boolean = true
            override fun hasPreviousMediaItem(): Boolean = true

            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands()
                    .buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                if (command == Player.COMMAND_SEEK_TO_NEXT ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                ) return true
                return super.isCommandAvailable(command)
            }
        }

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, skipForwardingPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build()

        setMediaNotificationProvider(notificationProvider)
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Playback"
            val descriptionText = "Podcast playback controls"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.let { player ->
            if (!player.playWhenReady || player.mediaItemCount == 0) {
                stopSelf()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    private fun startPositionSaving() {
        positionSaveJob?.cancel()
        positionSaveJob = serviceScope.launch {
            while (true) {
                delay(POSITION_SAVE_INTERVAL_MS)
                saveCurrentPosition()
            }
        }
    }

    private fun saveCurrentPosition() {
        savePosition(exoPlayer.currentMediaItem?.mediaId, exoPlayer.currentPosition)
    }

    /** The media item id is the episode id. */
    private fun savePosition(episodeId: String?, positionMs: Long) {
        if (episodeId.isNullOrEmpty() || positionMs <= 0) return
        nowPlayingStorage.savePosition(episodeId, positionMs)
        val dao = database.playbackPositionDao()
        saveScope.launch {
            try {
                dao.insert(PlaybackPosition(episodeId, positionMs, System.currentTimeMillis()))
            } catch (e: Exception) {
                CrashLogger.logError(TAG, "Could not save position for $episodeId", e)
            }
        }
    }

    private fun onEpisodeEnded(player: ExoPlayer) {
        val endedId = player.currentMediaItem?.mediaId
        // Store the full length so the episode reads as played. The larger of the real and
        // the feed's length, so a later play judges it finished and doesn't resume at the end.
        val statedMs = DateTimeFormatter.parseDuration(
            player.currentMediaItem?.mediaMetadata?.extras?.getString("duration")
        )?.times(1000L) ?: 0L
        savePosition(endedId, maxOf(player.duration, statedMs, player.currentPosition))
        if (endedId != null) com.calmcast.podcast.data.FinishedEpisodes.mark(this, endedId)

        // The queue outranks implicit autoplay: queueing an episode is an explicit
        // "play this next" request, so advance regardless of the autoplay setting.
        queueAdvancedFrom = null
        val next = queue.firstOrNull { it.episodeId != endedId }
        if (endedId != null && queue.any { it.episodeId == endedId }) {
            // Finished a queued episode: consume it from the queue.
            val dao = database.savedEpisodeDao()
            saveScope.launch { dao.delete(endedId) }
        }
        if (next == null) return

        // Cascade breaker: if episodes are "finishing" instantly (bad stored position,
        // dead audio), stop advancing instead of racing through, and consuming, the queue.
        val now = System.currentTimeMillis()
        rapidQueueAdvances = if (now - lastQueueAdvanceAtMs < 5000) rapidQueueAdvances + 1 else 0
        lastQueueAdvanceAtMs = now
        if (rapidQueueAdvances >= 3) {
            CrashLogger.log(TAG, "Queue: halting auto-advance after $rapidQueueAdvances instant-end cycles")
            return
        }
        queueAdvancedFrom = endedId
        serviceScope.launch { playQueued(next) }
    }

    private suspend fun playQueued(saved: SavedEpisode) {
        val prepared = try {
            withContext(Dispatchers.IO) { prepareQueuedItem(saved) }
        } catch (e: Exception) {
            CrashLogger.logError(TAG, "Queue: could not prepare ${saved.episodeTitle.take(40)}", e)
            return
        }
        // Someone picked another episode while we were looking this one up.
        if (exoPlayer.playbackState != Player.STATE_ENDED) return

        CrashLogger.log(TAG, "Queue: advancing to ${saved.episodeTitle.take(40)} at ${prepared.startMs}ms")
        exoPlayer.setMediaItem(prepared.item, prepared.startMs)
        exoPlayer.prepare()
        exoPlayer.play()

        val speed = exoPlayer.playbackParameters.speed
        withContext(Dispatchers.IO) {
            try {
                nowPlayingStorage.save(
                    PodcastSnapshot(
                        episode = prepared.episode,
                        contextType = nowPlayingStorage.load()?.contextType ?: "PODCAST",
                        positionMs = prepared.startMs,
                        playbackSpeed = speed,
                        artworkUrl = prepared.artworkUrl
                    )
                )
                database.listenHistoryDao().insert(
                    ListenHistory(
                        episodeId = saved.episodeId,
                        podcastId = saved.podcastId,
                        podcastTitle = saved.podcastTitle,
                        episodeTitle = saved.episodeTitle,
                        description = saved.description,
                        publishDate = saved.publishDate,
                        publishDateMillis = saved.publishDateMillis,
                        duration = saved.duration,
                        audioUrl = saved.audioUrl,
                        downloadedPath = saved.downloadedPath,
                        lastListenedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                CrashLogger.logError(TAG, "Queue: could not record ${saved.episodeTitle.take(40)}", e)
            }
        }
    }

    private class QueuedItem(
        val item: MediaItem,
        val startMs: Long,
        val episode: Episode,
        val artworkUrl: String?
    )

    // Builds the same kind of media item PodcastViewModel.playEpisode does.
    private suspend fun prepareQueuedItem(saved: SavedEpisode): QueuedItem {
        val download = database.downloadDao().getByEpisodeId(saved.episodeId)
        val uri = download?.takeIf { it.status == DownloadStatus.DOWNLOADED }?.downloadUri
            ?: saved.downloadedPath
            ?: saved.audioUrl

        // Start over if it was finished, or if there's no known length to judge that by.
        val stored = database.playbackPositionDao().getPlaybackPosition(saved.episodeId)?.position ?: 0L
        val durationMs = DateTimeFormatter.parseDuration(saved.duration)?.times(1000L) ?: 0L
        val startMs = if (stored > 0 && durationMs > 0 && stored.toDouble() / durationMs <= 0.99) stored else 0L

        val podcast: Podcast? = try {
            database.podcastDao().getPodcast(saved.podcastId)
        } catch (e: Exception) {
            null
        }
        val artworkUrl = podcast?.imageUrl
        val podcastTitle = podcast?.title ?: saved.podcastTitle

        val metadata = MediaMetadata.Builder()
            .setTitle(saved.episodeTitle)
            .setArtist(podcastTitle)
            .setArtworkUri(artworkUrl?.let { Uri.parse(it) })
            .setExtras(Bundle().apply {
                putString("episodeId", saved.episodeId)
                putString("podcastId", saved.podcastId)
                putString("podcastTitle", podcastTitle)
                putString("description", saved.description)
                putString("publishDate", saved.publishDate)
                putLong("publishDateMillis", saved.publishDateMillis)
                putString("duration", saved.duration)
                putString("audioUrl", saved.audioUrl)
                putString("downloadedPath", saved.downloadedPath)
            })
            .build()

        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(saved.episodeId)
            .setMediaMetadata(metadata)
            .build()

        val episode = Episode(
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
        return QueuedItem(item, startMs, episode, artworkUrl)
    }

    private fun runSleepTimer(minutes: Int) {
        if (minutes <= 0) return
        sleepTimerJob?.cancel()
        settings.setSleepTimerActive(true)
        // Measured against the clock, so a late tick in deep sleep can't stretch the timer.
        val endsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        _sleepTimerRemainingSeconds.value = minutes * 60L
        sleepTimerJob = serviceScope.launch {
            while (true) {
                val leftMs = endsAt - SystemClock.elapsedRealtime()
                if (leftMs <= 0) break
                _sleepTimerRemainingSeconds.value = (leftMs + 999) / 1000
                // The player only shows whole minutes: wake as each one runs out, and at the exact end.
                delay((leftMs - 1) % 60_000L + 1)
            }
            exoPlayer.pause()
            finishSleepTimer()
        }
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        finishSleepTimer()
    }

    // Clears the countdown and the saved "active" flag, so the player never shows a stuck 00:00.
    private fun finishSleepTimer() {
        _sleepTimerRemainingSeconds.value = 0L
        settings.setSleepTimerActive(false)
    }

    /**
     * Pause when the thing you were listening through goes away: Bluetooth
     * (A2DP or headset), the wired jack, or a USB audio device.
     */
    private fun registerOutputDeviceWatcher(player: ExoPlayer) {
        val manager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        val personalOutputs = setOf(
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
        )
        val callback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) {
                val lostListening = removed?.any { it.isSink && it.type in personalOutputs } == true
                if (!lostListening) return
                // The callback can arrive on a binder thread; touch the player on its own.
                android.os.Handler(mainLooper).post {
                    // playWhenReady, not isPlaying: also catches the moment it is buffering.
                    if (player.playWhenReady) player.pause()
                }
            }
        }
        manager.registerAudioDeviceCallback(callback, android.os.Handler(mainLooper))
        audioManager = manager
        deviceCallback = callback
    }

    override fun onDestroy() {
        saveCurrentPosition()
        positionSaveJob?.cancel()
        sleepTimerJob?.cancel()
        finishSleepTimer()
        serviceScope.cancel()
        if (instance === this) instance = null
        deviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        audioManager = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        playerInstance = null
        super.onDestroy()
    }
}