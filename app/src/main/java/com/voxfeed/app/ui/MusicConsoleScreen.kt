package com.voxfeed.app.ui

import android.content.ComponentName
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CutCornerShape
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

// ── WAAAGH-BOX palette (function-first, no bitmap assets) ───────────────────
object Waaagh {
    val Iron = Color(0xFF0D0F0E)
    val IronLight = Color(0xFF151A17)
    val Hazard = Color(0xFFD49B27) // oxidized brass / amber
    val Green = Color(0xFF39FF14)
    val Dim = Color(0xFF8A8A8A)
    val Alert = Color(0xFFFF3B30)
    val Slate = Color(0xFF141712)       // terminal key fill
    val Slate2 = Color(0xFF1C2019)      // primary-action fill
    val BrassLo = Color(0xFF6E5D28)     // hairline border
    val BrassHi = Color(0xFFA8892E)     // primary border / rivets
    val Gold = Color(0xFFD4AF37)        // key text
}

internal val Heavy = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))
internal val Mono = FontFamily.Monospace
internal val Plate = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)


/** Immutable snapshot of the shared player, rebuilt on every Player event / progress tick. */
private data class DeckState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val mediaId: String = "",
    val title: String = "",
    val artist: String = "",
    val artwork: String? = null,
    val stashed: Boolean = false,
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
        stashed = item?.requestMetadata?.extras?.getBoolean(VoxPlaybackService.EXTRA_STASHED, false) == true,
        positionMs = currentPosition.coerceAtLeast(0),
        durationMs = dur,
        shuffle = shuffleModeEnabled,
        repeat = repeatMode,
        hasMedia = item != null,
        index = currentMediaItemIndex
    )
}

private data class QueueRow(val title: String, val artist: String, val stashed: Boolean)

private fun MediaController.queueSnapshot(): List<QueueRow> = (0 until mediaItemCount).map {
    val item = getMediaItemAt(it)
    QueueRow(
        item.mediaMetadata.title?.toString() ?: "UNKNOWN TRACK",
        item.mediaMetadata.artist?.toString().orEmpty(),
        item.requestMetadata.extras?.getBoolean(VoxPlaybackService.EXTRA_STASHED, false) == true
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

/** Builds a queue item. Local file when stashed + present, otherwise the watch URL (resolved lazily by the service). */
private fun buildMusicItem(t: MusicTrackEntity, local: String?, playlistName: String?): MediaItem {
    val play = local ?: t.sourceUrl
    return MediaItem.Builder()
        .setMediaId("music:${t.id}")
        .setUri(play)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title.takeIf { !it.isNullOrBlank() && it != "null" } ?: "Unknown Audio-Stream")
                .setArtist(t.artist.takeIf { !it.isNullOrBlank() && it != "null" } ?: "VoxFeed Audio")
                .setAlbumTitle(playlistName.takeIf { !it.isNullOrBlank() && it != "null" } ?: "Vox-Archive")
                .setArtworkUri(t.thumbnailUrl?.let(Uri::parse)).build()
        )
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setExtras(
                VoxPlaybackService.extras(t.id.toString(), "", listOf(play)).apply {
                    putBoolean(VoxPlaybackService.EXTRA_MUSIC, true)
                    putBoolean(VoxPlaybackService.EXTRA_STASHED, local != null)
                }
            ).build()
        )
        .build()
}

