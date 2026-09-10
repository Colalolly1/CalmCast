package com.calmcast.podcast

import android.app.Application
import androidx.work.Configuration
import com.calmcast.podcast.data.NowPlayingStorage
import com.calmcast.podcast.data.PodcastDatabase
import com.calmcast.podcast.data.SettingsManager
import com.calmcast.podcast.data.SubscriptionManager
import com.calmcast.podcast.data.download.AndroidDownloadManager
import com.calmcast.podcast.work.EpisodeSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Cache
import okhttp3.Interceptor
import java.io.File
import java.util.concurrent.TimeUnit

class CalmCastApplication : Application(), Configuration.Provider {

    lateinit var subscriptionManager: SubscriptionManager
    lateinit var downloadManager: AndroidDownloadManager

    val nowPlayingStorage: NowPlayingStorage by lazy {
        NowPlayingStorage(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()

        com.calmcast.podcast.util.CrashLogger.init(this)

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        val database = PodcastDatabase.getDatabase(this)
        val downloadDao = database.downloadDao()
        val podcastDao = database.podcastDao()
        val settingsManager = SettingsManager(this)
        subscriptionManager = SubscriptionManager(this, podcastDao)
        downloadManager = AndroidDownloadManager(this, okHttpClient, downloadDao, settingsManager)

        // Separate cache dir from PodcastViewModel's client — two OkHttp Cache instances
        // over the same directory corrupt the DiskLruCache journal.
        val rssCacheDir = File(cacheDir, "http_rss_cache_app")
        if (!rssCacheDir.exists()) rssCacheDir.mkdirs()
        val rssCache = Cache(rssCacheDir, 10L * 1024 * 1024)
        val rssClient = OkHttpClient.Builder()
            .cache(rssCache)
            .addInterceptor(Interceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", "CalmCast/1.0 (Android; Podcast Player)")
                    .build()
                chain.proceed(req)
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            downloadDao.deleteInvalidDownloads()
        }

        AppLifecycleTracker.initialize(
            subscriptionManager,
            settingsManager,
            podcastDao,
            null,
            apiProvider = { com.calmcast.podcast.api.ItunesApiService(rssClient) }
        )

        // Schedule background episode sync if enabled
        if (settingsManager.isBackgroundSyncEnabledSync()) {
            EpisodeSyncWorker.schedule(this, settingsManager.isWiFiOnlyDownloadsEnabledSync())
        }
    }
}