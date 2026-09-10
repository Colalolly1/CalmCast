package com.calmcast.podcast

import android.annotation.SuppressLint
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.sharp.Clear
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import com.calmcast.podcast.data.PodcastDatabase
import com.calmcast.podcast.data.PodcastRepository.Episode
import com.calmcast.podcast.data.SettingsManager
import com.calmcast.podcast.ui.FullPlayerScreen
import com.calmcast.podcast.ui.PictureInPictureContent
import com.calmcast.podcast.ui.PictureInPictureHelper
import com.calmcast.podcast.ui.PodcastViewModel
import com.calmcast.podcast.ui.PodcastViewModelFactory
import com.calmcast.podcast.ui.SettingsScreen
import com.calmcast.podcast.ui.downloads.DownloadsScreen
import com.calmcast.podcast.ui.history.HistoryScreen
import com.calmcast.podcast.ui.podcastdetail.FeedNotFoundScreen
import com.calmcast.podcast.ui.podcastdetail.PodcastDetailScreen
import com.calmcast.podcast.ui.queue.QueueScreen
import com.calmcast.podcast.ui.search.SearchScreen
import com.calmcast.podcast.ui.subscriptions.SubscriptionsScreen
import com.calmcast.podcast.ui.subscriptions.AddRSSFeedModal
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.nav_bar.NavigationBarItemMMD
import com.mudita.mmd.components.nav_bar.NavigationBarMMD
import com.calmcast.podcast.ui.common.ContinueListeningStrip
import com.mudita.mmd.components.progress_indicator.CircularProgressIndicatorMMD
import com.mudita.mmd.components.search_bar.SearchBarDefaultsMMD
import com.mudita.mmd.components.snackbar.SnackbarHostMMD
import com.mudita.mmd.components.snackbar.SnackbarHostStateMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Subscriptions : Screen("subscriptions", "Following", Icons.Outlined.StarBorder)
    object Queue : Screen("queue", "Queue", Icons.Outlined.BookmarkBorder)
    object History : Screen("history", "History", Icons.Outlined.History)
    object Downloads : Screen("downloads", "Downloads", Icons.Outlined.Download)
    object Settings : Screen("settings", "Settings", Icons.Outlined.Settings)
}

val navItems = listOf(
    Screen.Subscriptions,
    Screen.Queue,
    Screen.History,
    Screen.Downloads,
    Screen.Settings
)

class MainActivity : ComponentActivity() {

