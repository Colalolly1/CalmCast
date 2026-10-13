package com.calmcast.podcast.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.calmcast.podcast.data.PodcastRepository.Episode
import com.calmcast.podcast.data.SettingsManager
import com.calmcast.podcast.util.NetworkUtil
import com.calmcast.podcast.work.EpisodeDownloadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** How one download attempt ended; EpisodeDownloadWorker turns this into a WorkManager result. */
enum class DownloadOutcome { DONE, RETRY, FAILED, STOPPED }

/**
 * Each download runs as a unique WorkManager job per episode (see EpisodeDownloadWorker),
 * so it survives the app's screen going away and retries with backoff on patchy signal.
 * This class enqueues those jobs and does the actual transfer when a job runs.
 */
class AndroidDownloadManager(
    private val context: Context,
    private val client: OkHttpClient,
    private val downloadDao: DownloadDao,
    private val settingsManager: SettingsManager
) : DownloadManager {

    companion object {
        private const val TAG = "AndroidDownloadManager"
        // "bytes 1000-1999/2000"; the total may be "*".
        private val CONTENT_RANGE = Regex("""bytes (\d+)-\d+/(\d+|\*)""")

        fun workName(episodeId: String) = "download_$episodeId"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workManager by lazy { WorkManager.getInstance(context) }
    // ETag or Last-Modified of the response each partial file came from, by episode id.
    private val validators by lazy {
        context.getSharedPreferences("download_validators", Context.MODE_PRIVATE)
    }

    private val activeCalls = ConcurrentHashMap<String, Call>()
    // Episodes the user paused or removed, so a job that is unwinding doesn't treat it as a network drop.
    private val stopRequested: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // One writer per episode file: a replaced or stopped job finishes before the next one starts.
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(episodeId: String): Mutex = fileLocks.getOrPut(episodeId) { Mutex() }

    override val downloads: Flow<List<Download>> = downloadDao.getAll()

    override fun startDownload(episode: Episode): Long {
        scope.launch { enqueueDownload(episode, userInitiated = true) }
        return episode.audioUrl.hashCode().toLong()
    }

    override fun startAutoDownload(episode: Episode) {
        scope.launch { enqueueDownload(episode, userInitiated = false) }
    }

    /**
     * A tap runs on any network and replaces whatever job was waiting. Automatic downloads
     * wait for Wi-Fi when the Wi-Fi-only setting is on, and leave a pending job alone.
     */
    suspend fun enqueueDownload(episode: Episode, userInitiated: Boolean): Unit = withContext(Dispatchers.IO) {
        try {
            if (!userInitiated && hasPendingJob(episode.id)) return@withContext
            stopRequested.remove(episode.id)

            val wifiOnly = !userInitiated && settingsManager.isWiFiOnlyDownloadsEnabledSync()
            // The same test as the job's UNMETERED constraint below.
            val canStartNow = !wifiOnly || NetworkUtil.isOnUnmeteredNetwork(context)
            val existing = downloadDao.getByEpisodeId(episode.id)
            downloadDao.insert(
                Download(
                    id = episode.id,
                    episode = episode,
                    // Waiting for Wi-Fi shows as paused; the job flips it to downloading when it starts.
                    status = if (canStartNow) DownloadStatus.DOWNLOADING else DownloadStatus.PAUSED,
                    progress = existing?.progress?.coerceAtLeast(0f) ?: 0f,
                    downloadedBytes = existing?.downloadedBytes ?: 0L,
                    totalBytes = existing?.totalBytes ?: -1L
                )
            )

            val request = OneTimeWorkRequestBuilder<EpisodeDownloadWorker>()
                .setInputData(workDataOf(EpisodeDownloadWorker.KEY_EPISODE_ID to episode.id))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(
                workName(episode.id),
                if (userInitiated) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            ).result.get()
        } catch (e: Exception) {
            Log.e(TAG, "Could not queue download for ${episode.title}", e)
        }
        Unit
    }

    private fun hasPendingJob(episodeId: String): Boolean =
        workManager.getWorkInfosForUniqueWork(workName(episodeId)).get().any { !it.state.isFinished }

    /**
     * Rows left DOWNLOADING by a process that was killed have no job behind them any more.
     * Called on app start so they show as paused (resumable) instead of spinning forever.
     */
    suspend fun pauseOrphanedDownloads(): Unit = withContext(Dispatchers.IO) {
        val stuck = downloadDao.getAll().first().filter { it.status == DownloadStatus.DOWNLOADING }
        for (download in stuck) {
            try {
                if (hasPendingJob(download.id)) continue
                lockFor(download.id).withLock {
                    val current = downloadDao.getByEpisodeId(download.id) ?: return@withLock
                    if (current.status == DownloadStatus.DOWNLOADING) {
                        downloadDao.insert(current.copy(status = DownloadStatus.PAUSED))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not check download ${download.id}: ${e.message}")
            }
        }
    }

    /** Called by EpisodeDownloadWorker. Resumes from any partial file already on disk. */
    suspend fun runDownload(episodeId: String, finalAttempt: Boolean): DownloadOutcome =
        withContext(Dispatchers.IO) {
            lockFor(episodeId).withLock { downloadLocked(episodeId, finalAttempt) }
        }

    private suspend fun downloadLocked(episodeId: String, finalAttempt: Boolean): DownloadOutcome {
        val row = downloadDao.getByEpisodeId(episodeId) ?: return DownloadOutcome.DONE
        // Removed by the user before this job got to run.
        if (row.status == DownloadStatus.DELETED || row.status == DownloadStatus.CANCELED) {
            return DownloadOutcome.DONE
        }
        val episode = row.episode
        if (row.status == DownloadStatus.DOWNLOADED && fileExists(row.downloadUri)) {
            return DownloadOutcome.DONE
        }

        val baseDir = try {
            StorageManager.getDownloadBaseDir(context, settingsManager)
        } catch (e: StorageUnavailableException) {
            Log.w(TAG, "External storage unavailable for episode: ${episode.title}")
            downloadDao.insert(row.copy(status = DownloadStatus.STORAGE_UNAVAILABLE, progress = 0f))
            return DownloadOutcome.FAILED
        }
        val file = File(baseDir, "${episode.id}.mp3")
        val bytesAlready = if (file.exists()) file.length() else 0L
        downloadDao.insert(row.copy(status = DownloadStatus.DOWNLOADING, downloadUri = null))

        var call: Call? = null
        val outcome = try {
            val requestBuilder = Request.Builder().url(episode.audioUrl)
            if (bytesAlready > 0) {
                requestBuilder.addHeader("Range", "bytes=$bytesAlready-")
                // If the file has changed since, the server sends all of it and we start over.
                validators.getString(episodeId, null)?.let { requestBuilder.addHeader("If-Range", it) }
            }
            val newCall = client.newCall(requestBuilder.build())
            call = newCall
            activeCalls[episodeId] = newCall
            coroutineScope {
                // A blocked socket read only notices the job being stopped once the call is cancelled.
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        newCall.cancel()
                    }
                }
                try {
                    fetch(newCall, row, file, bytesAlready)
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: IOException) {
            // Signal dropped or the call was cancelled. The partial file stays for the next attempt.
            Log.w(TAG, "Download interrupted for ${episode.title}: ${e.message}")
            val jobStopped = !currentCoroutineContext().isActive
            saveProgress(episodeId, file, systemStopped = jobStopped && !stopRequested.contains(episodeId))
            currentCoroutineContext().ensureActive()
            if (stopRequested.contains(episodeId)) DownloadOutcome.STOPPED else DownloadOutcome.RETRY
        } catch (e: CancellationException) {
            saveProgress(episodeId, file, systemStopped = !stopRequested.contains(episodeId))
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Exception during download for episode ${episode.title}: ${e.message}", e)
            DownloadOutcome.FAILED
        } finally {
            call?.let { activeCalls.remove(episodeId, it) }
        }

        if (outcome == DownloadOutcome.FAILED || (outcome == DownloadOutcome.RETRY && finalAttempt)) {
            val current = downloadDao.getByEpisodeId(episodeId)
            if (current != null && current.status == DownloadStatus.DOWNLOADING) {
                downloadDao.insert(current.copy(status = DownloadStatus.FAILED, progress = 0f))
            }
            return DownloadOutcome.FAILED
        }
        return outcome
    }

    private suspend fun fetch(call: Call, row: Download, file: File, bytesAlready: Long): DownloadOutcome {
        call.execute().use { response ->
            if (response.code == 416) {
                // The partial no longer lines up with the server's file; start over next attempt.
                Log.w(TAG, "Range not satisfiable for episode: ${row.episode.title}. Retrying from scratch.")
                file.delete()
                forgetValidator(row.id)
                return DownloadOutcome.RETRY
            }
            if (!response.isSuccessful) {
                Log.w(TAG, "Download failed with status ${response.code} for episode: ${row.episode.title}")
                val temporary = response.code >= 500 || response.code == 408 || response.code == 429
                return if (temporary) DownloadOutcome.RETRY else DownloadOutcome.FAILED
            }
            val body = response.body ?: return DownloadOutcome.RETRY

            val isResume = response.code == 206
            if (isResume && !continuesPartial(response, row.totalBytes, bytesAlready, body.contentLength())) {
                // A different version of the file: joining them would make one broken episode.
                Log.w(TAG, "Server's file changed for episode: ${row.episode.title}. Retrying from scratch.")
                file.delete()
                forgetValidator(row.id)
                return DownloadOutcome.RETRY
            }
            // A full response starts the file afresh; remember which version it is.
            if (!isResume) rememberValidator(row.id, response)
            val startBytes = if (isResume) bytesAlready else 0L
            val serverBytes = body.contentLength()
            val totalBytes = if (serverBytes != -1L) startBytes + serverBytes else -1L
            var bytesCopied = startBytes

            val download = row.copy(status = DownloadStatus.DOWNLOADING, downloadUri = null)
            downloadDao.insert(download.copy(
                progress = fraction(bytesCopied, totalBytes),
                downloadedBytes = bytesCopied,
                totalBytes = totalBytes
            ))

            body.byteStream().use { input ->
                FileOutputStream(file, isResume).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = input.read(buffer)
                    var lastDbUpdateTime = System.currentTimeMillis()

                    while (read >= 0) {
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, read)
                        bytesCopied += read

                        // Each insert makes Room re-emit the full download list and
                        // recompose observers — 5s is plenty for an e-ink progress bar.
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastDbUpdateTime >= 5000) {
                            downloadDao.insert(download.copy(
                                progress = fraction(bytesCopied, totalBytes),
                                downloadedBytes = bytesCopied,
                                totalBytes = totalBytes
                            ))
                            lastDbUpdateTime = currentTime
                        }
                        read = input.read(buffer)
                    }
                }
            }

            if (totalBytes != -1L && bytesCopied < totalBytes) {
                Log.w(TAG, "Download incomplete for ${row.episode.title}: $bytesCopied / $totalBytes bytes")
                downloadDao.insert(download.copy(
                    progress = fraction(bytesCopied, totalBytes),
                    downloadedBytes = bytesCopied,
                    totalBytes = totalBytes
                ))
                return DownloadOutcome.RETRY
            }

            downloadDao.insert(download.copy(
                status = DownloadStatus.DOWNLOADED,
                progress = 1f,
                downloadUri = Uri.fromFile(file).toString(),
                downloadedBytes = bytesCopied,
                totalBytes = totalBytes
            ))
            return DownloadOutcome.DONE
        }
    }

    // A resumed response must start where the partial ends, for a file of the size first reported.
    private fun continuesPartial(response: Response, storedTotal: Long, bytesAlready: Long, bodyBytes: Long): Boolean {
        val match = response.header("Content-Range")?.let { CONTENT_RANGE.find(it) }
        val start = match?.groupValues?.get(1)?.toLongOrNull()
        if (start != null && start != bytesAlready) return false
        val serverTotal = match?.groupValues?.get(2)?.toLongOrNull()
            ?: if (bodyBytes != -1L) bytesAlready + bodyBytes else -1L
        return storedTotal <= 0 || serverTotal <= 0 || serverTotal == storedTotal
    }

    private fun rememberValidator(episodeId: String, response: Response) {
        // A weak ETag never matches an If-Range, so prefer Last-Modified over one.
        val etag = response.header("ETag")
        val validator = etag?.takeUnless { it.startsWith("W/") } ?: response.header("Last-Modified") ?: etag
        val editor = validators.edit()
        if (validator != null) editor.putString(episodeId, validator) else editor.remove(episodeId)
        editor.commit()
    }

    private fun forgetValidator(episodeId: String) {
        validators.edit().remove(episodeId).commit()
    }

    private fun fraction(bytes: Long, total: Long): Float =
        if (total > 0) bytes.toFloat() / total.toFloat() else -1f

    private fun fileExists(downloadUri: String?): Boolean {
        val path = downloadUri?.let { Uri.parse(it).path } ?: return false
        return File(path).exists()
    }

    // Records how far a stopped download got, leaving the status to whoever stopped it. When the
    // system stopped the job (Wi-Fi lost, say), WorkManager runs it again later: show it paused.
    private suspend fun saveProgress(
        episodeId: String,
        file: File,
        systemStopped: Boolean = false
    ): Unit = withContext(NonCancellable) {
        val current = downloadDao.getByEpisodeId(episodeId) ?: return@withContext
        if (current.status != DownloadStatus.DOWNLOADING) return@withContext
        val bytes = if (file.exists()) file.length() else 0L
        downloadDao.insert(current.copy(
            status = if (systemStopped) DownloadStatus.PAUSED else current.status,
            progress = fraction(bytes, current.totalBytes).coerceAtLeast(0f),
            downloadedBytes = bytes
        ))
    }

    override fun cancelDownload(id: Long) {
        val episodeId = activeCalls.keys.find { it.hashCode().toLong() == id }
        if (episodeId != null) {
            cancelDownload(episodeId)
        }
    }

    override fun cancelDownload(episodeId: String) {
        stopJob(episodeId)
        scope.launch {
            // Wait for a running job to close the file before deleting it.
            lockFor(episodeId).withLock {
                val download = downloadDao.getByEpisodeId(episodeId)
                deleteDownloadFiles(episodeId, download?.episode?.title ?: "", download?.downloadUri)
                if (download != null) {
                    downloadDao.insert(download.copy(status = DownloadStatus.DELETED, progress = 0f, downloadUri = null, downloadedBytes = 0L, totalBytes = -1L))
                }
            }
        }
    }

    override fun pauseDownload(episodeId: String) {
        stopJob(episodeId)
        scope.launch {
            // Wait for a running job to record its progress, then mark it paused.
            lockFor(episodeId).withLock {
                val download = downloadDao.getByEpisodeId(episodeId)
                if (download != null && download.status == DownloadStatus.DOWNLOADING) {
                    downloadDao.insert(download.copy(status = DownloadStatus.PAUSED))
                }
            }
        }
    }

    private fun stopJob(episodeId: String) {
        stopRequested.add(episodeId)
        workManager.cancelUniqueWork(workName(episodeId))
        // Cancel the call too, to stop network traffic straight away.
        activeCalls[episodeId]?.cancel()
    }

    override fun resumeDownload(episodeId: String) {
        scope.launch {
            val download = downloadDao.getByEpisodeId(episodeId)
            if (download != null && download.status != DownloadStatus.DOWNLOADED) {
                // The job picks up the partial file and resumes from its length.
                enqueueDownload(download.episode, userInitiated = true)
            }
        }
    }

    override fun getDownload(id: Long): Download? {
        val episodeId = id.toString()
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            downloadDao.getByEpisodeId(episodeId)
        }
    }

    override fun deleteDownload(episode: Episode) {
        stopJob(episode.id)
        scope.launch {
            // File/storage I/O off the caller thread — this is invoked from onClick and
            // from the player's STATE_ENDED callback on the main thread.
            lockFor(episode.id).withLock {
                val download = downloadDao.getByEpisodeId(episode.id)
                deleteDownloadFiles(episode.id, episode.title, download?.downloadUri)
                if (download != null) {
                    downloadDao.insert(download.copy(status = DownloadStatus.DELETED, downloadUri = null, progress = 0f, downloadedBytes = 0L, totalBytes = -1L))
                }
            }
        }
    }

    /**
     * Removes a download's file(s) from disk. Prefers the exact path recorded at
     * download time (downloadUri); then sweeps every candidate storage dir, because
     * the user may have switched Internal/External since the file was written —
     * recomputing only the CURRENT base dir orphans files in the other location.
     */
    private fun deleteDownloadFiles(episodeId: String, episodeTitle: String, downloadUri: String?) {
        forgetValidator(episodeId)
        try {
            downloadUri?.let { uri ->
                Uri.parse(uri).path?.let { path ->
                    val recorded = File(path)
                    if (recorded.exists()) recorded.delete()
                }
            }
            for (dir in StorageManager.getAllCandidateDirs(context)) {
                val file = File(dir, "$episodeId.mp3")
                if (file.exists()) file.delete()
                val oldFile = File(dir, "$episodeTitle.mp3")
                if (oldFile.exists()) oldFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting download files for $episodeId: ${e.message}")
        }
    }
}
