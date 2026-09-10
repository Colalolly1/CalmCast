package com.calmcast.podcast.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calmcast.podcast.data.PlaybackPosition
import com.calmcast.podcast.data.PodcastRepository.Episode
import com.calmcast.podcast.data.download.Download
import com.calmcast.podcast.ui.common.SafeHtmlText
import com.calmcast.podcast.ui.podcastdetail.EpisodeItem
import com.calmcast.podcast.utils.DateTimeFormatter
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import androidx.compose.foundation.layout.Spacer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    downloads: List<Download>,
    playbackPositions: Map<String, PlaybackPosition>,
    currentPlayingEpisodeId: String? = null,
    isBuffering: Boolean = false,
    onEpisodeClick: (Episode) -> Unit,
    onDeleteClick: (Episode) -> Unit,
    onPauseClick: (Episode) -> Unit = {},
    onCancelClick: (Episode) -> Unit = {},
    onResumeClick: (Episode) -> Unit = {},
    removeDividers: Boolean = false
) {
    val hasError = remember { mutableStateOf(false) }
    var selectedEpisodeForInfo by remember { mutableStateOf<Episode?>(null) }

    if (selectedEpisodeForInfo != null) {
        ModalBottomSheetMMD(onDismissRequest = { selectedEpisodeForInfo = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp)
            ) {
                Text(
                    text = selectedEpisodeForInfo?.title?.takeIf { it.isNotBlank() } ?: "Episode",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier.padding(bottom = 16.dp, top = 16.dp)
                )
                if (!selectedEpisodeForInfo?.description.isNullOrEmpty()) {
                    SafeHtmlText(
                        html = selectedEpisodeForInfo?.description ?: "",
                        forceBlackText = true
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    val validDownloads = remember(downloads) {
        try {
            downloads.filter { download ->
                try {
                    if (download.status.name == "DELETED") return@filter false
                    download.episode.let { episode ->
                        episode.id.isNotBlank() && episode.title.isNotBlank() && episode.audioUrl.isNotBlank()
                    }
                } catch (_: Exception) {
                    hasError.value = true
                    false
                }
            }.sortedByDescending {
                DateTimeFormatter.parseDateToMillis(it.episode.publishDate) ?: 0L
            }
        } catch (_: Exception) {
            hasError.value = true
            emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 0.dp, bottom = 16.dp)
    ) {
        if (hasError.value && downloads.isNotEmpty() && validDownloads.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Could not load downloads",
                        fontSize = 18.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Some of your old downloads are incompatible. They will be removed automatically.",
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Button(onClick = { hasError.value = false }) {
                        Text("Dismiss")
                    }
                }
            }
        } else if (validDownloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No downloads yet")
            }
        } else if (downloads.isNotEmpty()) {
            LazyColumnMMD(
                modifier = Modifier.weight(1f),
            ) {
                items(validDownloads.size, key = { validDownloads[it].id }) { index ->
                    val download = validDownloads[index]
                    val playbackPosition = playbackPositions[download.episode.id]
                    val isCurrentlyPlaying = download.episode.id == currentPlayingEpisodeId

                    EpisodeItem(
                        episode = download.episode,
                        download = download,
                        isLastItem = index == validDownloads.lastIndex,
                        playbackPosition = playbackPosition,
                        isCurrentlyPlaying = isCurrentlyPlaying,
                        isBuffering = isBuffering && isCurrentlyPlaying,
                        showPodcastName = true,
                        onClick = { onEpisodeClick(download.episode) },
                        onDeleteClick = { onDeleteClick(download.episode) },
                        onPauseClick = { onPauseClick(download.episode) },
                        onCancelClick = { onCancelClick(download.episode) },
                        onResumeClick = { onResumeClick(download.episode) },
                        onInfoClick = { selectedEpisodeForInfo = download.episode },
                        removeDividers = removeDividers,
                        customPaddingValues = PaddingValues(
                            start = 0.dp,
                            end = 0.dp,
                            top = 16.dp,
                            bottom = 0.dp
                        )
                    )
                }
            }
        }
    }
}