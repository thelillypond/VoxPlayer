package com.voxfeed.app.ui

import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.Build
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
import com.voxfeed.app.PurgeConfirmDialog
import com.voxfeed.app.data.VideoChapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
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
import com.voxfeed.app.R
import kotlinx.coroutines.delay

/** Stream resolution state for the player screen. */
sealed interface StreamState {
    object Resolving : StreamState
    object Ready : StreamState
    data class Failed(val message: String) : StreamState
}

/** Offline archive state of the current video (Stage 4B). */
enum class ArchiveUiState { IDLE, ARCHIVING, ARCHIVED }

private val Obsidian = Color(0xFF0A0D0A)
private val BrassLine = Color(0xFF8C7832)
private val Cream = Color(0xFFD8D5A8)
private val SPEEDS = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)

@Composable
private fun PlateButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    translucent: Boolean = false, fontSize: Int = 11, textColor: Color = Cream
) {
    Box(
        modifier.background(if (translucent) Color(0xCC0A0D0A) else Grim.Card)
            .border(1.dp, BrassLine).clickable(onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = textColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            fontSize = fontSize.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

private fun filterLabel(on: Boolean) = if (on) "[ VOX-FILTER: ON ]" else "[ VOX-FILTER: OFF ]"

private fun fmt(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}

/** Scanlines (3dp pitch), phosphor bloom tint and dark radial vignette. Draws only; no input handling. */
@Composable
private fun VoxFilterOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Color(0x1F00FF41))
        val pitch = 3.dp.toPx()
        val bar = 1.5.dp.toPx()
        val lineColor = Color(0xB3030A03)
        var y = 0f
        while (y < size.height) {
            drawRect(lineColor, Offset(0f, y), Size(size.width, bar))
            y += pitch
        }
        drawRect(
            Brush.radialGradient(
                0f to Color.Transparent, 1f to Color(0x99020602),
                center = center, radius = maxOf(size.width, size.height) * 0.7f
            )
        )
    }
}

private val greenPhosphorMatrix = android.graphics.ColorMatrix(
    floatArrayOf(
        0f,    0f,    0f,    0f, 0f,
        0.33f, 0.59f, 0.11f, 0f, 12f,
        0f,    0f,    0f,    0f, 0f,
        0f,    0f,    0f,    1f, 0f
    )
)

@Composable
private fun TopButtons(
    voxFilterEnabled: Boolean, onToggleFilter: () -> Unit, onClose: () -> Unit,
    archiveState: ArchiveUiState, archivePercent: Int, onArchive: () -> Unit,
    subsAvailable: Boolean, subsEnabled: Boolean, onToggleSubs: () -> Unit,
    digestCached: Boolean, onDigest: () -> Unit,
    modifier: Modifier = Modifier, translucent: Boolean = false, inline: Boolean = false
) {
    val archiveLabel = when (archiveState) {
        ArchiveUiState.IDLE -> "[ ARCHIVE ]"
        ArchiveUiState.ARCHIVING -> "[ ARCHIVING: ${archivePercent.coerceIn(0, 99)}% ]"
        ArchiveUiState.ARCHIVED -> "[ ARCHIVED ]"
    }
    // Tapping an ARCHIVED slate opens the expungement confirmation (onArchive is rerouted by the caller).
    val archiveClick: () -> Unit = if (archiveState == ArchiveUiState.ARCHIVING) ({}) else onArchive
    val subsLabel = when { !subsAvailable -> "[ SUBS: N/A ]"; subsEnabled -> "[ SUBS: ON ]"; else -> "[ SUBS: OFF ]" }
    val digestLabel = if (digestCached) "[ INTEL CACHED ]" else "[ A.I. DIGEST ]"
    val digestColor = if (digestCached) Color(0xFF00FF41) else Cream
    if (inline) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlateButton("[ TERMINATE ]", onClose, Modifier.weight(1f).height(42.dp), translucent)
            PlateButton(filterLabel(voxFilterEnabled), onToggleFilter, Modifier.weight(1f).height(42.dp), translucent)
            PlateButton(archiveLabel, archiveClick, Modifier.weight(1f).height(42.dp), translucent)
            PlateButton(digestLabel, onDigest, Modifier.weight(1f).height(42.dp), translucent, textColor = digestColor)
            PlateButton(subsLabel, onToggleSubs, Modifier.weight(1f).height(42.dp), translucent)
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlateButton("[ TERMINATE ]", onClose, Modifier.weight(1f).height(42.dp), translucent)
                PlateButton(filterLabel(voxFilterEnabled), onToggleFilter, Modifier.weight(1f).height(42.dp), translucent)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlateButton(archiveLabel, archiveClick, Modifier.weight(1f).height(36.dp), translucent)
                PlateButton(digestLabel, onDigest, Modifier.weight(1f).height(36.dp), translucent, textColor = digestColor)
            }
            PlateButton(subsLabel, onToggleSubs, Modifier.fillMaxWidth().height(36.dp), translucent)
        }
    }
}

