package com.calmcast.podcast.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calmcast.podcast.ui.PodcastViewModel
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.progress_indicator.LinearProgressIndicatorMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * "Continue listening" card shown at the top of the Following screen so the current
 * episode is always one tap away. Reads ViewModel state internally so the 1s position
 * tick recomposes only this strip, not the surrounding screen.
 */
@Composable
fun ContinueListeningStrip(viewModel: PodcastViewModel) {
    val episode = viewModel.currentEpisode.value ?: return
    val position = viewModel.currentPosition.value
    val duration = viewModel.duration.value
    val isPlaying = viewModel.isPlaying.value

    // E-ink: quantize what we render so pixels change about once a minute, not every
    // second — otherwise the position tick forces a constant partial screen refresh.
    val progressFraction = if (duration > 0) {
        val raw = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        (raw * 50).toInt() / 50f // 2% steps
    } else 0f
    val remainingText = if (duration > 0) {
        val remainingMin = ((duration - position) / 60000L).coerceAtLeast(0L)
        if (remainingMin >= 60) "-${remainingMin / 60}h ${remainingMin % 60}m" else "-$remainingMin min"
    } else ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.showFullPlayer() }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
    ) {
        TextMMD(
            text = "CONTINUE LISTENING",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                TextMMD(
                    text = episode.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextMMD(
                    text = episode.podcastTitle,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = { viewModel.togglePlayPause() },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Resume",
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        // Always rendered (even with unknown duration) so the strip height never
        // shifts mid-session — a layout shift repaints the whole list on e-ink.
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicatorMMD(
                progress = { progressFraction },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.size(8.dp))
            TextMMD(
                text = remainingText,
                fontSize = 12.sp
            )
        }
    }
    HorizontalDividerMMD(thickness = 3.dp)
}