/** Entry point: main Mek-Amp deck, or the Stash Armoury manager. Active playlist is shared between both. */
@Composable
fun MusicConsoleScreen(onExit: () -> Unit) {
    var armoury by rememberSaveable { mutableStateOf(false) }
    var activeId by rememberSaveable { mutableStateOf(-1L) }
    Box(Modifier.fillMaxSize()) {
        if (armoury) {
            BackHandler { armoury = false }
            StashArmouryScreen(activeId, { activeId = it }, onBack = { armoury = false })
        } else {
            MusicConsoleMain(onExit, activeId, { activeId = it }, { armoury = true })
        }
        CogitatorBannerHost()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MusicConsoleMain(onExit: () -> Unit, activeId: Long, setActiveId: (Long) -> Unit, openArmoury: () -> Unit) {
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
    val counts = remember(allTracks) { allTracks.groupingBy { it.playlistId }.eachCount() }
    val uniqueCount = remember(allTracks) { allTracks.distinctBy { it.sourceUrl }.size }
    val dl by MusicDownloader.progress.collectAsState()

    var tab by remember { mutableStateOf(0) } // 0 = playlist, 1 = active queue
    var status by remember { mutableStateOf<String?>(null) }
    var sheetUrl by remember { mutableStateOf<String?>(null) } // non-null => loot sheet open
    var showNewPlaylist by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { MusicDownloader.messages.collect { status = it } }

    /** Builds queue items; stashed files that vanished fall back to VOX-STREAM. */
    suspend fun toItems(list: List<MusicTrackEntity>): List<MediaItem> = withContext(Dispatchers.IO) {
        list.map { t ->
            val local = t.localUri?.takeIf { localFileExists(ctx, it) }
            if (t.localUri != null && local == null) dao.setLocalUri(t.id, null)
            buildMusicItem(t, local, active?.name)
        }
    }

    /** Row tap: plays this track now by replacing the current queue slot; upcoming queue is preserved. */
    fun playSingle(index: Int) {
        val c = controller ?: run { status = "SERVICE OFFLINE"; return }
        val t = tracks.getOrNull(index) ?: return
        scope.launch {
            val item = toItems(listOf(t)).first()
            if (c.mediaItemCount == 0) {
                c.setMediaItem(item); c.prepare(); c.play()
            } else {
                // Swap only the current slot; every queued ([ + Q ]) track after it stays put.
                val idx = c.currentMediaItemIndex.coerceIn(0, c.mediaItemCount - 1)
                c.replaceMediaItem(idx, item)
                c.seekTo(idx, 0L); c.prepare(); c.play()
            }
        }
    }

    /** [ PLAY ALL ]: loads the whole active playlist into the queue. */
    fun playAll() {
        val c = controller ?: run { status = "SERVICE OFFLINE"; return }
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
        val c = controller ?: run { status = "SERVICE OFFLINE"; return }
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
        val c = controller ?: run { status = "SERVICE OFFLINE"; return }
        scope.launch {
            val item = toItems(listOf(t)).first()
            if (c.mediaItemCount == 0) {
                c.setMediaItem(item); c.prepare(); c.play()
            } else {
                val ended = c.playbackState == Player.STATE_ENDED
                c.addMediaItem(item)
                if (ended) { c.seekTo(c.mediaItemCount - 1, 0L); c.play() }
            }
            status = "QUEUED: ${t.title}"
        }
    }

    Column(
        Modifier.fillMaxSize().background(Waaagh.Iron).statusBarsPadding().navigationBarsPadding()
            .cogitatorScanlines().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ── Single-row header ──
        Row(Modifier.fillMaxWidth().cogitatorFrame().padding(horizontal = 3.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(Plate).background(Waaagh.Slate).border(1.dp, Waaagh.BrassLo, Plate)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = {},
                        onDoubleClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onExit() }
                    ),
                contentAlignment = Alignment.Center
            ) { CogitatorEmblem(deck.isPlaying, 26.dp) }
            Spacer(Modifier.width(6.dp))
            Text(
                "VOX-DECK // AUDIO COGITATOR", Modifier.weight(1f).padding(horizontal = 6.dp), color = Waaagh.Hazard,
                fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 11.sp, maxLines = 2
            )
            IronButton("[ ARCHIVE ]", { openArmoury() }, accent = true, height = 34.dp, size = 10, hPad = 6.dp, vPad = 4.dp)
            Spacer(Modifier.width(5.dp))
            IronButton("[ VOXFEED ]", { onExit() }, height = 34.dp, size = 10, hPad = 6.dp, vPad = 4.dp)
        }
        LaunchedEffect(status) { status?.let { CogitatorBanner.show(it); status = null } }

        NowPlaying(deck, controller)
        Transport(deck, controller)

        // ── Tab toggle ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabBtn("[ PLAYLIST (${tracks.size}) ]", tab == 0, Modifier.weight(1f)) { tab = 0 }
            TabBtn("[ ACTIVE QUEUE (${queue.size}) ]", tab == 1, Modifier.weight(1f)) { tab = 1 }
        }
        if (tab == 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistSelector(if (favMode) "★ FAVOURITES" else active?.name ?: "---", tracks.size, Modifier.weight(1f)) { showPicker = true }
            IronButton("[ PLAY ALL ]", { playAll() }, enabled = tracks.isNotEmpty(), accent = true, height = 34.dp, size = 11, hPad = 8.dp, vPad = 4.dp)
        }
        if (tab == 0) genreFilter?.let { g -> Row { Chip("[ GENRE: $g X ]", true) { genreFilter = null } } }

        Box(Modifier.weight(1f).fillMaxWidth().background(Waaagh.IronLight).cogitatorFrame()) {
            if (tab == 0) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (tracks.isEmpty()) item {
                        Text("ARCHIVE EMPTY - EXTRACT AN AUDIO-STREAM", color = Waaagh.Dim, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                    }
                    itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                        val current = deck.mediaId == "music:${t.id}"
                        val pct = dl[t.id]
                        val stashed = t.localUri != null
                        Row(
                            Modifier.fillMaxWidth().background(if (current) Waaagh.Iron else Color.Transparent)
                                .combinedClickable(onClick = { playSingle(i) }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuTrack = t })
                                .padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (current) "▶" else "%02d".format(i + 1), color = if (current) Waaagh.Green else Waaagh.Dim,
                                fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.width(22.dp)
                            )
                            Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FavStar(t.isFavorite)
                                    Text(t.title, color = if (current) Waaagh.Green else Color.White, fontFamily = Heavy, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(
                                        if (stashed) "CACHED" else "VOX-STREAM",
                                        color = if (stashed) Waaagh.Green else Waaagh.Hazard, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 8.sp, maxLines = 1,
                                        modifier = Modifier.border(1.dp, if (stashed) Waaagh.Green else Waaagh.Hazard).padding(horizontal = 3.dp)
                                    )
                                    GenrePill(t.genre) { genreFilter = t.genre }
                                    Text(t.artist, color = Waaagh.Dim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    Text(if (t.durationMs > 0) mmss(t.durationMs) else "--:--", color = Waaagh.Dim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
                                }
                            }
                            Box(
                                Modifier.defaultMinSize(minWidth = 36.dp, minHeight = 40.dp).clickable { enqueue(t) },
                                contentAlignment = Alignment.Center
                            ) { Text("[ + Q ]", color = Waaagh.Green, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 3.dp)) }
                            if (!stashed) Box(
                                Modifier.defaultMinSize(minWidth = 34.dp, minHeight = 40.dp)
                                    .clickable(enabled = pct == null) {
                                        MusicDownloader.enqueue(ctx, t, active?.name ?: MusicDao.DEFAULT_PLAYLIST)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(if (pct != null) "$pct%" else "⬇", color = Waaagh.Hazard, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = if (pct != null) 10.sp else 16.sp)
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                IronButton(
                    "[ CLEAR QUEUE ]", { controller?.let { it.stop(); it.clearMediaItems() } },
                    Modifier.fillMaxWidth().padding(3.dp), enabled = queue.isNotEmpty(), height = 32.dp, size = 11, vPad = 4.dp
                )
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (queue.isEmpty()) item {
                        Text("QUEUE EMPTY - TAP [ + Q ] ON A TRACK", color = Waaagh.Dim, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                    }
                    itemsIndexed(queue) { i, q ->
                        val current = i == deck.index && deck.hasMedia
                        Row(
                            Modifier.fillMaxWidth().background(if (current) Waaagh.Iron else Color.Transparent)
                                .then(if (current) Modifier.equalizerUnderlay() else Modifier)
                                .clickable { controller?.let { it.seekTo(i, 0L); it.play() } }.padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (current) "▶" else "%02d".format(i + 1), color = if (current) Waaagh.Green else Waaagh.Dim,
                                fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.width(22.dp)
                            )
                            Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                                Text(q.title, color = if (current) Waaagh.Green else Color.White, fontFamily = Heavy, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    q.artist,
                                    color = Waaagh.Dim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box(
                                Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp).clickable { controller?.removeMediaItem(i) },
                                contentAlignment = Alignment.Center
                            ) { Text("X", color = Waaagh.Alert, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 15.sp) }
                        }
                    }
                }
                }
            }
        }
        IronButton("[ + EXTRACT AUDIO-STREAM ]", { sheetUrl = "" }, Modifier.fillMaxWidth(), accent = true, height = 40.dp)

    }

    // ── Dialogs / sheet ──
    if (showPicker) {
        PlaylistPickerDialog(
            playlists = playlists, counts = counts, activeId = if (favMode) MusicDao.FAV_ID else activePid,
            onPick = { setActiveId(it); genreFilter = null; tab = 0; showPicker = false },
            onNew = { showPicker = false; showNewPlaylist = true },
            onDismiss = { showPicker = false },
            top = {
                IronButton("[ ★ FAVOURITES ]", { setActiveId(MusicDao.FAV_ID); genreFilter = null; tab = 0; showPicker = false }, Modifier.fillMaxWidth(), accent = favMode)
                IronButton(
                    "[ SHUFFLE ALL ARCHIVE ($uniqueCount) ]", { showPicker = false; shuffleAll() },
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
        WaaaghDialog({ showNewPlaylist = false }) {
            Text("// NEW ARCHIVE", color = Waaagh.Hazard, fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 16.sp)
            NameField(name, { name = it }, "ARCHIVE NAME")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IronButton("[ ABORT ]", { showNewPlaylist = false }, Modifier.weight(1f))
                IronButton("[ CREATE ]", {
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
            onCommit = { picked, targetId, newName, stash ->
                scope.launch {
                    val pid = targetId ?: dao.insertPlaylist(MusicPlaylistEntity(name = newName!!.trim(), createdAt = System.currentTimeMillis()))
                    val pname = dao.getPlaylist(pid)?.name ?: MusicDao.DEFAULT_PLAYLIST
                    val rows = dao.appendTracks(pid, picked.map {
                        MusicTrackEntity(
                            playlistId = pid, title = it.title, artist = it.artist, durationMs = it.durationMs,
                            sourceUrl = it.url, thumbnailUrl = it.thumbnailUrl, localUri = null, position = 0
                        )
                    })
                    if (stash) rows.forEach { MusicDownloader.enqueue(ctx, it, pname) }
                    setActiveId(pid)
                    sheetUrl = null
                    status = if (stash) "CACHING ${rows.size} AUDIO-STREAM(S)..." else "ADDED ${rows.size} TRACK(S) TO $pname"
                }
            }
        )
    }
}

/** Full-width `[ PLAYLIST: NAME (n) ▼ ]` selector button (shared by console + armoury). */
@Composable
internal fun PlaylistSelector(name: String, count: Int, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    IronButton("[ PLAYLIST: ${name.uppercase()} ($count) ▼ ]", onClick, modifier, height = 34.dp, size = 11, hPad = 8.dp, vPad = 4.dp)
}

/** Ork-styled modal listing every playlist with its track count. */
@Composable
internal fun PlaylistPickerDialog(
    playlists: List<MusicPlaylistEntity>, counts: Map<Long, Int>, activeId: Long,
    onPick: (Long) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit,
    top: (@Composable ColumnScope.() -> Unit)? = null
) {
    WaaaghDialog(onDismiss) {
        Text("// SELECT PLAYLIST", color = Waaagh.Hazard, fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 16.sp)
        top?.invoke(this)
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 300.dp).background(Waaagh.IronLight).cogitatorFrame(),
            contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(playlists, key = { it.id }) { p ->
                val on = p.id == activeId
                Row(
                    Modifier.fillMaxWidth().background(if (on) Waaagh.Iron else Color.Transparent)
                        .border(if (on) 2.dp else 1.dp, if (on) Waaagh.Green else Waaagh.Dim)
                        .clickable { onPick(p.id) }.padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        p.name.uppercase(), Modifier.weight(1f), color = if (on) Waaagh.Green else Color.White,
                        fontFamily = Heavy, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("(${counts[p.id] ?: 0})", color = Waaagh.Hazard, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
        IronButton("[ + NEW PLAYLIST ]", onNew, Modifier.fillMaxWidth())
        IronButton("[ CLOSE ]", onDismiss, Modifier.fillMaxWidth(), height = 36.dp)
    }
}

@Composable
private fun TabBtn(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = if (selected) Waaagh.Green else Waaagh.Hazard
    Text(
        label, modifier.border(if (selected) 2.dp else 1.dp, c).background(if (selected) Waaagh.Iron else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 7.dp),
        color = c, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center
    )
}

@Composable
internal fun Chip(label: String, selected: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val c = if (danger) Waaagh.Alert else if (selected) Waaagh.Green else Waaagh.Hazard
    Text(
        label, color = c, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1,
        modifier = Modifier.border(if (selected) 2.dp else 1.dp, c).background(if (selected) Waaagh.IronLight else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 8.dp)
    )
}

@Composable
internal fun NameField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    BasicTextField(
        value, onChange, singleLine = true,
        textStyle = TextStyle(color = Waaagh.Green, fontFamily = Mono, fontSize = 13.sp),
        cursorBrush = SolidColor(Waaagh.Green),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
                if (value.isEmpty()) Text(hint, color = Waaagh.Dim, fontFamily = Mono, fontSize = 13.sp)
                inner()
            }
        },
        modifier = modifier.fillMaxWidth().background(Color(0xFF070908)).border(1.dp, Waaagh.BrassLo).padding(10.dp)
    )
}

@Composable
internal fun WaaaghDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().cogitatorScanlines().cogitatorFrame().brassRivets(8.dp).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content
        )
    }
}