    private val pipStateHolder = mutableStateOf(false)
    private lateinit var settingsManager: SettingsManager
    private var isPlayingFlag: Boolean = false
    private var refreshCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)
        setContent {
            ThemeMMD {
                CalmCastApp(pipStateHolder, settingsManager, onIsPlayingChanged = { isPlaying ->
                    isPlayingFlag = isPlaying
                }, onRefreshCallbackReady = { callback ->
                    refreshCallback = callback
                })
            }
        }
    }

    // NOTE: deliberately no onResume() refresh — AppLifecycleTracker already refreshes
    // on ON_START (throttled). Having both meant two full feed refreshes per screen wake,
    // which saturated the e-ink device's CPU and made the whole app laggy.

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        attemptEnterPictureInPictureMode()
    }

    private fun attemptEnterPictureInPictureMode() {
        try {
            if (isPlayingFlag &&
                settingsManager.isPictureInPictureEnabledSync() &&
                PictureInPictureHelper.isPictureInPictureSupported(this)
            ) {
                PictureInPictureHelper.enterPictureInPictureMode(this)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        pipStateHolder.value = isInPictureInPictureMode
    }
}


@SuppressLint("ContextCastToActivity")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalmCastApp(pipStateHolder: androidx.compose.runtime.MutableState<Boolean>, settingsManager: SettingsManager, onIsPlayingChanged: (Boolean) -> Unit, onRefreshCallbackReady: (((() -> Unit)?) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostStateMMD() }
    val isInPiP = pipStateHolder

    val showAddRSSModal = remember { mutableStateOf(false) }

    val application = LocalContext.current.applicationContext as Application
    val database = PodcastDatabase.getDatabase(application)
    val podcastDao = database.podcastDao()
    val playbackPositionDao = database.playbackPositionDao()
    val downloadDao = database.downloadDao()
    val listenHistoryDao = database.listenHistoryDao()
    val savedEpisodeDao = database.savedEpisodeDao()
    val viewModel: PodcastViewModel = viewModel(factory = PodcastViewModelFactory(application, podcastDao, playbackPositionDao, downloadDao, listenHistoryDao, savedEpisodeDao, settingsManager))

    DisposableEffect(Unit) {
        val callback: (() -> Unit) = {
            viewModel.reloadData()
        }
        AppLifecycleTracker.setRefreshCallback(callback)
        onRefreshCallbackReady?.invoke(callback)
        // Clear the static reference so the ViewModel/Activity can be GC'd
        onDispose { AppLifecycleTracker.setRefreshCallback(null) }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { viewModel.isPlaying.value }
            .collect { playing -> onIsPlayingChanged(playing) }
    }

    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(viewModel.isKeepScreenOnEnabled.value, viewModel.showFullPlayer.value) {
        val shouldKeepOn = viewModel.isKeepScreenOnEnabled.value && viewModel.showFullPlayer.value
        if (shouldKeepOn) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    val navController = rememberNavController()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val canNavigateBack = navController.previousBackStackEntry != null
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(currentDestination) {
        if (currentDestination?.route == "search") {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { viewModel.playbackError.value }
            .collect { error ->
                if (error != null) {
                    val result = snackbarHostState.showSnackbar(
                        message = "No internet connection. Go to Downloads to play offline episodes.",
                        actionLabel = "Downloads"
                    )
                    if (result == com.mudita.mmd.components.snackbar.SnackbarResultMMD.ActionPerformed) {
                        navController.navigate("downloads")
                    }
                    viewModel.clearPlaybackError()
                }
            }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { viewModel.isFetchingLatestEpisodes.value }
            .collect { isFetching ->
                if (isFetching) {
                    scope.launch {
                        snackbarHostState.showSnackbar("Fetching latest episodes")
                    }
                }
            }
    }


    if (isInPiP.value && viewModel.currentEpisode.value != null) {
        PictureInPictureContent(
            podcast = viewModel.currentPodcastDetails.value?.podcast,
            episode = viewModel.currentEpisode.value,
            isPlaying = viewModel.isPlaying.value,
            onPlayPauseClick = { viewModel.togglePlayPause() },
            artworkUri = viewModel.currentArtworkUri.value
        )
    } else {
        AddRSSFeedModal(
            showModal = showAddRSSModal.value,
            onDismiss = { showAddRSSModal.value = false },
            onSave = { url ->
                viewModel.subscribeToRSSUrl(url) { result ->
                    scope.launch {
                        if (result.isSuccess) {
                            val p = result.getOrNull()
                            snackbarHostState.showSnackbar("Now following ${p?.title ?: "podcast"}")
                            showAddRSSModal.value = false
                        } else {
                            val message = result.exceptionOrNull()?.message ?: "Failed to add RSS feed"
                            snackbarHostState.showSnackbar(message)
                        }
                    }
                }
            },
            isLoading = viewModel.isAddingRSSFeed.value
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        SnackbarHostMMD(hostState = snackbarHostState)
                    }
                },
                topBar = {
                    Column {
                        TopAppBarMMD(
                            title = {
                                if (currentDestination?.route == "search") {
                                    SearchBarDefaultsMMD.InputField(
                                        query = viewModel.searchQuery.value,
                                        onQueryChange = { viewModel.updateSearchQuery(it) },
                                        onSearch = { },
                                        expanded = true,
                                        onExpandedChange = { },
                                        placeholder = { TextMMD("Search") },
                                        trailingIcon = {
                                            if (viewModel.searchQuery.value.isNotEmpty()) {
                                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                                    Icon(
                                                        Icons.Sharp.Clear,
                                                        contentDescription = "Clear search"
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier.focusRequester(focusRequester)
                                    )
                                } else {
                                    Column {
                                        val title = getAppBarTitle(currentDestination?.route, viewModel)
                                        Text(
                                            text = title,
                                            fontSize = if (currentDestination?.route?.startsWith("detail/") == true) 18.sp else 24.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        if (currentDestination?.route?.startsWith("detail/") == true) {
                                            viewModel.currentPodcastDetails.value?.podcast?.author?.let { author ->
                                                Text(
                                                    text = "by $author",
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Normal,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            navigationIcon = {
                                if (canNavigateBack) {
                                    IconButton(onClick = { navController.navigateUp() }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back"
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (viewModel.isLoading.value || viewModel.isFetchingLatestEpisodes.value) {
                                    CircularProgressIndicatorMMD(
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(20.dp)
                                    )
                                }

                                if (viewModel.currentEpisode.value != null) {
                                    Row {
                                        ButtonMMD(
                                            contentPadding = PaddingValues(0.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.onBackground,
                                                containerColor = MaterialTheme.colorScheme.background
                                            ),
                                            onClick = { viewModel.togglePlayPause() }
                                        ) {
                                            Icon(
                                                imageVector = if (viewModel.isPlaying.value) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                                contentDescription = if (viewModel.isPlaying.value) "Pause" else "Play"
                                            )
                                        }
                                        ButtonMMD(
                                            contentPadding = PaddingValues(0.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.onBackground,
                                                containerColor = MaterialTheme.colorScheme.background
                                            ),
                                            onClick = { viewModel.showFullPlayer() }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Fullscreen,
                                                contentDescription = "Open full screen player"
                                            )
                                        }
                                    }
                                }

                                if (currentDestination?.route == "subscriptions") {
                                    ButtonMMD(
                                        contentPadding = PaddingValues(0.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.onBackground,
                                            containerColor = MaterialTheme.colorScheme.background
                                        ),
                                        onClick = { navController.navigate("search") }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Search,
                                            contentDescription = "Search"
                                        )
                                    }
                                }
                                if (currentDestination?.route == "search") {
                                    ButtonMMD(
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.padding(end = 8.dp),
                                        onClick = { showAddRSSModal.value = true }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Add,
                                            contentDescription = "Add RSS"
                                        )
                                    }
                                }
                                if (currentDestination?.route?.startsWith("detail/") == true) {
                                    val podcast = viewModel.currentPodcastDetails.value?.podcast
                                    if (podcast != null) {
                                        val isFollowed = viewModel.isSubscribed(podcast.id)
                                        ButtonMMD(
                                            contentPadding = PaddingValues(0.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.onBackground,
                                                containerColor = MaterialTheme.colorScheme.background
                                            ),
                                            onClick = {
                                                if (isFollowed) {
                                                    viewModel.unsubscribeFromPodcast(podcast.id)
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar("Unfollowed ${podcast.title}")
                                                    }
                                                } else {
                                                    viewModel.subscribeToPodcast(podcast)
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar("Followed ${podcast.title}")
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = if (viewModel.isSubscribed(podcast.id)) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                                contentDescription = if (viewModel.isSubscribed(podcast.id)) "Unfollow" else "Follow"
                                            )
                                        }
                                    }
                                }
                            },
                            showDivider = false
                        )
                        HorizontalDividerMMD(thickness = 3.dp)
                    }
                },
                bottomBar = {
                    if (currentDestination?.route?.startsWith("detail/") != true) {
                        NavigationBarMMD {
                            navItems.forEach { screen ->
                                val isSelected =
                                    currentDestination?.hierarchy?.any { it.route == screen.route } == true
                                NavigationBarItemMMD(
                                    icon = {
                                        Icon(
                                            painter = rememberVectorPainter(image = screen.icon),
                                            contentDescription = screen.label,
                                        )
                                    },
                                    label = {
                                        Text(
                                            screen.label,
                                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium
                                        )
                                    },
                                    selected = isSelected,
                                    onClick = {
                                        navController.navigate(screen.route) {
                                            popUpTo(navController.graph.findStartDestination().id)
                                            launchSingleTop = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            ) { paddingValues ->
                NavHost(
                    navController = navController,
                    startDestination = "subscriptions",
                    modifier = Modifier.padding(paddingValues),
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None }
                ) {
                    composable(
                        "subscriptions",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None }
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            ContinueListeningStrip(viewModel)
                            Box(modifier = Modifier.weight(1f)) {
                                SubscriptionsScreen(
                                    podcasts = viewModel.subscriptions.value,
                                    onPodcastClick = { podcast ->
                                        viewModel.clearPodcastDetails()
                                        navController.navigate("detail/${podcast.id}")
                                    },
                                    removeDividers = viewModel.removeHorizontalDividers.value,
                                    isLoading = viewModel.isLoading.value,
                                )
                            }
                        }
                    }
                    composable(
                        "search",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None }
                    ) {
                        SearchScreen(
                            searchQuery = viewModel.searchQuery.value,
                            searchResults = viewModel.searchResults.value,
                            onPodcastClick = { podcast ->
                                viewModel.clearPodcastDetails()
                                navController.navigate("detail/${podcast.id}")
                            },
                            isFollowed = viewModel::isSubscribed,
                            onFollowClick = { podcast ->
                                if (viewModel.isSubscribed(podcast.id)) {
                                    viewModel.unsubscribeFromPodcast(podcast.id)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Unfollowed ${podcast.title}")
                                    }
                                } else {
                                    viewModel.subscribeToPodcast(podcast)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Followed ${podcast.title}")
                                    }
                                }
                            },
                            removeDividers = viewModel.removeHorizontalDividers.value,
                            isLoading = viewModel.isLoading.value
                        )
                    }
                    composable(
                        "detail/{podcastId}",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None }
                    ) { backStackEntry ->
                        val podcastId = backStackEntry.arguments?.getString("podcastId") ?: return@composable

                        LaunchedEffect(podcastId) {
                            val currentDetails = viewModel.currentPodcastDetails.value
                            if (currentDetails == null || currentDetails.podcast.id != podcastId) {
                                viewModel.fetchPodcastDetails(podcastId)
                            }
                        }

                        val detailError = viewModel.detailError.value
                        if (detailError != null) {
                            FeedNotFoundScreen(
                                errorCode = detailError.first,
                                onBackClick = { navController.navigateUp() }
                            )
                        } else {
                            val podcastWithEpisodes = viewModel.currentPodcastDetails.value
                            // Never render stale data from a previously viewed podcast —
                            // show a loader until the loaded details match this route.
                            if (podcastWithEpisodes == null || podcastWithEpisodes.podcast.id != podcastId) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicatorMMD()
                                }
                                return@composable
                            }

                            val podcast = podcastWithEpisodes.podcast
                            val episodes = podcastWithEpisodes.episodes
                            val isLoadingEpisodes = viewModel.episodesLoading.value
                            val downloads = viewModel.downloads.value
                            val playbackPositions = viewModel.playbackPositions.value

                            PodcastDetailScreen(
                                podcast = podcast,
                                episodes = episodes,
                                downloads = downloads,
                                playbackPositions = playbackPositions,
                                isLoadingEpisodes = isLoadingEpisodes,
                                isBuffering = viewModel.isBuffering.value,
                                currentPlayingEpisodeId = viewModel.currentEpisode.value?.id,
                                onEpisodeClick = { episode: Episode ->
                                    viewModel.playEpisodeFromPodcast(episode)
                                },
                                onDownloadClick = { episode: Episode ->
                                    viewModel.downloadEpisode(episode)
                                },
                                onDeleteClick = { episode: Episode ->
                                    viewModel.deleteEpisode(episode)
                                },
                                onPauseClick = { episode: Episode ->
                                    viewModel.pauseDownload(episode.id)
                                },
                                onCancelClick = { episode: Episode ->
                                    viewModel.cancelDownload(episode.id)
                                },
                                onResumeClick = { episode: Episode ->
                                    viewModel.resumeDownload(episode.id)
                                },
                                onRefreshClick = {
                                    viewModel.refreshPodcastEpisodes(podcast.id)
                                },
                                onSaveClick = { episode: Episode ->
                                    viewModel.toggleSaveEpisode(episode)
                                },
                                isSaved = { episodeId ->
                                    viewModel.isEpisodeSaved(episodeId)
                                },
                                onMarkPlayedClick = { episode: Episode ->
                                    viewModel.markAsPlayed(episode)
                                },
                                onMarkUnplayedClick = { episode: Episode ->
                                    viewModel.markAsUnplayed(episode)
                                },
                                removeDividers = viewModel.removeHorizontalDividers.value,
                                sortOrder = viewModel.episodeSortOrder.value,
                                onSortOrderChange = { viewModel.setEpisodeSortOrder(it) },
                                episodeFilter = viewModel.episodeFilter.value,
                                onEpisodeFilterChange = { viewModel.setEpisodeFilter(it) }
                            )
                        }
                    }
                    composable(
                        "queue",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None }
                    ) {
                        QueueScreen(
                            savedEpisodes = viewModel.savedEpisodes.value,
                            playbackPositions = viewModel.playbackPositions.value,
                            currentPlayingEpisodeId = viewModel.currentEpisode.value?.id,
                            onEpisodeClick = { episode ->
                                viewModel.playEpisodeFromPodcast(episode)
                            },
                            onRemoveClick = { episodeId ->
                                viewModel.removeSavedEpisode(episodeId)
                            },
                            toEpisode = viewModel::savedEpisodeToEpisode,
                            removeDividers = viewModel.removeHorizontalDividers.value,
                            onMoveUp = { episodeId -> viewModel.moveQueueItem(episodeId, up = true) },
                            onMoveDown = { episodeId -> viewModel.moveQueueItem(episodeId, up = false) }
                        )
                    }
                    composable(
                        "history",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None }
                    ) {
                        HistoryScreen(
                            history = viewModel.listenHistory.value,
                            playbackPositions = viewModel.playbackPositions.value,
                            currentPlayingEpisodeId = viewModel.currentEpisode.value?.id,
                            onEpisodeClick = { episode ->
                                viewModel.playEpisodeFromPodcast(episode)
                            },
                            onRemoveClick = { episodeId ->
                                viewModel.removeFromHistory(episodeId)
                            },
                            toEpisode = viewModel::historyEntryToEpisode,
                            removeDividers = viewModel.removeHorizontalDividers.value
                        )
                    }
                    composable(
                        "downloads",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None }
                    ) {
                        val rawDownloads = viewModel.downloads.value
                        val downloads = remember(rawDownloads) {
                            rawDownloads.sortedByDescending { it.episode.publishDateMillis }
                        }
                        val playbackPositions = viewModel.playbackPositions.value

                        DownloadsScreen(
                            downloads = downloads,
                            playbackPositions = playbackPositions,
                            currentPlayingEpisodeId = viewModel.currentEpisode.value?.id,
                            isBuffering = viewModel.isBuffering.value,
                            onEpisodeClick = { episode ->
                                viewModel.playEpisodeFromDownloads(episode)
                            },
                            onDeleteClick = { episode ->
                                viewModel.deleteEpisode(episode)
                            },
                            onPauseClick = { episode ->
                                viewModel.pauseDownload(episode.id)
                            },
                            onCancelClick = { episode ->
                                viewModel.cancelDownload(episode.id)
                            },
                            onResumeClick = { episode ->
                                viewModel.resumeDownload(episode.id)
                            },
                            removeDividers = viewModel.removeHorizontalDividers.value
                        )
                    }
                    composable(
                        "settings",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None }
                    ) {
                        val exportLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.CreateDocument("text/xml")
                        ) { uri ->
                            if (uri != null) {
                                val outputStream = application.contentResolver.openOutputStream(uri)
                                if (outputStream != null) {
                                    viewModel.exportOpml(outputStream)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Subscriptions exported")
                                    }
                                }
                            }
                        }

                        val importLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocument()
                        ) { uri ->
                            if (uri != null) {
                                viewModel.importOpml(uri) { count ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Imported $count subscriptions")
                                    }
                                }
                            }
                        }

                        val settingsContext = LocalContext.current
                        var isBatteryExempt by remember {
                            mutableStateOf(isIgnoringBatteryOptimizations(settingsContext))
                        }
                        val settingsLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                        DisposableEffect(settingsLifecycleOwner) {
                            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                    isBatteryExempt = isIgnoringBatteryOptimizations(settingsContext)
                                }
                            }
                            settingsLifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { settingsLifecycleOwner.lifecycle.removeObserver(observer) }
                        }

                        SettingsScreen(
                            isPictureInPictureEnabled = viewModel.isPictureInPictureEnabled.value,
                            onPictureInPictureToggle = { enabled ->
                                viewModel.setPictureInPictureEnabled(enabled)
                            },
                            isAutoDownloadEnabled = viewModel.isAutoDownloadEnabled.value,
                            onAutoDownloadToggle = { enabled ->
                                viewModel.setAutoDownloadEnabled(enabled)
                            },
                            skipSeconds = viewModel.skipSeconds.value,
                            onSkipSecondsChange = { seconds ->
                                viewModel.setSkipSeconds(seconds)
                            },
                            isKeepScreenOnEnabled = viewModel.isKeepScreenOnEnabled.value,
                            onKeepScreenOnToggle = { enabled ->
                                viewModel.setKeepScreenOnEnabled(enabled)
                            },
                            removeHorizontalDividers = viewModel.removeHorizontalDividers.value,
                            onRemoveHorizontalDividersToggle = { enabled ->
                                viewModel.setRemoveHorizontalDividers(enabled)
                            },
                            sleepTimerEnabled = viewModel.sleepTimerEnabled.value,
                            onSleepTimerEnabledChange = { enabled ->
                                viewModel.setSleepTimerEnabled(enabled)
                            },
                            sleepTimerMinutes = viewModel.sleepTimerMinutes.value,
                            onSleepTimerMinutesChange = { minutes ->
                                viewModel.setSleepTimerMinutes(minutes)
                            },
                            downloadLocation = viewModel.downloadLocation.value,
                            onDownloadLocationChange = { location ->
                                viewModel.setDownloadLocation(location)
                            },
                            isExternalStorageAvailable = viewModel.isExternalStorageAvailable.value,
                            playbackSpeed = viewModel.playbackSpeed.value,
                            onPlaybackSpeedChange = { speed ->
                                viewModel.setPlaybackSpeed(speed)
                            },
                            isAutoPlayNextEpisodeEnabled = viewModel.isAutoPlayNextEpisodeEnabled.value,
                            onAutoPlayNextEpisodeToggle = { enabled ->
                                viewModel.setAutoPlayEpisodeEnabled(enabled)
                            },
                            isWiFiOnlyDownloadsEnabled = viewModel.isWiFiOnlyDownloadsEnabled.value,
                            onWiFiOnlyDownloadsToggle = { enabled ->
                                viewModel.setWiFiOnlyDownloadsEnabled(enabled)
                            },
                            onExportOpml = {
                                exportLauncher.launch("calmcast_subscriptions.opml")
                            },
                            onImportOpml = {
                                importLauncher.launch(arrayOf("text/xml", "text/x-opml", "application/xml", "*/*"))
                            },
                            isSkipSilencesEnabled = viewModel.isSkipSilencesEnabled.value,
                            onSkipSilencesToggle = { viewModel.setSkipSilencesEnabled(it) },
                            isAutoDeletePlayedEnabled = viewModel.isAutoDeletePlayedEnabled.value,
                            onAutoDeletePlayedToggle = { viewModel.setAutoDeletePlayedEnabled(it) },
                            isBackgroundSyncEnabled = viewModel.isBackgroundSyncEnabled.value,
                            onBackgroundSyncToggle = { viewModel.setBackgroundSyncEnabled(it) },
                            isBatteryExempt = isBatteryExempt,
                            onRequestBatteryExemption = {
                                requestBatteryExemption(settingsContext)
                            },
                            storageUsage = remember(viewModel.downloads.value) {
                                viewModel.computeStorageUsage()
                            },
                            onDeletePodcastDownloads = { usage ->
                                viewModel.deletePodcastDownloads(usage.podcastId)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Deleted ${usage.episodeCount} download(s) from ${usage.podcastTitle}"
                                    )
                                }
                            }
                        )
                    }
                }
            }

            // Isolated in its own composable: the 1s position tick recomposes only this
            // overlay, not the Scaffold/NavHost shell around it.
            FullPlayerOverlay(viewModel)
        }
    }
}

