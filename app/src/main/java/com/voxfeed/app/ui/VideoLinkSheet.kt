package com.voxfeed.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.voxfeed.app.data.AdHocMeta
import com.voxfeed.app.data.DigestResult
import com.voxfeed.app.data.SummaryDepth
import com.voxfeed.app.data.VideoEntity
import com.voxfeed.app.data.failureMessage
import com.voxfeed.app.videoIntent
import com.voxfeed.app.worker.PollPrefs
import kotlinx.coroutines.launch

/** Action sheet for a pasted single-video link: no channel subscription is required for any action but the last. */
@Composable
internal fun VideoLinkSheet(
    state: FeedViewModel.AdHocState, vm: FeedViewModel, subscribedIds: Set<String>,
    onDismiss: () -> Unit, onAddToPlaylist: (VideoEntity) -> Unit
) {
    val ctx = LocalContext.current
    when (state) {
        is FeedViewModel.AdHocState.Loading -> AppDialog(onDismiss) {
            AppLabel("Video Link", Palette.Text, 18.sp)
            Text("Fetching video details...", color = Palette.Dim, fontFamily = Mono, fontSize = 12.sp)
            ActionButton("Cancel", onDismiss, Modifier.fillMaxWidth())
        }
        is FeedViewModel.AdHocState.Failed -> AppDialog(onDismiss) {
            AppLabel("Video Link", Palette.Alert, 18.sp)
            Text("Could not load video: ${state.message}", color = Palette.Text, fontSize = 13.sp)
            ActionButton("Close", onDismiss, Modifier.fillMaxWidth())
        }
        is FeedViewModel.AdHocState.Ready -> {
            val meta = state.meta
            val scope = rememberCoroutineScope()
            var showSummary by remember { mutableStateOf(false) }
            var depth by remember { mutableStateOf(SummaryDepth.from(PollPrefs.summaryDepth(ctx))) }
            var summaryState by remember { mutableStateOf<SummaryState>(SummaryState.Idle) }
            val digest by remember(meta.videoId, depth) { vm.digestFlow(meta.videoId, depth) }.collectAsState(initial = null)
            val subscribed = meta.channelId != null && meta.channelId in subscribedIds

            AppFormDialog(onDismiss) {
                AppLabel("Video Link", Palette.Text, 18.sp)
                AsyncImage(
                    model = meta.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))
                )
                Text(meta.title, color = Palette.Text, fontFamily = Heavy, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val who = listOfNotNull(meta.channelName.ifBlank { null }, meta.channelHandle).joinToString("  ")
                Text(who, color = Palette.Accent, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(if (meta.durationSeconds > 0) mmss(meta.durationSeconds * 1000) else "--:--")
                        if (meta.chapters.isNotEmpty()) append("  |  ${meta.chapters.size} chapters")
                    },
                    color = Palette.Dim, fontFamily = Mono, fontSize = 11.sp
                )
                ActionButton("Watch Video", {
                    vm.withAdHocVideo(meta) { v -> ctx.startActivity(videoIntent(ctx, v, emptyList())); onDismiss() }
                }, Modifier.fillMaxWidth(), accent = true)
                ActionButton("AI Summary", { vm.withAdHocVideo(meta) { showSummary = true } }, Modifier.fillMaxWidth())
                ActionButton("Extract to Audio Deck", { vm.extractToAudioDeck(meta) }, Modifier.fillMaxWidth())
                ActionButton("Add to Playlist", { vm.withAdHocVideo(meta) { v -> onAddToPlaylist(v) } }, Modifier.fillMaxWidth())
                ActionButton(
                    if (subscribed) "Subscribed" else "Subscribe to Channel",
                    { vm.subscribeFromAdHoc(meta) },
                    Modifier.fillMaxWidth(), enabled = !subscribed && meta.channelId != null, accent = subscribed
                )
                ActionButton("Close", onDismiss, Modifier.fillMaxWidth())
            }

            if (showSummary) AiSummaryDialog(
                voxDigest = digest, state = summaryState, depth = depth,
                onDepthChange = { depth = it; PollPrefs.setSummaryDepth(ctx, it.name) },
                onGenerate = {
                    if (summaryState !is SummaryState.Loading) {
                        summaryState = SummaryState.Loading
                        val d = depth
                        scope.launch {
                            val r = vm.generateDigest(meta, d)
                            summaryState = if (r is DigestResult.Success) SummaryState.Idle else SummaryState.Failed(r.failureMessage())
                        }
                    }
                },
                onSeek = { showSummary = false },
                onClose = { showSummary = false }
            )
        }
    }
}
