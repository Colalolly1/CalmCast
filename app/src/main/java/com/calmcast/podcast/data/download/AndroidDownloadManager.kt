package com.calmcast.podcast.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import com.calmcast.podcast.data.PodcastRepository.Episode
import com.calmcast.podcast.data.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class AndroidDownloadManager(
    private val context: Context,
    private val client: OkHttpClient,
    private val downloadDao: DownloadDao,
    private val settingsManager: SettingsManager
) : DownloadManager {

    companion object {
        private const val TAG = "AndroidDownloadManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Mutated from both the main thread (clicks) and IO coroutines — must be concurrent.
    private val activeDownloads = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val activeCalls = java.util.concurrent.ConcurrentHashMap<String, Call>()
    private val pausedDownloads: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    override val downloads: Flow<List<Download>> = downloadDao.getAll()

    override fun startDownload(episode: Episode): Long {
        val downloadId = episode.audioUrl.hashCode().toLong()
        val episodeId = episode.id

        // Double-tap / sync race guard: two jobs writing the same file corrupt the audio.
        if (activeDownloads.containsKey(episodeId)) {
            Log.d(TAG, "Download already active for ${episode.title}, ignoring duplicate start")
            return downloadId
        }
        val baseDir: File
        try {
            baseDir = StorageManager.getDownloadBaseDir(context, settingsManager)
        } catch (e: StorageUnavailableException) {
            Log.w(TAG, "External storage unavailable for episode: ${episode.title}")
            scope.launch {
                val download = Download(episodeId, episode, DownloadStatus.STORAGE_UNAVAILABLE, 0f)
                downloadDao.insert(download)
            }
            return downloadId
        }
        val file = File(baseDir, "${episode.id}.mp3")

        val download = Download(episodeId, episode, DownloadStatus.DOWNLOADING, 0f)

        val job = scope.launch {
            downloadDao.insert(download)
            try {
                val attemptResume = file.exists() && file.length() > 0
                val bytesAlready = if (attemptResume) file.length() else 0L

                val requestBuilder = Request.Builder().url(episode.audioUrl)
                if (bytesAlready > 0) {
                    requestBuilder.addHeader("Range", "bytes=$bytesAlready-")
                }

                val call = client.newCall(requestBuilder.build())
                activeCalls[episodeId] = call

                val response = call.execute()
                if (response.isSuccessful) {
                    val code = response.code
                    val body = response.body

                    if (body != null) {
                        val isResume = code == 206

                        val finalBytesAlready = if (isResume) bytesAlready else 0L
                        val appendMode = isResume

                        val serverBytes = body.contentLength()
                        val totalBytes = if (serverBytes != -1L) finalBytesAlready + serverBytes else -1L

                        var bytesCopied = finalBytesAlready

                        try {
                            body.byteStream().use { input ->
                                FileOutputStream(file, appendMode).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var read = input.read(buffer)
                                    var lastDbUpdateTime = System.currentTimeMillis()

                                    while (read >= 0) {
                                        if (pausedDownloads.contains(episodeId)) {
                                            downloadDao.insert(download.copy(
                                                status = DownloadStatus.PAUSED,
                                                progress = if (totalBytes > 0) bytesCopied.toFloat() / totalBytes.toFloat() else 0f,
                                                downloadedBytes = bytesCopied,
                                                totalBytes = totalBytes
                                            ))
                                            return@launch
                                        }

                                        output.write(buffer, 0, read)
                                        bytesCopied += read

                                        val progress = if (totalBytes > 0) {
                                            bytesCopied.toFloat() / totalBytes.toFloat()
                                        } else {
                                            -1f
                                        }

                                        // Each insert makes Room re-emit the full download list and
                                        // recompose observers — 5s is plenty for an e-ink progress bar.
                                        val currentTime = System.currentTimeMillis()
                                        if (currentTime - lastDbUpdateTime >= 5000) {
                                            downloadDao.insert(download.copy(
                                                status = DownloadStatus.DOWNLOADING,
                                                progress = progress,
                                                downloadedBytes = bytesCopied,
                                                totalBytes = totalBytes
                                            ))
                                            lastDbUpdateTime = currentTime
                                        }
                                        read = input.read(buffer)
                                    }
                                }
                            }
                        } catch (e: IOException) {
                            // Keep the partial when paused (resume needs it); otherwise delete
                            // so failed partials don't silently accumulate on disk.
                            // (Condition was previously inverted — `!file.exists()` — making
                            // this cleanup dead code.)
                            if (!pausedDownloads.contains(episodeId) && file.exists()) {
                                file.delete()
                            }
                            throw e
                        }

                        if (totalBytes != -1L && bytesCopied < totalBytes) {
                            if (pausedDownloads.contains(episodeId)) {
                                Log.d(TAG, "Download paused for ${episode.title}")
                                downloadDao.insert(download.copy(
                                    status = DownloadStatus.PAUSED,
                                    progress = bytesCopied.toFloat() / totalBytes.toFloat(),
                                    downloadedBytes = bytesCopied,
                                    totalBytes = totalBytes
                                ))
                            } else {
                                Log.w(TAG, "Download incomplete for ${episode.title}: $bytesCopied / $totalBytes bytes")
                                downloadDao.insert(download.copy(status = DownloadStatus.FAILED, progress = 0f))
                            }
                        } else {
                            val fileUri = Uri.fromFile(file).toString()
                            downloadDao.insert(download.copy(
                                status = DownloadStatus.DOWNLOADED,
                                progress = 1f,
                                downloadUri = fileUri,
                                downloadedBytes = bytesCopied,
                                totalBytes = totalBytes
                            ))
                        }
                    } else {
                        Log.w(TAG, "No response body for episode: ${episode.title}")
                        downloadDao.insert(download.copy(status = DownloadStatus.FAILED, progress = 0f))
                    }
                } else if (response.code == 416) {
                    Log.w(TAG, "Range not satisfiable for episode: ${episode.title}. Retrying from scratch.")
                    file.delete()
                    downloadDao.insert(download.copy(status = DownloadStatus.FAILED, progress = 0f))
                } else {
                    Log.w(TAG, "Download failed with status ${response.code} for episode: ${episode.title}")
                    downloadDao.insert(download.copy(status = DownloadStatus.FAILED, progress = 0f))
                }
            } catch (e: Exception) {
                val isCancelled = (e is IOException && e.message == "Canceled") ||
                        (e is java.net.SocketException && e.message?.contains("closed") == true)

                if (pausedDownloads.contains(episodeId) || isCancelled) {
                    Log.d(TAG, "Download paused (exception caught) for ${episode.title}")
                    val currentBytes = if (file.exists()) file.length() else 0L
                    downloadDao.insert(download.copy(
                        status = DownloadStatus.PAUSED,
                        downloadedBytes = currentBytes
                    ))
                } else {
                    Log.e(TAG, "Exception during download for episode ${episode.title}: ${e.message}", e)
                    downloadDao.insert(download.copy(status = DownloadStatus.FAILED, progress = 0f))
                }
            } finally {
                activeDownloads.remove(episodeId)
                activeCalls.remove(episodeId)
                if (!pausedDownloads.contains(episodeId)) {
                    pausedDownloads.remove(episodeId)
                }
            }
        }

        activeDownloads[episodeId] = job
        return downloadId
    }

    override fun cancelDownload(id: Long) {
        val episodeId = activeDownloads.keys.find { it.hashCode().toLong() == id }
        if (episodeId != null) {
            cancelDownload(episodeId)
        }
    }

    override fun cancelDownload(episodeId: String) {
        pausedDownloads.remove(episodeId)
        activeCalls[episodeId]?.cancel()
        activeDownloads[episodeId]?.cancel()
        scope.launch {
            // File/storage I/O off the caller thread — this is invoked from onClick.
            val download = downloadDao.getByEpisodeId(episodeId)
            deleteDownloadFiles(episodeId, download?.episode?.title ?: "", download?.downloadUri)
            if (download != null) {
                downloadDao.insert(download.copy(status = DownloadStatus.DELETED, progress = 0f, downloadUri = null, downloadedBytes = 0L, totalBytes = -1L))
            }
        }
    }

    override fun pauseDownload(episodeId: String) {
        pausedDownloads.add(episodeId)
        // Optimistically update DB
        scope.launch {
            val download = downloadDao.getByEpisodeId(episodeId)
            if (download != null && download.status == DownloadStatus.DOWNLOADING) {
                downloadDao.insert(download.copy(status = DownloadStatus.PAUSED))
            }
        }
        // Cancel the call to stop network traffic immediately
        activeCalls[episodeId]?.cancel()
    }

    override fun resumeDownload(episodeId: String) {
        pausedDownloads.remove(episodeId)
        scope.launch {
            val download = downloadDao.getByEpisodeId(episodeId)
            if (download != null && download.status == DownloadStatus.PAUSED) {
                // Restart the download - it will detect the partial file and resume from offset
                startDownload(download.episode)
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
        scope.launch {
            // File/storage I/O off the caller thread — this is invoked from onClick and
            // from the player's STATE_ENDED callback on the main thread.
            val download = downloadDao.getByEpisodeId(episode.id)
            deleteDownloadFiles(episode.id, episode.title, download?.downloadUri)
            if (download != null) {
                downloadDao.insert(download.copy(status = DownloadStatus.DELETED, downloadUri = null, progress = 0f, downloadedBytes = 0L, totalBytes = -1L))
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