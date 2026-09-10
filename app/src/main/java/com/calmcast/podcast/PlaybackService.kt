package com.calmcast.podcast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.calmcast.podcast.util.CrashLogger

sealed class PlaybackError {
    data class NetworkError(val message: String) : PlaybackError()
    data class GeneralError(val message: String) : PlaybackError()
}

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    companion object {
        private const val NOTIFICATION_ID = 123
        private const val CHANNEL_ID = "playback_channel"
        private const val TAG = "PlaybackService"

        private var errorCallback: ((PlaybackException) -> Unit)? = null
        private var playerInstance: ExoPlayer? = null

        fun setErrorCallback(callback: ((PlaybackException) -> Unit)?) {
            errorCallback = callback
        }

        @androidx.annotation.OptIn(UnstableApi::class)
        fun setSkipSilenceEnabled(enabled: Boolean) {
            playerInstance?.skipSilenceEnabled = enabled
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

        // Apply skip silences setting
        val settings = com.calmcast.podcast.data.SettingsManager(this)
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

        // Hold a Wi-Fi + partial wake lock while playing so streaming doesn't stall
        // when the screen turns off and the device enters deep sleep (common cause of
        // playback cutting out a few minutes / ~30 min after screen-off on Wi-Fi).
        player.setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)

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

        // Wrap player so headphone double-tap (KEYCODE_MEDIA_NEXT) and triple-tap
        // (KEYCODE_MEDIA_PREVIOUS) seek by the user's skipSeconds setting instead
        // of jumping to next/previous track. Auto-play-next-episode (STATE_ENDED)
        // is handled separately in PodcastViewModel and unaffected.
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

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        playerInstance = null
        super.onDestroy()
    }
}