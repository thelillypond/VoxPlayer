package com.voxfeed.app.ui

import android.content.ComponentName
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.voxfeed.app.VoxPlaybackService
import com.voxfeed.app.data.YouTubeStreamExtractor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.voxfeed.app.data.*

/** Immutable snapshot of the shared player, rebuilt on every Player event / progress tick. */
private data class DeckState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val mediaId: String = "",
    val title: String = "",
    val artist: String = "",
    val artwork: String? = null,
    val cached: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val hasMedia: Boolean = false,
    val index: Int = 0
)

private fun MediaController.snapshot(): DeckState {
    val item = currentMediaItem
    val md = item?.mediaMetadata
    val dur = duration.takeIf { it != androidx.media3.common.C.TIME_UNSET && it > 0 } ?: 0L
    return DeckState(
        connected = true,
        isPlaying = isPlaying,
        mediaId = item?.mediaId.orEmpty(),
        title = md?.title?.toString().orEmpty(),
        artist = md?.artist?.toString().orEmpty(),
        artwork = md?.artworkUri?.toString(),
        cached = item?.requestMetadata?.extras?.getBoolean(VoxPlaybackService.EXTRA_CACHED, false) == true,
        positionMs = currentPosition.coerceAtLeast(0),
        durationMs = dur,
        shuffle = shuffleModeEnabled,
        repeat = repeatMode,
        hasMedia = item != null,
        index = currentMediaItemIndex
    )
}

private data class QueueRow(val title: String, val artist: String, val cached: Boolean)

private fun MediaController.queueSnapshot(): List<QueueRow> = (0 until mediaItemCount).map {
    val item = getMediaItemAt(it)
    QueueRow(
        item.mediaMetadata.title?.toString() ?: "Unknown Track",
        item.mediaMetadata.artist?.toString().orEmpty(),
        item.requestMetadata.extras?.getBoolean(VoxPlaybackService.EXTRA_CACHED, false) == true
    )
}

internal fun mmss(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return "%02d:%02d".format(s / 60, s % 60)
}



/** True when [uri] (content:// or file://) can still be opened. */
internal fun localFileExists(ctx: android.content.Context, uri: String): Boolean = runCatching {
    ctx.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
}.getOrDefault(false)

/** Builds a queue item. Local file when cached + present, otherwise the watch URL (resolved lazily by the service). */
private fun buildMusicItem(t: MusicTrackEntity, local: String?, playlistName: String?): MediaItem {
    val play = local ?: t.sourceUrl
    return MediaItem.Builder()
        .setMediaId("music:${t.id}")
        .setUri(play)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title.takeIf { !it.isNullOrBlank() && it != "null" } ?: "Unknown Audio")
                .setArtist(t.artist.takeIf { !it.isNullOrBlank() && it != "null" } ?: "Unknown Artist")
                .setAlbumTitle(playlistName.takeIf { !it.isNullOrBlank() && it != "null" } ?: "VoxPlayer")
                .setArtworkUri(t.thumbnailUrl?.let(Uri::parse)).build()
        )
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setExtras(
                VoxPlaybackService.extras(t.id.toString(), "", listOf(play)).apply {
                    putBoolean(VoxPlaybackService.EXTRA_MUSIC, true)
                    putBoolean(VoxPlaybackService.EXTRA_CACHED, local != null)
                }
            ).build()
        )
        .build()
}