/** Centered chamfered cogitator dialog: paste & scan -> pick audio-streams -> initiate extraction. */
@Composable
private fun ExtractDialog(
    playlists: List<MusicPlaylistEntity>, counts: Map<Long, Int>, defaultPlaylistId: Long, initialUrl: String,
    onDismiss: () -> Unit,
    onCommit: (picked: List<ScannedTrack>, targetId: Long?, newName: String?, stash: Boolean) -> Unit
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
                // Importing a whole playlist: offer to create an archive named after it.
                if (r.isPlaylist && newName.isBlank()) newName = r.title
                if (r.tracks.isEmpty()) error = "NO AUDIO-STREAMS FOUND"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "SCAN FAULT: ${e.message ?: e.javaClass.simpleName}".take(140)
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
                Modifier.fillMaxWidth().heightIn(max = 600.dp).cogitatorScanlines().cogitatorFrame().brassRivets(8.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("// EXTRACT AUDIO-STREAM", color = Waaagh.Green, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NameField(input, { input = it }, "TRACK OR PLAYLIST URL...", Modifier.weight(1f))
                    IronButton(if (scanning) "..." else "[ SCAN ]", { doScan() }, enabled = input.isNotBlank() && !scanning, accent = true, height = 40.dp, size = 11)
                }
                error?.let { Text(it, color = Waaagh.Alert, fontFamily = Mono, fontSize = 11.sp) }
                IronButton(
                    "[ TARGET ARCHIVE: ${if (newMode) "+ NEW " + newName.ifBlank { "ARCHIVE" } else playlists.firstOrNull { it.id == targetId }?.name ?: "---"} ▼ ]".uppercase(),
                    { showTargetPicker = true }, Modifier.fillMaxWidth(), height = 34.dp, size = 11, hPad = 8.dp, vPad = 4.dp
                )
                if (newMode) NameField(newName, { newName = it }, "NEW ARCHIVE NAME")
                if (showTargetPicker) PlaylistPickerDialog(
                    playlists = playlists, counts = counts, activeId = if (newMode) -1L else (targetId ?: -1L),
                    onPick = { newMode = false; targetId = it; showTargetPicker = false },
                    onNew = { newMode = true; showTargetPicker = false },
                    onDismiss = { showTargetPicker = false }
                )

                if (scanning) Text("SCANNING DATA-STREAM...", color = Waaagh.Hazard, fontFamily = Mono, fontSize = 12.sp)
                if (s != null && s.tracks.isNotEmpty()) {
                    Text(
                        "${s.title.uppercase()} | ${checked.size}/${s.tracks.size} SELECTED", color = Waaagh.Green,
                        fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IronButton("[ SELECT ALL ]", { checked = s.tracks.indices.toSet() }, height = 36.dp, size = 11)
                        IronButton("[ CLEAR ]", { checked = emptySet() }, height = 36.dp, size = 11)
                        Spacer(Modifier.weight(1f))
                        Text("QUALITY: MAX BITRATE AUDIO", color = Waaagh.Green, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1, modifier = Modifier.border(1.dp, Waaagh.Green).padding(horizontal = 4.dp, vertical = 3.dp))
                    }
                    LazyColumn(
                        Modifier.weight(1f, fill = false).fillMaxWidth().background(Color(0xFF070908)).cogitatorFrame(),
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
                                    colors = CheckboxDefaults.colors(checkedColor = Waaagh.Green, uncheckedColor = Waaagh.Hazard, checkmarkColor = Waaagh.Iron)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(t.title, color = Color.White, fontFamily = Heavy, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(t.artist, color = Waaagh.Dim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(if (t.durationMs > 0) mmss(t.durationMs) else "--:--", color = Waaagh.Dim, fontFamily = Mono, fontSize = 11.sp)
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { cacheOffline = !cacheOffline },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            cacheOffline, { cacheOffline = it },
                            colors = CheckboxDefaults.colors(checkedColor = Waaagh.Green, uncheckedColor = Waaagh.Hazard, checkmarkColor = Waaagh.Iron)
                        )
                        Text("CACHE OFFLINE (LOCAL-CACHE)", color = if (cacheOffline) Waaagh.Green else Waaagh.Dim, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IronButton("[ ABORT ]", onDismiss, Modifier.weight(1f))
                    IronButton("[ INITIATE EXTRACTION ]", { commit() }, Modifier.weight(2f), enabled = ready, accent = true, size = 12)
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
        Modifier.fillMaxWidth().background(Waaagh.IronLight).cogitatorFrame(Waaagh.Green).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).background(Waaagh.Iron).border(1.dp, Color.White),
                contentAlignment = Alignment.Center
            ) {
                if (deck.artwork != null) AsyncImage(deck.artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else CogitatorEmblem(deck.isPlaying, 36.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    deck.title.ifEmpty { "NO TRACK LOADED" }, color = Color.White, fontFamily = Heavy,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(deck.artist.ifEmpty { "---" }, color = Waaagh.Dim, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    thumbColor = Waaagh.Hazard, activeTrackColor = Waaagh.Green, inactiveTrackColor = Color(0xFF444444),
                    disabledThumbColor = Waaagh.Dim, disabledActiveTrackColor = Color(0xFF444444), disabledInactiveTrackColor = Color(0xFF333333)
                )
            )
            Text(
                "[${mmss(shownPos)} / ${mmss(deck.durationMs)}]", Modifier.padding(start = 6.dp),
                color = Waaagh.Green, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1
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
        IronButton("SHUF", { c?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }, Modifier.weight(1f), enabled = on, active = deck.shuffle, height = h, size = 11, hPad = 4.dp, vPad = 4.dp)
        IronButton("|◀◀", { c?.seekToPreviousMediaItem() }, Modifier.weight(1f), enabled = on, height = h, size = 14, hPad = 4.dp, vPad = 4.dp)
        IronButton(
            if (deck.isPlaying) "❚❚" else "▶",
            { c?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } } },
            Modifier.weight(1.4f), enabled = on && deck.hasMedia, accent = true, height = h, size = 20, hPad = 4.dp, vPad = 4.dp
        )
        IronButton("▶▶|", { c?.seekToNextMediaItem() }, Modifier.weight(1f), enabled = on, height = h, size = 14, hPad = 4.dp, vPad = 4.dp)
    }
}

/** Cogitator key: slate plate, 1dp brass border (phosphor-green when [active]), amber fill for primary actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun IronButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    active: Boolean = false, accent: Boolean = false, height: androidx.compose.ui.unit.Dp = 44.dp, size: Int = 13,
    hPad: androidx.compose.ui.unit.Dp = 10.dp, vPad: androidx.compose.ui.unit.Dp = 8.dp
) {
    val border = if (active) Waaagh.Green else Waaagh.Hazard
    Box(
        modifier.heightIn(min = height).alpha(if (enabled) 1f else 0.4f)
            .clip(Plate)
            .background(if (accent) Waaagh.Slate2 else Waaagh.Slate)
            .brassRivets(5.dp)
            .border(1.dp, if (active) Waaagh.Green else if (accent) Waaagh.BrassHi else Waaagh.BrassLo, Plate)
            .combinedClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = hPad, vertical = vPad),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = if (accent || active) Waaagh.Green else Waaagh.Gold,
            fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = size.sp, textAlign = TextAlign.Center, maxLines = 2
        )
    }
}