/** Brass timeline: tap or drag to seek. */
@Composable
private fun BrassSlider(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
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
        drawRect(Grim.Card, Offset(0f, cy - trackH / 2), Size(size.width, trackH))
        drawRect(BrassLine, Offset(0f, cy - trackH / 2), Size(size.width * fraction, trackH))
        drawRect(
            BrassLine, Offset(0f, cy - trackH / 2), Size(size.width, trackH),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
        )
        val tw = 10.dp.toPx()
        val tx = (size.width * fraction - tw / 2).coerceIn(0f, size.width - tw)
        drawRect(Cream, Offset(tx, cy - 11.dp.toPx()), Size(tw, 22.dp.toPx()))
        drawRect(BrassLine, Offset(tx, cy - 11.dp.toPx()), Size(tw, 22.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
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

    Column(modifier.background(Obsidian).border(1.dp, BrassLine).padding(8.dp)) {
        BrassSlider(position, duration, { player.seekTo(it); onInteract() }, Modifier.fillMaxWidth())
        Text(
            "${fmt(position)} / ${fmt(duration)}", Modifier.fillMaxWidth().padding(vertical = 4.dp),
            color = Cream, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, textAlign = TextAlign.Center
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val m = Modifier.weight(1f).height(42.dp)
            PlateButton("[ -15S ]", { seekBy(-15_000) }, m)
            PlateButton(if (isPlaying) "[ PAUSE ]" else "[ PLAY ]", {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.playWhenReady = !player.playWhenReady
                if (player.playWhenReady) player.play()
                onInteract()
            }, Modifier.weight(1.3f).height(42.dp))
            PlateButton("[ +15S ]", { seekBy(15_000) }, m)
            PlateButton("[ ${SPEEDS[speedIdx]}X ]".replace("1.0X", "1.0X"), {
                speedIdx = (speedIdx + 1) % SPEEDS.size
                player.playbackParameters = PlaybackParameters(SPEEDS[speedIdx])
                onInteract()
            }, m)
        }
    }
}

@Composable
private fun VideoSurface(player: Player, voxFilterEnabled: Boolean, modifier: Modifier = Modifier) {
    val effect = remember(voxFilterEnabled) {
        if (voxFilterEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(greenPhosphorMatrix)).asComposeRenderEffect()
        } else null
    }
    AndroidView(
        modifier = modifier.graphicsLayer { renderEffect = effect },
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.vox_player_view, null) as PlayerView).apply {
                useController = false
                subtitleView?.apply {
                    setApplyEmbeddedStyles(false)
                    setApplyEmbeddedFontSizes(false)
                    setStyle(
                        androidx.media3.ui.CaptionStyleCompat(
                            0xFFD8D5A8.toInt(), 0xCC0A0D0A.toInt(), android.graphics.Color.TRANSPARENT,
                            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.BLACK,
                            android.graphics.Typeface.MONOSPACE
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
    Box(modifier.background(Obsidian).padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
            when (state) {
                is StreamState.Failed -> "SIGNAL INTERRUPTED: ${state.message}"
                else -> "RESOLVING VOX-STREAM..."
            },
            color = if (state is StreamState.Failed) Cream else Grim.Phosphor,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun PictFeedScreen(
    player: Player, state: StreamState, title: String, description: String,
    landscape: Boolean, voxFilterEnabled: Boolean, ended: Boolean,
    onToggleFilter: () -> Unit, onClose: () -> Unit,
    archiveState: ArchiveUiState = ArchiveUiState.IDLE, archivePercent: Int = 0, onArchive: () -> Unit = {},
    videoTitle: String = "", onPurge: () -> Unit = {},
    subsAvailable: Boolean = false, subsEnabled: Boolean = false, onToggleSubs: () -> Unit = {},
    chapters: List<VideoChapter> = emptyList(), subtitleText: String? = null,
    voxDigest: String? = null, intelState: IntelState = IntelState.Idle,
    onGenerateDigest: () -> Unit = {},
    summaryDepth: com.voxfeed.app.data.SummaryDepth = com.voxfeed.app.data.SummaryDepth.BRIEF,
    onDepthChange: (com.voxfeed.app.data.SummaryDepth) -> Unit = {}
) {
    var showIntel by remember { mutableStateOf(false) }
    val openIntel: () -> Unit = { showIntel = true }
    var confirmPurge by remember { mutableStateOf(false) }
    val archiveAction: () -> Unit = { if (archiveState == ArchiveUiState.ARCHIVED) confirmPurge = true else onArchive() }
    var hudVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    val onInteract: () -> Unit = { interaction++ }
    var isPlaying by remember { mutableStateOf(player.playWhenReady) }
    var buffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { isPlaying = playWhenReady }
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
            Column(
                Modifier.align(Alignment.Center).background(Obsidian).border(1.5.dp, BrassLine)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    "// BUFFERING MACHINE SPIRIT //", color = Grim.Phosphor, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center
                )
                Text(
                    "COMMUNING WITH NOOSPHERE...", color = Cream, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 9.sp, textAlign = TextAlign.Center
                )
            }
        }
    }

    val endedOverlay: @Composable BoxScope.() -> Unit = {
        if (ended) {
            Column(
                Modifier.fillMaxSize().background(Obsidian).pointerInput(Unit) { detectTapGestures { } },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "TRANSMISSION COMPLETE —", color = Grim.Phosphor, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "[ RETURN TO FEED ]",
                    Modifier.background(Grim.Card).border(1.5.dp, BrassLine).clickable(onClick = onClose)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    color = Cream, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp
                )
            }
        }
    }

    if (landscape) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(player, voxFilterEnabled, Modifier.fillMaxSize())
            if (voxFilterEnabled) VoxFilterOverlay(Modifier.fillMaxSize())
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
                    voxFilterEnabled, onToggleFilter, onClose, archiveState, archivePercent, archiveAction,
                    subsAvailable, subsEnabled, onToggleSubs, voxDigest != null, openIntel,
                    Modifier.align(Alignment.TopCenter).fillMaxWidth(0.85f).padding(8.dp),
                    translucent = true, inline = true
                )
                ControlDeck(player, onInteract, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
            }
            endedOverlay()
        }
    } else {
        Column(Modifier.fillMaxSize().background(Obsidian)) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                TopButtons(voxFilterEnabled, onToggleFilter, onClose, archiveState, archivePercent, archiveAction, subsAvailable, subsEnabled, onToggleSubs, voxDigest != null, openIntel, Modifier.fillMaxWidth())
                Text(
                    title.uppercase(), Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = Cream, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp
                )
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                VideoSurface(player, voxFilterEnabled, Modifier.fillMaxSize())
                if (voxFilterEnabled) VoxFilterOverlay(Modifier.fillMaxSize())
                StatusPane(state, Modifier.fillMaxSize())
                bufferingBadge()
                SubtitleOverlay(subtitleText, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
                endedOverlay()
            }
            ControlDeck(player, {}, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp))
            ChapterIndex(player, chapters, Modifier.padding(horizontal = 8.dp))
            PictFeedLog(description, Modifier.weight(1f))
        }
    }
    if (showIntel) AbominableIntelModal(
        voxDigest, intelState, summaryDepth, onDepthChange, onGenerateDigest,
        onSeek = { ms ->
            // Jump to the cited moment, resume if paused, and dismiss so it plays immediately.
            player.seekTo(ms)
            if (!player.playWhenReady) player.play()
            showIntel = false
        }
    ) { showIntel = false }
    if (confirmPurge) PurgeConfirmDialog(videoTitle, { confirmPurge = false }, { confirmPurge = false; onPurge() })
}


