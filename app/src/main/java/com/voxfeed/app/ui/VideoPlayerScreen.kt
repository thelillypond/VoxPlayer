package com.voxfeed.app.ui

import android.view.LayoutInflater
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.voxfeed.app.DeleteDownloadDialog
import com.voxfeed.app.R
import com.voxfeed.app.data.VideoChapter
import kotlinx.coroutines.delay

/** Stream resolution state for the player screen. */
sealed interface StreamState {
    object Resolving : StreamState
    object Ready : StreamState
    data class Failed(val message: String) : StreamState
}

/** Offline download state of the current video. */
enum class ArchiveUiState { IDLE, ARCHIVING, ARCHIVED }

private val SPEEDS = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)

@Composable
private fun PlayerButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    translucent: Boolean = false, fontSize: Int = 12, textColor: Color = Palette.Text,
    trailingCheck: Boolean = false
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.background(if (translucent) Color(0xCC000000) else Palette.Surface, shape)
            .border(1.dp, Palette.Border, shape).clickable(onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                label, color = textColor, fontFamily = Heavy, fontWeight = FontWeight.Black,
                fontSize = fontSize.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false)
            )
            if (trailingCheck) androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.Check, null, tint = textColor,
                modifier = Modifier.size((fontSize + 4).dp)
            )
        }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}

@Composable
private fun TopButtons(
    onClose: () -> Unit,
    archiveState: ArchiveUiState, archivePercent: Int, onArchive: () -> Unit,
    subsAvailable: Boolean, subsEnabled: Boolean, onToggleSubs: () -> Unit,
    summaryCached: Boolean, onSummary: () -> Unit,
    modifier: Modifier = Modifier, translucent: Boolean = false, inline: Boolean = false
) {
    val archiveLabel = when (archiveState) {
        ArchiveUiState.IDLE -> "Download"
        ArchiveUiState.ARCHIVING -> "Downloading ${archivePercent.coerceIn(0, 99)}%"
        ArchiveUiState.ARCHIVED -> "Downloaded"
    }
    // Tapping a downloaded video opens the delete confirmation (onArchive is rerouted by the caller).
    val archiveClick: () -> Unit = if (archiveState == ArchiveUiState.ARCHIVING) ({}) else onArchive
    val subsLabel = when { !subsAvailable -> "Captions: N/A"; subsEnabled -> "Captions: On"; else -> "Captions: Off" }
    val summaryLabel = if (summaryCached) "AI Summary ✓" else "AI Summary"
    val summaryColor = if (summaryCached) Palette.Accent else Palette.Text
    val archiveColor = if (archiveState == ArchiveUiState.ARCHIVED) Palette.Accent else Palette.Text
    val subsColor = if (subsAvailable && subsEnabled) Palette.Accent else Color.White
    if (inline) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerButton("Close", onClose, Modifier.weight(1f).height(42.dp), translucent)
            PlayerButton(archiveLabel, archiveClick, Modifier.weight(1f).height(42.dp), translucent, textColor = archiveColor, trailingCheck = archiveState == ArchiveUiState.ARCHIVED)
            PlayerButton(summaryLabel, onSummary, Modifier.weight(1f).height(42.dp), translucent, textColor = summaryColor)
            PlayerButton(subsLabel, onToggleSubs, Modifier.weight(1f).height(42.dp), translucent, textColor = subsColor)
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerButton("Close", onClose, Modifier.weight(1f).height(42.dp), translucent)
                PlayerButton(archiveLabel, archiveClick, Modifier.weight(1f).height(42.dp), translucent, textColor = archiveColor, trailingCheck = archiveState == ArchiveUiState.ARCHIVED)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerButton(summaryLabel, onSummary, Modifier.weight(1f).height(38.dp), translucent, textColor = summaryColor)
                PlayerButton(subsLabel, onToggleSubs, Modifier.weight(1f).height(38.dp), translucent, textColor = subsColor)
            }
        }
    }
}

/** Timeline: tap or drag to seek. */
@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction
        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Canvas(
        modifier.height(28.dp)
            .pointerInput(durationMs) {
                detectTapGestures { o ->
                    if (durationMs > 0) onSeek((o.x / size.width).coerceIn(0f, 1f).times(durationMs).toLong())
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragFraction?.let { if (durationMs > 0) onSeek((it * durationMs).toLong()) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null }
                )
            }
    ) {
        val cy = size.height / 2
        val trackH = 6.dp.toPx()
        val r = CornerRadius(trackH / 2)
        drawRoundRect(Color(0xFF2A2A2A), Offset(0f, cy - trackH / 2), Size(size.width, trackH), r)
        drawRoundRect(Palette.Accent, Offset(0f, cy - trackH / 2), Size(size.width * fraction, trackH), r)
        val tx = (size.width * fraction).coerceIn(8.dp.toPx(), size.width - 8.dp.toPx())
        drawCircle(Palette.Text, 8.dp.toPx(), Offset(tx, cy))
    }
}

