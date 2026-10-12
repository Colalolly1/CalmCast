package com.calmcast.podcast.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.calmcast.podcast.CalmCastApplication
import com.calmcast.podcast.data.download.DownloadOutcome

/**
 * Downloads one episode. Enqueued as unique work per episode by AndroidDownloadManager,
 * so a job keeps going after the app's screen goes away and never runs twice at once.
 * If the system stops it, or the signal drops, the next attempt resumes the partial file.
 */
class EpisodeDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val episodeId = inputData.getString(KEY_EPISODE_ID) ?: return Result.failure()
        val manager = (applicationContext as CalmCastApplication).downloadManager
        val finalAttempt = runAttemptCount >= MAX_ATTEMPTS - 1
        return when (manager.runDownload(episodeId, finalAttempt)) {
            DownloadOutcome.DONE -> Result.success()
            DownloadOutcome.RETRY -> Result.retry()
            DownloadOutcome.FAILED, DownloadOutcome.STOPPED -> Result.failure()
        }
    }

    companion object {
        const val KEY_EPISODE_ID = "episode_id"
        // With the 30s exponential backoff this keeps trying for about four hours.
        private const val MAX_ATTEMPTS = 10
    }
}
