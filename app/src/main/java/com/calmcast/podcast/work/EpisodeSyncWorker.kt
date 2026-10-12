package com.calmcast.podcast.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.calmcast.podcast.CalmCastApplication
import com.calmcast.podcast.api.ItunesApiService
import com.calmcast.podcast.data.PodcastDatabase
import com.calmcast.podcast.data.PodcastRepository
import com.calmcast.podcast.data.SettingsManager
import com.calmcast.podcast.data.download.DownloadStatus
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class EpisodeSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Background sync starting")
            val app = applicationContext as CalmCastApplication
            val database = PodcastDatabase.getDatabase(applicationContext)
            val settingsManager = SettingsManager(applicationContext)
            val podcastDao = database.podcastDao()
            val downloadDao = database.downloadDao()
            val repository = PodcastRepository(
                apiService = ItunesApiService(),
                subscriptionManager = app.subscriptionManager,
                podcastDao = podcastDao,
                playbackPositionDao = database.playbackPositionDao()
            )

            repository.invalidateAllEpisodeCache()
            val subscriptions = app.subscriptionManager.getSubscriptions()
            Log.d(TAG, "Syncing ${subscriptions.size} podcasts")

            val autoDownload = settingsManager.isAutoDownloadEnabled()

            for (podcast in subscriptions) {
                try {
                    val result = repository.getPodcastDetails(podcast.id).first()
                    val episodes = result.getOrNull()?.episodes ?: continue

                    val lastSeenEpisode = episodes.find { it.id == podcast.lastSeenEpisodeId }
                    val newEpisodeIndex = if (lastSeenEpisode != null) {
                        episodes.indexOf(lastSeenEpisode)
                    } else {
                        0
                    }

                    if (newEpisodeIndex > 0) {
                        podcastDao.updateNewEpisodeCount(podcast.id, newEpisodeIndex)

                        if (autoDownload) {
                            val newEpisodes = episodes.slice(0 until newEpisodeIndex)
                            for (episode in newEpisodes) {
                                // Look up by the real episode id — the previous hashCode-based
                                // lookup never matched, so completed downloads were re-fetched
                                // (and could even be deleted by the stale-resume path).
                                val existing = downloadDao.getByEpisodeId(episode.id)
                                val status = existing?.status
                                // Paused or failed still need downloading. This only queues the
                                // download job; a job already pending for the episode is left alone.
                                if (status != DownloadStatus.DELETED &&
                                    status != DownloadStatus.DOWNLOADED) {
                                    Log.d(TAG, "Auto-downloading: ${episode.title}")
                                    app.downloadManager.enqueueDownload(episode, userInitiated = false)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync ${podcast.title}", e)
                }
            }

            Log.d(TAG, "Background sync complete")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Background sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EpisodeSyncWorker"
        private const val WORK_NAME = "calmcast_episode_sync"

        fun schedule(context: Context, wifiOnly: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<EpisodeSyncWorker>(
                repeatInterval = 6,
                repeatIntervalTimeUnit = TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.d(TAG, "Scheduled periodic sync (wifiOnly=$wifiOnly)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled periodic sync")
        }
    }
}