/** Entry point: main audio deck, or the Library manager. Active playlist is shared between both. */
@Composable
fun MusicConsoleScreen(onExit: () -> Unit) {
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    var activeId by rememberSaveable { mutableStateOf(-1L) }
    Box(Modifier.fillMaxSize()) {
        if (showLibrary) {
            BackHandler { showLibrary = false }
            LibraryScreen(activeId, { activeId = it }, onBack = { showLibrary = false })
        } else {
            MusicConsoleMain(onExit, activeId, { activeId = it }, { showLibrary = true })
        }
        StatusBannerHost()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MusicConsoleMain(onExit: () -> Unit, activeId: Long, setActiveId: (Long) -> Unit, openLibrary: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val dao = remember { VoxDatabase.get(ctx).musicDao() }

    BackHandler { onExit() } // audio keeps playing in VoxPlaybackService

    // ── MediaController bound to the shared VoxPlaybackService ──
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var deck by remember { mutableStateOf(DeckState()) }
    var queue by remember { mutableStateOf<List<QueueRow>>(emptyList()) }
    DisposableEffect(Unit) {
        val token = SessionToken(ctx, ComponentName(ctx, VoxPlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        var listener: Player.Listener? = null
        future.addListener({
            runCatching { future.get() }.onSuccess { c ->
                val l = object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        val mc = player as MediaController
                        deck = mc.snapshot()
                        if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) queue = mc.queueSnapshot()
                    }
                }
                c.addListener(l)
                listener = l
                controller = c
                deck = c.snapshot()
                queue = c.queueSnapshot()
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            // Releasing the controller does NOT stop playback; the service owns the player.
            controller?.let { c -> listener?.let { c.removeListener(it) } }
            MediaController.releaseFuture(future)
            controller = null
        }
    }
    LaunchedEffect(controller, deck.isPlaying) {
        val c = controller ?: return@LaunchedEffect
        while (deck.isPlaying) { deck = c.snapshot(); delay(500) }
    }

    // ── Room: playlists + tracks ──
    LaunchedEffect(Unit) { dao.ensureDefaultPlaylist() }
    val playlists by dao.observePlaylists().collectAsState(initial = emptyList())
    val allTracks by dao.observeAllTracks().collectAsState(initial = emptyList())
    val favMode = activeId == MusicDao.FAV_ID
    val active = playlists.firstOrNull { it.id == activeId } ?: playlists.firstOrNull()
    val activePid = active?.id ?: -1L
    var genreFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var menuTrack by remember { mutableStateOf<MusicTrackEntity?>(null) }
    val tracks = remember(allTracks, activePid, favMode, genreFilter) {
        val base = if (favMode) allTracks.filter { it.isFavorite }.distinctBy { it.sourceUrl } else allTracks.filter { it.playlistId == activePid }
        genreFilter?.let { g -> base.filter { it.genre == g } } ?: base
    }
    // Live cached-file lookup: any copy of a source being cached flips the badge for every row.
    val cachedBySource = remember(allTracks) { allTracks.filter { it.localUri != null }.associate { it.sourceUrl to it.localUri!! } }
    val counts = remember(allTracks) { allTracks.groupingBy { it.playlistId }.eachCount() }
    val uniqueCount = remember(allTracks) { allTracks.distinctBy { it.sourceUrl }.size }
    val dl by MusicDownloader.progress.collectAsState()

    var tab by remember { mutableStateOf(0) } // 0 = playlist, 1 = active queue
    var status by remember { mutableStateOf<String?>(null) }
    var sheetUrl by remember { mutableStateOf<String?>(null) } // non-null => add-audio dialog open
    var showNewPlaylist by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { MusicDownloader.messages.collect { status = it } }

    /** Builds queue items; cached files that vanished fall back to streaming. */
    suspend fun toItems(list: List<MusicTrackEntity>): List<MediaItem> = withContext(Dispatchers.IO) {
        list.map { t ->
            val local = t.localUri?.takeIf { localFileExists(ctx, it) }
            if (t.localUri != null && local == null) dao.setLocalUri(t.id, null)
            buildMusicItem(t, local, active?.name)
        }
    }

    /** Row tap: plays this track now by replacing the current queue slot; upcoming queue is preserved. */
    fun playSingle(index: Int) {
        val c = controller ?: run { status = "Player unavailable"; return }
        val t = tracks.getOrNull(index) ?: return
        scope.launch {
            val item = toItems(listOf(t)).first()
            if (c.mediaItemCount == 0) {
                c.setMediaItem(item); c.prepare(); c.play()
            } else {
                // Swap only the current slot; every queued (+ Queue) track after it stays put.
                val idx = c.currentMediaItemIndex.coerceIn(0, c.mediaItemCount - 1)
                c.replaceMediaItem(idx, item)
                c.seekTo(idx, 0L); c.prepare(); c.play()
            }
        }
    }

    /** Play All: loads the whole active playlist into the queue. */
    fun playAll() {
        val c = controller ?: run { status = "Player unavailable"; return }
        val snapshot = tracks
        if (snapshot.isEmpty()) return
        scope.launch {
            val items = toItems(snapshot)
            c.stop(); c.setMediaItems(items, 0, 0L); c.prepare(); c.play()
            tab = 1
        }
    }

    /** Every unique track across all playlists, shuffled. */
    fun shuffleAll() {
        val c = controller ?: run { status = "Player unavailable"; return }
        val snapshot = allTracks.distinctBy { it.sourceUrl }.shuffled()
        if (snapshot.isEmpty()) return
        scope.launch {
            val items = toItems(snapshot)
            c.stop(); c.setMediaItems(items, 0, 0L); c.prepare(); c.play()
            tab = 1
        }
    }

    /** Jukebox: append one track without interrupting what is playing. */
    fun enqueue(t: MusicTrackEntity) {
        val c = controller ?: run { status = "Player unavailable"; return }
        scope.launch {
            val item = toItems(listOf(t)).first()
            if (c.mediaItemCount == 0) {
                c.setMediaItem(item); c.prepare(); c.play()
            } else {
                val ended = c.playbackState == Player.STATE_ENDED
                c.addMediaItem(item)
                if (ended) { c.seekTo(c.mediaItemCount - 1, 0L); c.play() }
            }
            status = "Queued: ${t.title}"
        }
    }

    Column(
        Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding().navigationBarsPadding()
            .deckBackground().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ── Single-row header ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Audio Deck", Modifier.weight(1f).padding(horizontal = 6.dp), color = Palette.Text,
                fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 20.sp, maxLines = 1
            )
            ActionButton("Library", { openLibrary() }, accent = true, height = 36.dp, size = 12, hPad = 10.dp, vPad = 4.dp)
            Spacer(Modifier.width(6.dp))
            ActionButton("Videos", { onExit() }, height = 36.dp, size = 12, hPad = 10.dp, vPad = 4.dp)
        }
        LaunchedEffect(status) { status?.let { StatusBanner.show(it); status = null } }

        NowPlaying(deck, controller)
        Transport(deck, controller)

        // ── Tab toggle ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabBtn("Playlist (${tracks.size})", tab == 0, Modifier.weight(1f)) { tab = 0 }
            TabBtn("Active Queue (${queue.size})", tab == 1, Modifier.weight(1f)) { tab = 1 }
        }
        if (tab == 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistSelector(if (favMode) "★ Favourites" else active?.name ?: "---", tracks.size, Modifier.weight(1f)) { showPicker = true }
            ActionButton("Play All", { playAll() }, enabled = tracks.isNotEmpty(), accent = true, height = 34.dp, size = 11, hPad = 8.dp, vPad = 4.dp)
        }
        if (tab == 0) genreFilter?.let { g -> Row { Chip("Genre: $g X", true) { genreFilter = null } } }

        Box(Modifier.weight(1f).fillMaxWidth().background(Palette.Surface).panelFrame()) {
            if (tab == 0) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (tracks.isEmpty()) item {
                        Text("No tracks yet. Tap + Add Audio below.", color = Palette.Dim, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                    }
                    itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                        val current = deck.mediaId == "music:${t.id}"
                        val pct = dl[t.id]
                        val cached = t.localUri != null || cachedBySource.containsKey(t.sourceUrl)
                        Row(
                            Modifier.fillMaxWidth().background(if (current) Palette.Bg else Color.Transparent)
                                .combinedClickable(onClick = { playSingle(i) }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuTrack = t })
                                .padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (current) "▶" else "%02d".format(i + 1), color = if (current) Palette.Accent else Palette.Dim,
                                fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.width(22.dp)
                            )
                            Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FavStar(t.isFavorite)
                                    Text(t.title, color = if (current) Palette.Accent else Color.White, fontFamily = Heavy, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(
                                        if (cached) "Cached" else "Stream",
                                        color = if (cached) Palette.Accent else Palette.Amber, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 8.sp, maxLines = 1,
                                        modifier = Modifier.border(1.dp, if (cached) Palette.Accent else Palette.Amber).padding(horizontal = 3.dp)
                                    )
                                    GenrePill(t.genre) { genreFilter = t.genre }
                                    Text(t.artist, color = Palette.Dim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    Text(if (t.durationMs > 0) mmss(t.durationMs) else "--:--", color = Palette.Dim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
                                }
                            }
                            Box(
                                Modifier.defaultMinSize(minWidth = 36.dp, minHeight = 32.dp).clip(ButtonShape).border(1.dp, Palette.Border, ButtonShape).clickable { enqueue(t) },
                                contentAlignment = Alignment.Center
                            ) { Text("+ Queue", color = Palette.Accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 3.dp)) }
                            if (!cached) Box(
                                Modifier.defaultMinSize(minWidth = 34.dp, minHeight = 40.dp)
                                    .clickable(enabled = pct == null) {
                                        MusicDownloader.enqueue(ctx, t, active?.name ?: MusicDao.DEFAULT_PLAYLIST)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(if (pct != null) "$pct%" else "⬇", color = Palette.Amber, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = if (pct != null) 10.sp else 16.sp)
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                ActionButton(
                    "Clear Queue", { controller?.let { it.stop(); it.clearMediaItems() } },
                    Modifier.fillMaxWidth().padding(3.dp), enabled = queue.isNotEmpty(), height = 32.dp, size = 11, vPad = 4.dp
                )
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (queue.isEmpty()) item {
                        Text("Queue is empty. Tap + Queue on a track.", color = Palette.Dim, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                    }
                    itemsIndexed(queue) { i, q ->
                        val current = i == deck.index && deck.hasMedia
                        Row(
                            Modifier.fillMaxWidth().background(if (current) Palette.Bg else Color.Transparent)
                                .then(if (current) Modifier.equalizerUnderlay() else Modifier)
                                .clickable { controller?.let { it.seekTo(i, 0L); it.play() } }.padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (current) "▶" else "%02d".format(i + 1), color = if (current) Palette.Accent else Palette.Dim,
                                fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.width(22.dp)
                            )
                            Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                                Text(q.title, color = if (current) Palette.Accent else Color.White, fontFamily = Heavy, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    q.artist,
                                    color = Palette.Dim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box(
                                Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp).clickable { controller?.removeMediaItem(i) },
                                contentAlignment = Alignment.Center
                            ) { Text("X", color = Palette.Alert, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 15.sp) }
                        }
                    }
                }
                }
            }
        }
        ActionButton("+ Add Audio", { sheetUrl = "" }, Modifier.fillMaxWidth(), accent = true, height = 40.dp)

    }

    // ── Dialogs / sheet ──
    if (showPicker) {
        PlaylistPickerDialog(
            playlists = playlists, counts = counts, activeId = if (favMode) MusicDao.FAV_ID else activePid,
            onPick = { setActiveId(it); genreFilter = null; tab = 0; showPicker = false },
            onNew = { showPicker = false; showNewPlaylist = true },
            onDismiss = { showPicker = false },
            top = {
                ActionButton("★ Favourites", { setActiveId(MusicDao.FAV_ID); genreFilter = null; tab = 0; showPicker = false }, Modifier.fillMaxWidth(), accent = favMode)
                ActionButton(
                    "Shuffle All ($uniqueCount)", { showPicker = false; shuffleAll() },
                    Modifier.fillMaxWidth(), enabled = uniqueCount > 0, accent = true
                )
            }
        )
    }
    menuTrack?.let { mt ->
        TrackActionMenu(mt, playlists, counts, dao, scope) { menuTrack = null }
    }
    if (showNewPlaylist) {
        var name by remember { mutableStateOf("") }
        AppDialog({ showNewPlaylist = false }) {
            Text("New Playlist", color = Palette.Amber, fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 16.sp)
            NameField(name, { name = it }, "Playlist name")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Cancel", { showNewPlaylist = false }, Modifier.weight(1f))
                ActionButton("Create", {
                    scope.launch {
                        setActiveId(dao.insertPlaylist(MusicPlaylistEntity(name = name.trim(), createdAt = System.currentTimeMillis())))
                        showNewPlaylist = false
                    }
                }, Modifier.weight(1f), enabled = name.isNotBlank(), accent = true)
            }
        }
    }
    sheetUrl?.let { initial ->
        ExtractDialog(
            playlists = playlists, counts = counts, defaultPlaylistId = activePid, initialUrl = initial,
            onDismiss = { sheetUrl = null },
            onCommit = { picked, targetId, newName, download ->
                scope.launch {
                    val pid = targetId ?: dao.insertPlaylist(MusicPlaylistEntity(name = newName!!.trim(), createdAt = System.currentTimeMillis()))
                    val pname = dao.getPlaylist(pid)?.name ?: MusicDao.DEFAULT_PLAYLIST
                    val rows = dao.appendTracks(pid, picked.map {
                        MusicTrackEntity(
                            playlistId = pid, title = it.title, artist = it.artist, durationMs = it.durationMs,
                            sourceUrl = it.url, thumbnailUrl = it.thumbnailUrl, localUri = null, position = 0
                        )
                    })
                    if (download) rows.forEach { MusicDownloader.enqueue(ctx, it, pname) }
                    setActiveId(pid)
                    sheetUrl = null
                    status = if (download) "Caching ${rows.size} track(s)..." else "Added ${rows.size} track(s) to $pname"
                }
            }
        )
    }
}

