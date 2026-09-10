package com.calmcast.podcast.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calmcast.podcast.data.ListenHistory
import com.calmcast.podcast.data.PlaybackPosition
import com.calmcast.podcast.data.PodcastRepository.Episode
import com.calmcast.podcast.ui.common.DashedDivider
import com.calmcast.podcast.ui.common.SafeHtmlText
import com.calmcast.podcast.utils.DateTimeFormatter
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    history: List<ListenHistory>,
    playbackPositions: Map<String, PlaybackPosition>,
    currentPlayingEpisodeId: String? = null,
    onEpisodeClick: (Episode) -> Unit,
    onRemoveClick: (String) -> Unit,
    toEpisode: (ListenHistory) -> Episode,
    removeDividers: Boolean = false
) {
    var selectedEntryForInfo by remember { mutableStateOf<ListenHistory?>(null) }

    if (selectedEntryForInfo != null) {
        ModalBottomSheetMMD(onDismissRequest = { selectedEntryForInfo = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp)
            ) {
                Text(
                    text = selectedEntryForInfo?.episodeTitle?.takeIf { it.isNotBlank() } ?: "Episode",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier.padding(bottom = 16.dp, top = 16.dp)
                )
                if (!selectedEntryForInfo?.description.isNullOrEmpty()) {
                    SafeHtmlText(
                        html = selectedEntryForInfo?.description ?: "",
                        forceBlackText = true
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 0.dp, bottom = 16.dp)
    ) {
        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No listening history yet")
            }
        } else {
            LazyColumnMMD(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(history.size, key = { history[it].episodeId }) { index ->
                    val entry = history[index]
                    val isLastItem = index == history.lastIndex
                    val playbackPosition = playbackPositions[entry.episodeId]
                    val isCurrentlyPlaying = entry.episodeId == currentPlayingEpisodeId

                    HistoryItem(
                        entry = entry,
                        playbackPosition = playbackPosition,
                        isCurrentlyPlaying = isCurrentlyPlaying,
                        isLastItem = isLastItem,
                        onClick = { onEpisodeClick(toEpisode(entry)) },
                        onRemoveClick = { onRemoveClick(entry.episodeId) },
                        removeDividers = removeDividers
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryItem(
    entry: ListenHistory,
    playbackPosition: PlaybackPosition?,
    isCurrentlyPlaying: Boolean,
    isLastItem: Boolean,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit,
    removeDividers: Boolean
) {
    val listenedDate = remember(entry.lastListenedAt) {
        DateTimeFormatter.formatRelativeTime(entry.lastListenedAt)
    }

    val durationText = remember(entry.duration, playbackPosition?.position) {
        val totalDurationStr = DateTimeFormatter.formatDurationFromString(entry.duration)
        val position = playbackPosition?.position ?: 0L
        if (position > 0) {
            val totalSeconds = position / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            val posStr = if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
            "$posStr - $totalDurationStr"
        } else {
            totalDurationStr
        }
    }

    Column(
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.padding(end = 16.dp),
                verticalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = onRemoveClick,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Remove from history",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp)
            ) {
                Text(
                    text = entry.episodeTitle,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = entry.podcastTitle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = listenedDate,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal
                    )
                    Text(
                        text = durationText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal
                    )
                }

                if (!removeDividers && !isLastItem) {
                    DashedDivider(
                        thickness = 1.dp,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
    }
}