/** Timeline + transport row. */
@Composable
private fun ControlDeck(player: Player, onInteract: () -> Unit, modifier: Modifier = Modifier) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(player.playWhenReady) }
    var speedIdx by remember { mutableIntStateOf(SPEEDS.indexOfFirst { it == player.playbackParameters.speed }.coerceAtLeast(0)) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p || player.playWhenReady }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { isPlaying = playWhenReady }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            delay(250)
        }
    }

    fun seekBy(delta: Long) {
        val target = player.currentPosition + delta
        val max = if (duration > 0) duration else Long.MAX_VALUE
        player.seekTo(target.coerceIn(0L, max))
        onInteract()
    }

    val shape = RoundedCornerShape(12.dp)
    Column(modifier.background(Palette.Bg, shape).border(1.dp, Palette.Border, shape).padding(8.dp)) {
        SeekBar(position, duration, { player.seekTo(it); onInteract() }, Modifier.fillMaxWidth())
        Text(
            "${fmt(position)} / ${fmt(duration)}", Modifier.fillMaxWidth().padding(vertical = 4.dp),
            color = Palette.Text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, textAlign = TextAlign.Center
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val m = Modifier.weight(1f).height(42.dp)
            PlayerButton("-15s", { seekBy(-15_000) }, m)
            PlayerButton(if (isPlaying) "Pause" else "Play", {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.playWhenReady = !player.playWhenReady
                if (player.playWhenReady) player.play()
                onInteract()
            }, Modifier.weight(1.3f).height(42.dp), textColor = Palette.Accent)
            PlayerButton("+15s", { seekBy(15_000) }, m)
            PlayerButton("${SPEEDS[speedIdx]}x", {
                speedIdx = (speedIdx + 1) % SPEEDS.size
                player.playbackParameters = PlaybackParameters(SPEEDS[speedIdx])
                onInteract()
            }, m)
        }
    }
}

@Composable
private fun VideoSurface(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.vox_player_view, null) as PlayerView).apply {
                useController = false
                subtitleView?.apply {
                    setApplyEmbeddedStyles(false)
                    setApplyEmbeddedFontSizes(false)
                    setStyle(
                        androidx.media3.ui.CaptionStyleCompat(
                            0xFFFFFFFF.toInt(), 0xCC000000.toInt(), android.graphics.Color.TRANSPARENT,
                            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.BLACK,
                            android.graphics.Typeface.DEFAULT_BOLD
                        )
                    )
                    setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                }
                this.player = player
            }
        },
        update = { it.player = player }
    )
}

@Composable
private fun StatusPane(state: StreamState, modifier: Modifier = Modifier) {
    if (state is StreamState.Ready) return
    Box(modifier.background(Palette.Bg).padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
            when (state) {
                is StreamState.Failed -> "Playback error: ${state.message}"
                else -> "Loading video..."
            },
            color = if (state is StreamState.Failed) Palette.Alert else Palette.Text,
            fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center
        )
    }
}