/** Scrollable description pane. */
@Composable
fun PictFeedLog(description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Obsidian).padding(8.dp)) {
        Column(
            Modifier.fillMaxSize().border(1.dp, BrassLine).background(Grim.Card)
                .verticalScroll(rememberScrollState()).padding(10.dp)
        ) {
            Text("// TRANSMISSION LOG", color = Grim.Phosphor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                description.ifBlank { "NO DATA RECORDED." },
                color = Cream, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp
            )
        }
    }
}

/** Compose-drawn caption: #D8D5A8 monospace on #CC0A0D0A with a thin brass border. */
@Composable
private fun SubtitleOverlay(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrBlank()) return
    Box(
        modifier.padding(horizontal = 16.dp).wrapContentHeight()
            .background(Color(0xCC0A0D0A)).border(1.dp, BrassLine)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            Modifier.fillMaxWidth(),
            color = Cream, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            fontSize = 13.sp, lineHeight = 17.sp, textAlign = TextAlign.Center,
            maxLines = 3, softWrap = true, overflow = TextOverflow.Ellipsis
        )
    }
}

/** `// CHAPTER INDEX` box: scrollable `[MM:SS]  Title` rows, active one in phosphor green, tap to seek. */
@Composable
private fun ChapterIndex(player: Player, chapters: List<VideoChapter>, modifier: Modifier = Modifier) {
    if (chapters.isEmpty()) return
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) { while (true) { position = player.currentPosition; delay(500) } }
    val active = chapters.indexOfLast { it.startSeconds * 1000L <= position }.coerceAtLeast(0)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(active) { listState.animateScrollToItem(active) }
    Column(modifier.fillMaxWidth().background(Obsidian).border(1.dp, BrassLine).padding(8.dp)) {
        Text(
            "// CHAPTER INDEX", color = Grim.Phosphor, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, fontSize = 11.sp
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
                    color = if (on) Color(0xFF00FF41) else Cream, fontFamily = FontFamily.Monospace,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
