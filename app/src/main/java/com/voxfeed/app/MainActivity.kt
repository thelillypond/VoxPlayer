package com.voxfeed.app

import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.voxfeed.app.data.ChannelEntity
import com.voxfeed.app.data.SubFeedEntity
import com.voxfeed.app.data.VideoEntity
import com.voxfeed.app.ui.*

class MainActivity : ComponentActivity() {
    private val vm: FeedViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.voxfeed.app.worker.PollPrefs.schedule(applicationContext)
        if (savedInstanceState == null) handleAlertIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Grim.Obsidian) { VoxApp(vm) }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlertIntent(intent)
    }

    /** Vox Alert tap: jump straight to that video's player and mark it read. */
    private fun handleAlertIntent(intent: android.content.Intent?) {
        val id = intent?.getStringExtra(EXTRA_OPEN_VIDEO_ID) ?: return
        intent.removeExtra(EXTRA_OPEN_VIDEO_ID)
        lifecycleScope.launch {
            val v = com.voxfeed.app.data.VoxDatabase.get(applicationContext).videoDao().get(id) ?: return@launch
            vm.markRead(id)
            startActivity(
                android.content.Intent(this@MainActivity, PictFeedActivity::class.java)
                    .putExtra(PictFeedActivity.VIDEO_ID, v.videoId)
                    .putExtra(PictFeedActivity.VIDEO_TITLE, v.title)
                    .putExtra(PictFeedActivity.VIDEO_DESCRIPTION, v.description)
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_VIDEO_ID = "OPEN_VIDEO_ID"
    }
}

private val navLabels = listOf("FEEDS", "EXTRACT", "FAVORITES", "RITES")
private val Condensed = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))
private val Mono = FontFamily.Monospace

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoxApp(vm: FeedViewModel) {
    var showConsole by rememberSaveable { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    if (showConsole) {
        MusicConsoleScreen(onExit = { showConsole = false })
        return
    }
    val ctx = LocalContext.current
    val art by produceState<GrimArt?>(null) { value = ArtLoader.load(ctx) }
    val subFeeds by vm.subFeeds.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val favCategory by vm.favCategory.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val archiveProgress by vm.archiveProgress.collectAsStateWithLifecycle()

    var showAddChannel by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SubFeedEntity?>(null) }
    val channels by vm.channels.collectAsStateWithLifecycle()
    val hiddenChannels by vm.hiddenChannels.collectAsStateWithLifecycle()
    val videoChannelTitles by vm.videoChannelTitles.collectAsStateWithLifecycle()
    var purgeTarget by remember { mutableStateOf<VideoEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    val activeSwitch = when {
        busy != null || showConfig -> 3
        showAddChannel -> 1
        filter == FeedFilter.Favorites -> 2
        else -> 0
    }

    fun onSwitch(i: Int) {
        when (i) {
            0 -> vm.select(FeedFilter.All)
            1 -> showAddChannel = true
            2 -> vm.select(if (filter == FeedFilter.Favorites) FeedFilter.All else FeedFilter.Favorites)
            3 -> showConfig = true
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Grim.Obsidian)) {
        val W = maxWidth
        val H = maxHeight
        val a = art
        if (a == null) {
            Text("INITIALISING COGITATOR...", Modifier.align(Alignment.Center), color = Grim.Phosphor, fontFamily = Mono, fontSize = 12.sp)
            return@BoxWithConstraints
        }
        val vp = a.viewport

        // ── Base + middle layer: CRT viewport ──────────────────────────────
        Box(
            Modifier.offset(W * vp.left, H * vp.top).size(W * vp.width, H * vp.height)
                .clipToBounds().background(Grim.Obsidian)
        ) {
            Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                val favMode = filter == FeedFilter.Favorites
                val archivedMode = filter == FeedFilter.Archived
                CategoryStrip(
                    subFeeds = subFeeds,
                    selectedId = if (favMode) favCategory else (filter as? FeedFilter.Sub)?.id,
                    favMode = favMode,
                    archivedMode = archivedMode,
                    onArchived = { vm.select(FeedFilter.Archived) },
                    onSelect = { id ->
                        if (favMode) vm.selectFavCategory(id)
                        else vm.select(if (id == null) FeedFilter.All else FeedFilter.Sub(id))
                    },
                    onLongPress = { deleteTarget = it }
                )
                VideoList(
                    videos, busy != null, vm::refresh,
                    { id -> videos.firstOrNull { it.videoId == id }?.let {
                        try {
                            vm.markRead(id)
                            ctx.startActivity(
                                android.content.Intent(ctx, PictFeedActivity::class.java)
                                    .putExtra(PictFeedActivity.VIDEO_ID, it.videoId)
                                    .putExtra(PictFeedActivity.VIDEO_TITLE, it.title)
                                    .putExtra(PictFeedActivity.VIDEO_DESCRIPTION, it.description)
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("VoxFeedCrash", "Opening video failed", e)
                            android.widget.Toast.makeText(ctx, "SIGNAL FAULT: ${e.message ?: e.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    } },
                    vm::setFavorite, vm::markRead, { id -> purgeTarget = videos.firstOrNull { it.videoId == id } }, archiveProgress
                )
            }
            CrtOverlay(Modifier.fillMaxSize())
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            busy?.let { DiagnosticOverlay(it, Modifier.fillMaxSize()) }
        }

        // ── Foreground overlays (no pointer handlers => touches fall through) ──
        Image(a.bezel, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Box(Modifier.fillMaxWidth().aspectRatio(a.header.width / a.header.height.toFloat()).align(Alignment.TopCenter)) {
            Image(a.header, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            HeaderEffects(Modifier.fillMaxSize())
            // Hidden entry: double-tap the Servo-Skull emblem (bionic eye) to open WAAAGH-BOX.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val hw = maxWidth; val hh = maxHeight
                val sz = hw * 0.26f
                Box(
                    Modifier.offset(hw * 0.443f - sz / 2, hh * 0.368f - sz / 2).size(sz)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClick = {},
                            onDoubleClick = {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                showConsole = true
                            }
                        )
                )
            }
        }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = H * 0.165f)
                .fillMaxWidth().aspectRatio(a.footer.width / a.footer.height.toFloat())
        ) {
            Image(a.footer, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            AltarEffects(Modifier.fillMaxSize())
        }

        // ── Hardware switches ──────────────────────────────────────────────
        SwitchGlow(activeSwitch, Modifier.fillMaxSize())
        navLabels.indices.forEach { i ->
            Box(
                Modifier
                    .offset(W * (switchCenters[i] - SWITCH_WIDTH / 2), H * SWITCH_TOP)
                    .size(W * SWITCH_WIDTH, H * SWITCH_HEIGHT)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSwitch(i) }
            )
        }
    }

    if (showAddChannel) AddChannelSheet(
        subFeeds, (filter as? FeedFilter.Sub)?.id,
        onDismiss = { showAddChannel = false },
        onSave = { input, sub, ex -> vm.addChannel(input, sub, ex); showAddChannel = false },
        onAddCategory = vm::addSubFeed
    )
    if (showConfig) CogitatorConfigModal(
        syncing = busy != null,
        channels = channels, subFeeds = subFeeds, hiddenChannels = hiddenChannels, videoChannelTitles = videoChannelTitles,
        onToggleHidden = vm::toggleHidden,
        onToggleChannelAlerts = vm::setChannelAlerts,
        onLookbackChange = vm::setLookbackHours,
        onDeleteChannel = vm::deleteChannel,
        onSync = { if (busy == null) vm.refreshAll(); showConfig = false },
        onDismiss = { showConfig = false }
    )
    purgeTarget?.let { v ->
        PurgeConfirmDialog(v.title, { purgeTarget = null }, { vm.purgeArchive(v.videoId); purgeTarget = null })
    }
    deleteTarget?.let { t ->
        GrimDialog(onDismiss = { deleteTarget = null }) {
            GrimLabel("// PURGE CATEGORY?", Grim.Phosphor, 13.sp)
            GrimLabel("\"${t.name.uppercase()}\"", Grim.Cream, 12.sp)
            GrimLabel("ITS CHANNELS AND VIDEOS WILL BE EXPUNGED FROM THE DATA-VAULT.", Grim.Cream, 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GrimButton("[ ABORT ]", { deleteTarget = null }, Modifier.weight(1f))
                GrimButton("[ PURGE ]", { vm.deleteSubFeed(t.id); deleteTarget = null }, Modifier.weight(1f))
            }
        }
    }
}

// ── Compact CRT controls ────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonoButton(label: String, onClick: () -> Unit, selected: Boolean = false, onLongClick: (() -> Unit)? = null) {
    Text(
        label,
        Modifier
            .border(1.dp, if (selected) Grim.Phosphor else Grim.Brass, RoundedCornerShape(2.dp))
            .background(if (selected) Grim.Phosphor.copy(alpha = 0.15f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        color = if (selected) Grim.Phosphor else Grim.Cream,
        fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1
    )
}

/**
 * The single slim category strip. In feed mode it picks the sub-feed; in favorites mode it filters
 * favorites by each video's subFeedId. Long-pressing a category chip asks to delete it.
 */
@Composable
private fun CategoryStrip(
    subFeeds: List<SubFeedEntity>, selectedId: Long?, favMode: Boolean, archivedMode: Boolean,
    onArchived: () -> Unit, onSelect: (Long?) -> Unit, onLongPress: (SubFeedEntity) -> Unit
) {
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (favMode) item { Text("CAT>", color = Grim.Dim, fontFamily = Mono, fontSize = 10.sp) }
        item { MonoButton("ALL", { onSelect(null) }, selectedId == null && !archivedMode) }
        items(subFeeds, key = { it.id }) { sf ->
            MonoButton(sf.name.uppercase(), { onSelect(sf.id) }, selectedId == sf.id && !archivedMode, onLongClick = { onLongPress(sf) })
        }
        if (!favMode) item { MonoButton("ARCHIVED", onArchived, archivedMode) }
    }
}
// dark emerald (#0D1F12) -> phosphor green (#9EE052) duotone
private val duotone = ColorMatrix(
    floatArrayOf(
        0.2126f * 145 / 255, 0.7152f * 145 / 255, 0.0722f * 145 / 255, 0f, 13f,
        0.2126f * 193 / 255, 0.7152f * 193 / 255, 0.0722f * 193 / 255, 0f, 31f,
        0.2126f * 64 / 255, 0.7152f * 64 / 255, 0.0722f * 64 / 255, 0f, 18f,
        0f, 0f, 0f, 1f, 0f
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoList(
    videos: List<VideoEntity>, refreshing: Boolean, onRefresh: () -> Unit, onOpen: (String) -> Unit,
    onFav: (String, Boolean) -> Unit, onRead: (String, Boolean) -> Unit, onPurge: (String) -> Unit,
    progress: Map<String, Int> = emptyMap()
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing, onRefresh = onRefresh, state = state, modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state, isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = Grim.Obsidian, color = Grim.Phosphor
            )
        }
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (videos.isEmpty()) item {
                Box(Modifier.fillParentMaxSize(), Alignment.Center) {
                    Text("NO SIGNAL", color = Grim.Dim, fontFamily = Mono, fontSize = 12.sp)
                }
            }
            items(videos, key = { it.videoId }) { v -> VideoCard(v, onOpen, onFav, onRead, onPurge, progress[v.videoId]) }
        }
    }
}
@Composable
private fun VideoCard(
    v: VideoEntity, onOpen: (String) -> Unit,
    onFav: (String, Boolean) -> Unit, onRead: (String, Boolean) -> Unit, onPurge: (String) -> Unit,
    archivePct: Int? = null
) {
    val shape = RoundedCornerShape(2.dp)
    Row(
        Modifier.fillMaxWidth()
            .background(Grim.Card, shape).border(1.dp, Grim.Brass, shape)
            .clickable { onOpen(v.videoId) }.padding(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = v.thumbnailUrl, contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.colorMatrix(duotone),
            modifier = Modifier.width(84.dp).aspectRatio(16f / 9f).alpha(if (v.isRead) 0.5f else 1f).border(1.dp, Grim.Brass, shape)
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f).alpha(if (v.isRead) 0.55f else 1f)) {
            Text(
                v.title, color = Grim.Cream, fontFamily = Condensed, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(v.channelTitle, color = Grim.Phosphor, fontFamily = Mono, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(relativeTime(v.publishedAt), color = Grim.Dim, fontFamily = Mono, fontSize = 9.sp)
            if (archivePct != null && !v.isArchived) {
                Text(
                    "ARCHIVING: ${archivePct.coerceIn(0, 99)}%", Modifier.padding(top = 3.dp),
                    color = Grim.Phosphor, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 9.sp
                )
            }
            if (v.isArchived) {
                Text(
                    "[ PURGE ]",
                    Modifier.padding(top = 3.dp).border(1.dp, Grim.Brass, RoundedCornerShape(2.dp))
                        .clickable { onPurge(v.videoId) }.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Grim.Cream, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AquilaIcon(
                v.isFavorite,
                Modifier.size(34.dp).clickable { onFav(v.videoId, !v.isFavorite) }.padding(4.dp)
            )
            PuritySealBadge(
                v.isRead,
                Modifier.size(34.dp).clickable { onRead(v.videoId, !v.isRead) }.padding(4.dp)
            )
        }
    }
}

private val AquilaGold = Color(0xFFD4AF37)
private val WeatheredIron = Color(0xFF3A3F3A)
private val PurityGreen = Color(0xFF00FF41)
private val ObsidianInk = Color(0xFF0A0D0A)
private val Crimson = Color(0xFFB3202A)

/** Imperial Aquila: glowing brass-gold when favorited, dim weathered iron otherwise. */
@Composable
private fun AquilaIcon(favorited: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val ox = (size.width - 24f * s) / 2f
        val oy = (size.height - 24f * s) / 2f
        fun Path.poly(pts: List<Pair<Float, Float>>) {
            pts.forEachIndexed { i, (x, y) ->
                val px = ox + x * s; val py = oy + y * s
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
        fun mirror(p: List<Pair<Float, Float>>) = p.map { (24f - it.first) to it.second }
        val wing = listOf(
            9.6f to 9.2f, 2.5f to 4.5f, 3.2f to 8f, 1.2f to 9.6f, 3.6f to 11.6f,
            2.6f to 14.2f, 5.4f to 14.8f, 4.8f to 17.4f, 9.9f to 14f
        )
        val torso = listOf(12f to 6.4f, 14.6f to 9.2f, 13.6f to 16.4f, 12f to 19.2f, 10.4f to 16.4f, 9.4f to 9.2f)
        val tail = listOf(10.4f to 19f, 9.2f to 22.6f, 12f to 21f, 14.8f to 22.6f, 13.6f to 19f)
        val beakL = listOf(7.9f to 4.7f, 5.6f to 6f, 8f to 6.7f)
        val path = Path().apply {
            poly(wing); poly(mirror(wing)); poly(torso); poly(tail); poly(beakL); poly(mirror(beakL))
        }
        val color = if (favorited) AquilaGold else WeatheredIron
        if (favorited) {
            drawPath(path, AquilaGold.copy(alpha = 0.35f), style = Stroke(width = 3.2f * s, join = StrokeJoin.Round))
        }
        drawPath(path, color)
        drawCircle(color, 1.7f * s, Offset(ox + 9.4f * s, oy + 5.2f * s))
        drawCircle(color, 1.7f * s, Offset(ox + 14.6f * s, oy + 5.2f * s))
        // etched detail: eye sockets and a spine line
        val etch = if (favorited) ObsidianInk else Color(0xFF1B1F1B)
        drawCircle(etch, 0.5f * s, Offset(ox + 9.0f * s, oy + 5.0f * s))
        drawCircle(etch, 0.5f * s, Offset(ox + 15.0f * s, oy + 5.0f * s))
        drawLine(etch, Offset(ox + 12f * s, oy + 8.5f * s), Offset(ox + 12f * s, oy + 18f * s), 0.9f * s)
    }
}

/** Purity Seal: bright phosphor-green wax rosette with a bold check-rune and obsidian border when watched. */
@Composable
private fun PuritySealBadge(watched: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        val rosette = Path().apply {
            val n = 16
            for (i in 0 until n * 2) {
                val a = Math.PI * i / n - Math.PI / 2
                val rad = if (i % 2 == 0) r else r * 0.84f
                val x = c.x + (rad * Math.cos(a)).toFloat(); val y = c.y + (rad * Math.sin(a)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        val check = Path().apply {
            moveTo(c.x - r * 0.42f, c.y + r * 0.02f)
            lineTo(c.x - r * 0.1f, c.y + r * 0.36f)
            lineTo(c.x + r * 0.46f, c.y - r * 0.34f)
        }
        if (watched) {
            drawPath(rosette, PurityGreen.copy(alpha = 0.3f), style = Stroke(width = r * 0.35f, join = StrokeJoin.Round))
            drawPath(rosette, PurityGreen)
            drawPath(rosette, ObsidianInk, style = Stroke(width = r * 0.14f, join = StrokeJoin.Round))
            drawCircle(ObsidianInk, r * 0.62f, c, style = Stroke(width = r * 0.08f))
            drawPath(check, ObsidianInk, style = Stroke(width = r * 0.24f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawPath(rosette, WeatheredIron, style = Stroke(width = r * 0.1f, join = StrokeJoin.Round))
            drawPath(check, WeatheredIron.copy(alpha = 0.6f), style = Stroke(width = r * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Obsidian-and-brass confirmation before an archived data-slate is expunged. */
@Composable
fun PurgeConfirmDialog(videoTitle: String, onAbort: () -> Unit, onExpunge: () -> Unit) {
    GrimDialog(onDismiss = onAbort) {
        GrimLabel("// CONFIRM DATA-SLATE EXPUNGEMENT //", Grim.Phosphor, 13.sp)
        Text(
            "Permanently purge local archive for '$videoTitle'? This action cannot be undone.",
            color = Grim.Cream, fontFamily = Mono, fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GrimButton("[ ABORT ]", onAbort, Modifier.weight(1f))
            GrimButton("[ EXPUNGE ]", onExpunge, Modifier.weight(1f), borderColor = Crimson)
        }
    }
}

// ── Grimdark cogitator dialogs ──────────────────────────────────────────────

private val PlateShape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp)

@Composable
fun GrimLabel(text: String, color: Color = Grim.Cream, size: androidx.compose.ui.unit.TextUnit = 11.sp) {
    Text(text.uppercase(), color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = size)
}

/** Obsidian terminal panel: brass border, scanlines and corner rivets. Dismisses on scrim tap / back. */
@Composable
fun GrimDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .imePadding().systemBarsPadding().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val shape = RoundedCornerShape(2.dp)
            Box(
                Modifier.fillMaxWidth().clip(shape).background(Grim.Obsidian).border(1.5.dp, Grim.Brass, shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content
                )
                CrtOverlay(Modifier.matchParentSize(), vignette = false)
                Canvas(Modifier.matchParentSize()) {
                    val inset = 6.dp.toPx(); val r = 2.2.dp.toPx()
                    listOf(
                        Offset(inset, inset), Offset(size.width - inset, inset),
                        Offset(inset, size.height - inset), Offset(size.width - inset, size.height - inset)
                    ).forEach {
                        drawCircle(Grim.Brass, r, it)
                        drawCircle(Grim.BrassBright.copy(alpha = 0.7f), r * 0.4f, it - Offset(r * 0.25f, r * 0.25f))
                    }
                }
            }
        }
    }
}

@Composable
fun GrimButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    borderColor: Color = Grim.Brass
) {
    Box(
        modifier.alpha(if (enabled) 1f else 0.4f)
            .clip(PlateShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF3A3220), Color(0xFF1E1A10))))
            .border(1.5.dp, borderColor, PlateShape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) { GrimLabel(label, if (enabled) Grim.Phosphor else Grim.Dim, 12.sp) }
}

@Composable
private fun GrimField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GrimLabel(label, Grim.Cream, 10.sp)
        val shape = RoundedCornerShape(2.dp)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = Grim.Phosphor, fontFamily = Mono, fontSize = 13.sp),
            cursorBrush = SolidColor(Grim.Phosphor),
            modifier = Modifier.fillMaxWidth().background(Grim.Card, shape).border(1.dp, Grim.Brass, shape)
                .padding(horizontal = 10.dp, vertical = 10.dp)
        )
    }
}

@Composable
fun GrimChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(2.dp)
    Text(
        label.uppercase(),
        Modifier.border(1.5.dp, if (selected) Grim.Phosphor else Grim.Brass, shape)
            .background(if (selected) Grim.Phosphor.copy(alpha = 0.15f) else Grim.Card, shape)
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 6.dp),
        color = if (selected) Grim.Phosphor else Grim.Cream,
        fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1
    )
}

@Composable
fun AddChannelSheet(
    subFeeds: List<SubFeedEntity>, preselect: Long?,
    onDismiss: () -> Unit, onSave: (String, Long, Boolean) -> Unit, onAddCategory: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(subFeeds.firstOrNull { it.id == preselect } ?: subFeeds.firstOrNull()) }
    var excludeShorts by remember { mutableStateOf(true) }
    var newCategory by remember { mutableStateOf("") }
    var pendingName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(subFeeds) {
        pendingName?.let { p ->
            subFeeds.firstOrNull { it.name == p }?.let { selected = it; pendingName = null }
        }
    }

    GrimDialog(onDismiss) {
        GrimLabel("// EXTRACT CHANNEL DATA-STREAM", Grim.Phosphor, 13.sp)
        GrimField(input, { input = it }, "@HANDLE, URL OR UC… ID")
        GrimLabel("TARGET CATEGORY", Grim.Cream, 10.sp)
        if (subFeeds.isEmpty()) GrimLabel("NO CATEGORIES FORGED", Grim.Dim, 10.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            subFeeds.forEach { GrimChip(it.name, selected?.id == it.id) { selected = it } }
        }
        GrimField(newCategory, { newCategory = it }, "NEW CATEGORY")
        GrimButton(
            "[ + FORGE CATEGORY ]",
            { pendingName = newCategory.trim(); onAddCategory(newCategory.trim()); newCategory = "" },
            Modifier.fillMaxWidth(), enabled = newCategory.isNotBlank()
        )
        GrimChip(if (excludeShorts) "[X] EXCLUDE SHORTS" else "[ ] EXCLUDE SHORTS", excludeShorts) { excludeShorts = !excludeShorts }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GrimButton("[ ABORT ]", onDismiss, Modifier.weight(1f))
            GrimButton(
                "[ INITIATE EXTRACTION ]", { selected?.let { onSave(input, it.id, excludeShorts) } },
                Modifier.weight(2f), enabled = input.isNotBlank() && selected != null
            )
        }
    }
}

fun relativeTime(ts: Long, now: Long = System.currentTimeMillis()): String {
    if (ts <= 0) return ""
    val m = ((now - ts) / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m}m ago"
        m < 1440 -> "${m / 60}h ago"
        m < 43200 -> "${m / 1440}d ago"
        m < 525600 -> "${m / 43200}mo ago"
        else -> "${m / 525600}y ago"
    }
}