@Composable
fun VideoPlayerScreen(
    player: Player, state: StreamState, title: String, description: String,
    landscape: Boolean, ended: Boolean, onClose: () -> Unit,
    archiveState: ArchiveUiState = ArchiveUiState.IDLE, archivePercent: Int = 0, onArchive: () -> Unit = {},
    videoTitle: String = "", onPurge: () -> Unit = {},
    subsAvailable: Boolean = false, subsEnabled: Boolean = false, onToggleSubs: () -> Unit = {},
    chapters: List<VideoChapter> = emptyList(), subtitleText: String? = null,
    voxDigest: String? = null, summaryState: SummaryState = SummaryState.Idle,
    onGenerateDigest: () -> Unit = {},
    summaryDepth: com.voxfeed.app.data.SummaryDepth = com.voxfeed.app.data.SummaryDepth.BRIEF,
    onDepthChange: (com.voxfeed.app.data.SummaryDepth) -> Unit = {},
    queueRemaining: Int = 0
) {
    var showSummary by remember { mutableStateOf(false) }
    val openSummary: () -> Unit = { showSummary = true }
    var confirmDelete by remember { mutableStateOf(false) }
    val archiveAction: () -> Unit = { if (archiveState == ArchiveUiState.ARCHIVED) confirmDelete = true else onArchive() }
    var hudVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    val onInteract: () -> Unit = { interaction++ }
    var buffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { buffering = playbackState == Player.STATE_BUFFERING }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    // Landscape: hide HUD chrome after 3s of inactivity.
    LaunchedEffect(landscape, hudVisible, interaction) {
        if (landscape && hudVisible) {
            delay(3000)
            hudVisible = false
        }
    }
    LaunchedEffect(landscape) { hudVisible = true }

    val bufferingBadge: @Composable BoxScope.() -> Unit = {
        if (buffering && state is StreamState.Ready && !ended) {
            Text(
                "Buffering...", Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp
            )
        }
    }

    val endedOverlay: @Composable BoxScope.() -> Unit = {
        if (ended) {
            Column(
                Modifier.fillMaxSize().background(Palette.Bg).pointerInput(Unit) { detectTapGestures { } },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Text(
                    if (queueRemaining > 0) "Up next..." else "Finished", color = Palette.Text,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                ActionButton("Back to Videos", onClose, accent = true)
            }
        }
    }

    if (landscape) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(player, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null
                ) { hudVisible = !hudVisible; interaction++ }
            )
            StatusPane(state, Modifier.fillMaxSize())
            bufferingBadge()
            SubtitleOverlay(subtitleText, Modifier.align(Alignment.BottomCenter).padding(bottom = if (hudVisible) 130.dp else 24.dp))
            if (hudVisible) {
                TopButtons(
                    onClose, archiveState, archivePercent, archiveAction,
                    subsAvailable, subsEnabled, onToggleSubs, voxDigest != null, openSummary,
                    Modifier.align(Alignment.TopCenter).fillMaxWidth(0.85f).padding(8.dp),
                    translucent = true, inline = true
                )
                ControlDeck(player, onInteract, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
            }
            endedOverlay()
        }
    } else {
        Column(Modifier.fillMaxSize().background(Palette.Bg)) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                TopButtons(onClose, archiveState, archivePercent, archiveAction, subsAvailable, subsEnabled, onToggleSubs, voxDigest != null, openSummary, Modifier.fillMaxWidth())
                Text(
                    title, Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = Palette.Text, fontFamily = Heavy, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (queueRemaining > 0) Text("$queueRemaining more in queue", color = Palette.Dim, fontSize = 11.sp)
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                VideoSurface(player, Modifier.fillMaxSize())
                StatusPane(state, Modifier.fillMaxSize())
                bufferingBadge()
                SubtitleOverlay(subtitleText, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
                endedOverlay()
            }
            ControlDeck(player, {}, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp))
            ChapterIndex(player, chapters, Modifier.padding(horizontal = 8.dp))
            VideoDescription(description, Modifier.weight(1f))
        }
    }
    if (showSummary) AiSummaryDialog(
        voxDigest, summaryState, summaryDepth, onDepthChange, onGenerateDigest,
        onSeek = { ms ->
            // Jump to the cited moment, resume if paused, and dismiss so it plays immediately.
            player.seekTo(ms)
            if (!player.playWhenReady) player.play()
            showSummary = false
        }
    ) { showSummary = false }
    if (confirmDelete) DeleteDownloadDialog(videoTitle, { confirmDelete = false }, { confirmDelete = false; onPurge() })
}

/** Scrollable description pane. */
@Composable
fun VideoDescription(description: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.fillMaxWidth().padding(8.dp)) {
        Column(
            Modifier.fillMaxSize().background(Palette.Surface, shape).border(1.dp, Palette.Border, shape)
                .verticalScroll(rememberScrollState()).padding(10.dp)
        ) {
            Text("Description", color = Palette.Accent, fontFamily = Heavy, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                description.ifBlank { "No description." },
                color = Palette.Text, fontSize = 13.sp, lineHeight = 18.sp
            )
        }
    }
}

/** Caption drawn in Compose: white text on a translucent black box. */
@Composable
private fun SubtitleOverlay(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrBlank()) return
    Box(
        modifier.padding(horizontal = 16.dp).wrapContentHeight()
            .background(Color(0xCC000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            Modifier.fillMaxWidth(),
            color = Color.White, fontWeight = FontWeight.Bold,
            fontSize = 14.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
            maxLines = 3, softWrap = true, overflow = TextOverflow.Ellipsis
        )
    }
}

/** Chapter list: scrollable `[MM:SS]  Title` rows, active one highlighted, tap to seek. */
@Composable
private fun ChapterIndex(player: Player, chapters: List<VideoChapter>, modifier: Modifier = Modifier) {
    if (chapters.isEmpty()) return
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) { while (true) { position = player.currentPosition; delay(500) } }
    val active = chapters.indexOfLast { it.startSeconds * 1000L <= position }.coerceAtLeast(0)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(active) { listState.animateScrollToItem(active) }
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.fillMaxWidth().background(Palette.Bg, shape).border(1.dp, Palette.Border, shape).padding(8.dp)) {
        Text(
            "Chapters", color = Palette.Accent, fontFamily = Heavy,
            fontWeight = FontWeight.Bold, fontSize = 13.sp
        )
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 96.dp), state = listState) {
            itemsIndexed(chapters) { i, c ->
                val s = c.startSeconds
                val ts = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
                val on = i == active
                Text(
                    "[$ts]  ${c.title}",
                    Modifier.fillMaxWidth().clickable { player.seekTo(c.startSeconds * 1000L) }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    color = if (on) Palette.Accent else Palette.Text, fontFamily = FontFamily.Monospace,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