/** Full-width `Name (n) ▼` playlist selector button (shared by deck + library). */
@Composable
internal fun PlaylistSelector(name: String, count: Int, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    ActionButton("${name} ($count) ▼", onClick, modifier, height = 34.dp, size = 11, hPad = 8.dp, vPad = 4.dp)
}

/** Ork-styled modal listing every playlist with its track count. */
@Composable
internal fun PlaylistPickerDialog(
    playlists: List<MusicPlaylistEntity>, counts: Map<Long, Int>, activeId: Long,
    onPick: (Long) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit,
    top: (@Composable ColumnScope.() -> Unit)? = null, title: String = "Select Playlist"
) {
    AppDialog(onDismiss) {
        Text(title, color = Palette.Amber, fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 16.sp)
        top?.invoke(this)
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 300.dp).background(Palette.Surface).panelFrame(),
            contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(playlists, key = { it.id }) { p ->
                val on = p.id == activeId
                Row(
                    Modifier.fillMaxWidth().background(if (on) Palette.Bg else Color.Transparent)
                        .border(if (on) 2.dp else 1.dp, if (on) Palette.Accent else Palette.Dim)
                        .clickable { onPick(p.id) }.padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        p.name, Modifier.weight(1f), color = if (on) Palette.Accent else Color.White,
                        fontFamily = Heavy, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("(${counts[p.id] ?: 0})", color = Palette.Amber, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
        ActionButton("+ New Playlist", onNew, Modifier.fillMaxWidth())
        ActionButton("Close", onDismiss, Modifier.fillMaxWidth(), height = 36.dp)
    }
}

@Composable
private fun TabBtn(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = if (selected) Palette.Accent else Palette.Amber
    Text(
        label, modifier.border(if (selected) 2.dp else 1.dp, c).background(if (selected) Palette.Bg else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 7.dp),
        color = c, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center
    )
}

/** Add-audio dialog: paste a URL, scan, pick tracks, then add them to a playlist. */
@Composable
private fun ExtractDialog(
    playlists: List<MusicPlaylistEntity>, counts: Map<Long, Int>, defaultPlaylistId: Long, initialUrl: String,
    onDismiss: () -> Unit,
    onCommit: (picked: List<ScannedTrack>, targetId: Long?, newName: String?, download: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf(initialUrl) }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scan by remember { mutableStateOf<ScanResult?>(null) }
    var checked by remember { mutableStateOf(setOf<Int>()) }
    var showTargetPicker by remember { mutableStateOf(false) }
    var targetId by remember { mutableStateOf(defaultPlaylistId.takeIf { it > 0 }) }
    var newMode by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var cacheOffline by remember { mutableStateOf(false) }

    fun doScan() {
        if (input.isBlank() || scanning) return
        scanning = true; error = null; scan = null
        scope.launch {
            try {
                val r = MusicExtractor.scan(input)
                scan = r
                checked = r.tracks.indices.toSet()
                // Importing a whole playlist: offer to create a playlist named after it.
                if (r.isPlaylist && newName.isBlank()) newName = r.title
                if (r.tracks.isEmpty()) error = "No audio found"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Scan failed: ${e.message ?: e.javaClass.simpleName}".take(140)
            } finally { scanning = false }
        }
    }
    LaunchedEffect(Unit) { if (initialUrl.isNotBlank()) doScan() }

    val s = scan
    val ready = s != null && checked.isNotEmpty() && (!newMode && targetId != null || newMode && newName.isNotBlank())
    fun commit() {
        val sc = scan ?: return
        val picked = sc.tracks.filterIndexed { i, _ -> i in checked }
        onCommit(picked, if (newMode) null else targetId, if (newMode) newName else null, cacheOffline)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .imePadding().systemBarsPadding().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 600.dp).deckBackground().panelFrame()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Extract Audio-Stream", color = Palette.Accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NameField(input, { input = it }, "Track or playlist URL...", Modifier.weight(1f))
                    ActionButton(if (scanning) "..." else "Scan", { doScan() }, enabled = input.isNotBlank() && !scanning, accent = true, height = 40.dp, size = 11)
                }
                error?.let { Text(it, color = Palette.Alert, fontFamily = Mono, fontSize = 11.sp) }
                ActionButton(
                    "Target playlist: ${if (newMode) "+ New " + newName.ifBlank { "Playlist" } else playlists.firstOrNull { it.id == targetId }?.name ?: "---"} ▼",
                    { showTargetPicker = true }, Modifier.fillMaxWidth(), height = 34.dp, size = 11, hPad = 8.dp, vPad = 4.dp
                )
                if (newMode) NameField(newName, { newName = it }, "New playlist name")
                if (showTargetPicker) PlaylistPickerDialog(
                    playlists = playlists, counts = counts, activeId = if (newMode) -1L else (targetId ?: -1L),
                    onPick = { newMode = false; targetId = it; showTargetPicker = false },
                    onNew = { newMode = true; showTargetPicker = false },
                    onDismiss = { showTargetPicker = false }
                )

                if (scanning) Text("Scanning...", color = Palette.Amber, fontFamily = Mono, fontSize = 12.sp)
                if (s != null && s.tracks.isNotEmpty()) {
                    Text(
                        "${s.title} | ${checked.size}/${s.tracks.size} Selected", color = Palette.Accent,
                        fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionButton("Select All", { checked = s.tracks.indices.toSet() }, height = 36.dp, size = 11)
                        ActionButton("Clear", { checked = emptySet() }, height = 36.dp, size = 11)
                        Spacer(Modifier.weight(1f))
                        Text("Quality: Max Bitrate Audio", color = Palette.Accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1, modifier = Modifier.border(1.dp, Palette.Accent).padding(horizontal = 4.dp, vertical = 3.dp))
                    }
                    LazyColumn(
                        Modifier.weight(1f, fill = false).fillMaxWidth().background(Color(0xFF070908)).panelFrame(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(s.tracks) { i, t ->
                            val on = i in checked
                            Row(
                                Modifier.fillMaxWidth().clickable { checked = if (on) checked - i else checked + i }.padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    on, { checked = if (it) checked + i else checked - i },
                                    colors = CheckboxDefaults.colors(checkedColor = Palette.Accent, uncheckedColor = Palette.Amber, checkmarkColor = Palette.Bg)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(t.title, color = Color.White, fontFamily = Heavy, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(t.artist, color = Palette.Dim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(if (t.durationMs > 0) mmss(t.durationMs) else "--:--", color = Palette.Dim, fontFamily = Mono, fontSize = 11.sp)
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { cacheOffline = !cacheOffline },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            cacheOffline, { cacheOffline = it },
                            colors = CheckboxDefaults.colors(checkedColor = Palette.Accent, uncheckedColor = Palette.Amber, checkmarkColor = Palette.Bg)
                        )
                        Text("Cache Offline (Local-Cache)", color = if (cacheOffline) Palette.Accent else Palette.Dim, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Cancel", onDismiss, Modifier.weight(1f))
                    ActionButton("Initiate Extraction", { commit() }, Modifier.weight(2f), enabled = ready, accent = true, size = 12)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlaying(deck: DeckState, controller: MediaController?) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(
        Modifier.fillMaxWidth().background(Palette.Surface).panelFrame(Palette.Accent).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).background(Palette.Bg).border(1.dp, Color.White),
                contentAlignment = Alignment.Center
            ) {
                if (deck.artwork != null) AsyncImage(deck.artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text("♪", color = Palette.Accent, fontSize = 28.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    deck.title.ifEmpty { "No Track Loaded" }, color = Color.White, fontFamily = Heavy,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(deck.artist.ifEmpty { "---" }, color = Palette.Dim, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val dur = deck.durationMs.coerceAtLeast(1)
        val frac = dragging ?: (deck.positionMs.toFloat() / dur).coerceIn(0f, 1f)
        val shownPos = dragging?.let { (it * deck.durationMs).toLong() } ?: deck.positionMs
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = frac,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { controller?.seekTo((it * deck.durationMs).toLong()) }
                    dragging = null
                },
                enabled = deck.hasMedia && deck.durationMs > 0,
                modifier = Modifier.weight(1f).height(30.dp),
                track = { ScrubTrack(it.value) },
                colors = SliderDefaults.colors(
                    thumbColor = Palette.Amber, activeTrackColor = Palette.Accent, inactiveTrackColor = Color(0xFF444444),
                    disabledThumbColor = Palette.Dim, disabledActiveTrackColor = Color(0xFF444444), disabledInactiveTrackColor = Color(0xFF333333)
                )
            )
            Text(
                "${mmss(shownPos)} / ${mmss(deck.durationMs)}", Modifier.padding(start = 6.dp),
                color = Palette.Accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Transport(deck: DeckState, c: MediaController?) {
    val on = deck.connected && c != null
    val h = 44.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionButton("Shuffle", { c?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }, Modifier.weight(1f), enabled = on, active = deck.shuffle, height = h, size = 11, hPad = 4.dp, vPad = 4.dp)
        ActionButton("|◀◀", { c?.seekToPreviousMediaItem() }, Modifier.weight(1f), enabled = on, height = h, size = 14, hPad = 4.dp, vPad = 4.dp)
        ActionButton(
            if (deck.isPlaying) "❚❚" else "▶",
            { c?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } } },
            Modifier.weight(1.4f), enabled = on && deck.hasMedia, accent = true, height = h, size = 20, hPad = 4.dp, vPad = 4.dp
        )
        ActionButton("▶▶|", { c?.seekToNextMediaItem() }, Modifier.weight(1f), enabled = on, height = h, size = 14, hPad = 4.dp, vPad = 4.dp)
    }
}
