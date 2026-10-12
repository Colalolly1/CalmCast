package com.calmcast.podcast.ui.queue

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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.calmcast.podcast.data.PlaybackPosition
import com.calmcast.podcast.data.PodcastRepository.Episode
import com.calmcast.podcast.data.SavedEpisode
import com.calmcast.podcast.ui.common.DashedDivider
import com.calmcast.podcast.ui.common.SafeHtmlText
import com.calmcast.podcast.utils.DateTimeFormatter
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    savedEpisodes: List<SavedEpisode>,
    playbackPositions: Map<String, PlaybackPosition>,
    currentPlayingEpisodeId: String? = null,
    onEpisodeClick: (Episode) -> Unit,
    onRemoveClick: (String) -> Unit,
    toEpisode: (SavedEpisode) -> Episode,
    removeDividers: Boolean = false,
    onMoveUp: (String) -> Unit = {},
    onMoveDown: (String) -> Unit = {}
) {
    var selectedEntryForInfo by remember { mutableStateOf<SavedEpisode?>(null) }

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
        if (savedEpisodes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 16.dp), // balance the parent's start-only padding
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Queue is empty.\nTap the bookmark on any episode to queue it — episodes play in order automatically.",
                    fontSize = 15.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumnMMD(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Deliberately UNKEYED: with keyed items, LazyColumn anchors scroll to the
                // moved item on reorder, making the swapped row appear to vanish.
                items(savedEpisodes.size) { index ->
                    val saved = savedEpisodes[index]
                    val isLastItem = index == savedEpisodes.lastIndex
                    val playbackPosition = playbackPositions[saved.episodeId]

                    QueueItem(
                        saved = saved,
                        position = index + 1,
                        playbackPosition = playbackPosition,
                        isLastItem = isLastItem,
                        isFirst = index == 0,
                        isLast = isLastItem,
                        isCurrentlyPlaying = saved.episodeId == currentPlayingEpisodeId,
                        onClick = { onEpisodeClick(toEpisode(saved)) },
                        onRemoveClick = { onRemoveClick(saved.episodeId) },
                        onMoveUp = { onMoveUp(saved.episodeId) },
                        onMoveDown = { onMoveDown(saved.episodeId) },
                        removeDividers = removeDividers
                    )
                }
            }
        }
    }
}

@Composable
fun QueueItem(
    saved: SavedEpisode,
    position: Int = 0,
    playbackPosition: PlaybackPosition?,
    isLastItem: Boolean,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    isCurrentlyPlaying: Boolean = false,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    removeDividers: Boolean
) {
    val durationText = remember(saved.duration, playbackPosition?.position) {
        val totalDurationStr = DateTimeFormatter.formatDurationFromString(saved.duration)
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

    val formattedDate = remember(saved.publishDate) {
        DateTimeFormatter.formatPublishDate(saved.publishDate)
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
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = "Remove from queue",
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = buildString {
                        if (position > 0) append("$position.  ")
                        if (isCurrentlyPlaying) append("▶ ")
                        append(saved.episodeTitle)
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = saved.podcastTitle,
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
                        text = formattedDate,
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

            // Reorder controls: arrows instead of drag — reliable on an e-ink screen.
            Column(
                modifier = Modifier.padding(end = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = !isFirst,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = "Move up in queue",
                        modifier = Modifier.size(28.dp),
                        tint = if (isFirst) Color(0xFF777777) else Color.Black
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = !isLast,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Move down in queue",
                        modifier = Modifier.size(28.dp),
                        tint = if (isLast) Color(0xFF777777) else Color.Black
                    )
                }
            }
        }
    }
}