@Composable
private fun FullPlayerOverlay(viewModel: PodcastViewModel) {
    if (!viewModel.showFullPlayer.value) return
    BackHandler {
        viewModel.minimizePlayer()
    }
    FullPlayerScreen(
        episode = viewModel.currentEpisode.value,
        artworkUrl = viewModel.currentArtworkUri.value?.toString(),
        isPlaying = viewModel.isPlaying.value,
        isLoading = viewModel.isBuffering.value,
        onPlayPauseClick = { viewModel.togglePlayPause() },
        onSeekForward = { viewModel.seekForward() },
        onSeekBackward = { viewModel.seekBackward() },
        onMinimizeClick = { viewModel.minimizePlayer() },
        currentPosition = viewModel.currentPosition.value,
        duration = viewModel.duration.value,
        onSeek = { positionMs -> viewModel.seekTo(positionMs) },
        skipSeconds = viewModel.skipSeconds.value,
        isKeepScreenOnEnabled = viewModel.isKeepScreenOnEnabled.value,
        onKeepScreenOnToggle = { enabled -> viewModel.setKeepScreenOnEnabled(enabled) },
        isSleepTimerActive = viewModel.isSleepTimerActive.value,
        sleepTimerRemainingSeconds = viewModel.sleepTimerRemainingSeconds.value,
        onStartSleepTimer = { viewModel.startSleepTimer() },
        onStopSleepTimer = { viewModel.stopSleepTimer() },
        onSleepTimerMinutesChange = { minutes -> viewModel.setSleepTimerMinutes(minutes) },
        playbackSpeed = viewModel.playbackSpeed.value,
        onPlaybackSpeedClick = { viewModel.cyclePlaybackSpeed() },
        onPlaybackSpeedChange = { speed -> viewModel.setPlaybackSpeed(speed) },
        isAutoPlayNextEpisodeEnabled = viewModel.isAutoPlayNextEpisodeEnabled.value,
        onAutoPlayNextEpisodeToggle = { enabled -> viewModel.setAutoPlayEpisodeEnabled(enabled) },
        chapters = viewModel.chapters.value,
        currentChapterIndex = viewModel.currentChapterIndex.value,
        onChapterClick = { index -> viewModel.seekToChapter(index) }
    )
}

@Composable
fun getAppBarTitle(currentRoute: String?, viewModel: PodcastViewModel): String {
    return when {
        currentRoute == "subscriptions" -> "Following"
        currentRoute == "search" -> "Search"
        currentRoute == "queue" -> "Queue"
        currentRoute == "history" -> "History"
        currentRoute == "downloads" -> "Downloads"
        currentRoute == "settings" -> "Settings"
        currentRoute?.startsWith("detail/") == true -> {
            viewModel.currentPodcastDetails.value?.podcast?.title ?: ""
        }

        else -> "CalmCast"
    }
}

fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    return try {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        true
    }
}

fun requestBatteryExemption(context: android.content.Context) {
    try {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    } catch (e: Exception) {
        // Some OEM builds don't ship the direct-request dialog; fall back to the list screen
        try {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        } catch (e2: Exception) {
            com.calmcast.podcast.util.CrashLogger.logError("MainActivity", "No battery optimization screen", e2)
        }
    }
}