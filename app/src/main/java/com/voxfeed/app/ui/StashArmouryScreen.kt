package com.voxfeed.app.ui

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxfeed.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class ArmouryStats(val total: Int = 0, val stashed: Int = 0, val stream: Int = 0, val bytes: Long = 0)

private data class Orphan(val uri: String, val title: String, val artist: String, val durationMs: Long, val folder: String)

private val AUDIO_EXT = setOf("m4a", "mp3", "webm")

private fun fileSize(ctx: Context, uri: String): Long = runCatching {
    val u = Uri.parse(uri)
    if (u.scheme == "file") File(u.path!!).length()
    else ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length.coerceAtLeast(0) } ?: 0L
}.getOrDefault(0L)

internal fun deleteLocalFile(ctx: Context, uri: String): Boolean = runCatching {
    val u = Uri.parse(uri)
    if (u.scheme == "file") File(u.path!!).delete() else ctx.contentResolver.delete(u, null, null) > 0
}.getOrDefault(false)

/** Audio files under Music/VoxFeed/ that this app can see. */
private fun listVoxFeedAudio(ctx: Context): List<Orphan> {
    val out = ArrayList<Orphan>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME
        )
        ctx.contentResolver.query(
            base, cols, "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_MUSIC}/VoxFeed/%"), null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(5).orEmpty()
                if (name.substringAfterLast('.', "").lowercase() !in AUDIO_EXT) continue
                val folder = c.getString(4).orEmpty().substringAfter("VoxFeed/", "").trim('/').substringBefore('/')
                out += Orphan(
                    ContentUris.withAppendedId(base, c.getLong(0)).toString(),
                    c.getString(1) ?: name.substringBeforeLast('.'),
                    c.getString(2)?.takeIf { it != "<unknown>" }.orEmpty(),
                    c.getLong(3), folder
                )
            }
        }
    } else {
        val root = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "VoxFeed")
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            dir.listFiles()?.filter { it.extension.lowercase() in AUDIO_EXT }?.forEach { f ->
                out += Orphan(Uri.fromFile(f).toString(), f.nameWithoutExtension, "", 0L, dir.name)
            }
        }
    }
    return out
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun StashArmouryScreen(activeId: Long, setActiveId: (Long) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val dao = remember { VoxDatabase.get(ctx).musicDao() }

    LaunchedEffect(Unit) { dao.ensureDefaultPlaylist() }
    val playlists by dao.observePlaylists().collectAsState(initial = emptyList())
    val active = playlists.firstOrNull { it.id == activeId } ?: playlists.firstOrNull()
    val activePid = active?.id ?: -1L
    val allTracks by dao.observeAllTracks().collectAsState(initial = emptyList())
    val favMode = activeId == MusicDao.FAV_ID
    var genreFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var menuTrack by remember { mutableStateOf<MusicTrackEntity?>(null) }
    val tracks = remember(allTracks, activePid, favMode, genreFilter) {
        val base = if (favMode) allTracks.filter { it.isFavorite }.distinctBy { it.sourceUrl } else allTracks.filter { it.playlistId == activePid }
        genreFilter?.let { g -> base.filter { it.genre == g } } ?: base
    }
    val counts = remember(allTracks) { allTracks.groupingBy { it.playlistId }.eachCount() }
    val dl by MusicDownloader.progress.collectAsState()

    var status by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var showNew by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var scrapTarget by remember { mutableStateOf<MusicTrackEntity?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<MusicTrackEntity?>(null) }

    LaunchedEffect(Unit) { MusicDownloader.messages.collect { status = it } }

    val stats by produceState(ArmouryStats(), tracks, refresh) {
        value = withContext(Dispatchers.IO) {
            val stashed = tracks.filter { it.localUri != null }
            ArmouryStats(tracks.size, stashed.size, tracks.size - stashed.size, stashed.sumOf { fileSize(ctx, it.localUri!!) })
        }
    }

    val canDelete = active != null && playlists.size > 1

    Column(
        Modifier.fillMaxSize().background(Waaagh.Iron).statusBarsPadding().navigationBarsPadding()
            .cogitatorScanlines().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // -- Header --
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "VOX-ARCHIVE // DATA-STORE", Modifier.weight(1f), color = Waaagh.Hazard, fontFamily = Heavy,
                fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 2
            )
            IronButton("[ BACK TO VOX-DECK ]", onBack, accent = true, height = 34.dp, size = 10, hPad = 6.dp, vPad = 4.dp)
        }

        // ── Telemetry ──
        Column(
            Modifier.fillMaxWidth().background(Waaagh.IronLight).cogitatorFrame(Waaagh.Green).padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "Music/VoxFeed/${MusicDownloader.sanitize(active?.name ?: MusicDao.DEFAULT_PLAYLIST)}/",
                    color = Waaagh.Dim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text("TOTAL: ${stats.total} | CACHED: ${stats.stashed} | STREAM: ${stats.stream} | DISK: %.1f MB".format(stats.bytes / 1_048_576.0), color = Waaagh.Green, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 2)
            }
            IronButton("[ RE-INDEX ARCHIVE ]", {
                scope.launch {
                    val (reset, imported) = withContext(Dispatchers.IO) {
                        // 1) stashed files that vanished from disk go back to VOX-STREAM
                        var missing = 0
                        dao.getStashedTracks().forEach {
                            if (!localFileExists(ctx, it.localUri!!)) { dao.setLocalUri(it.id, null); missing++ }
                        }
                        // 2) orphaned files: attach to the playlist named like their subfolder, else to RESCANNED LOOT
                        val known = dao.getStashedTracks().mapNotNull { it.localUri }.toMutableSet()
                        val lists = dao.getPlaylists()
                        var added = 0
                        for (o in listVoxFeedAudio(ctx)) {
                            if (!known.add(o.uri)) continue // already attached somewhere: no duplicates
                            val target = lists.firstOrNull { MusicDownloader.sanitize(it.name) == o.folder }
                                ?: dao.getOrCreatePlaylist(MusicDao.RESCAN_PLAYLIST)
                            dao.appendTracks(
                                target.id,
                                listOf(
                                    MusicTrackEntity(
                                        playlistId = target.id, title = o.title, artist = o.artist, durationMs = o.durationMs,
                                        sourceUrl = o.uri, thumbnailUrl = null, localUri = o.uri, position = 0
                                    )
                                )
                            )
                            added++
                        }
                        missing to added
                    }
                    refresh++
                    status = "RE-INDEX: $reset MISSING FILE(S) RESET, $imported ORPHAN(S) IMPORTED"
                }
            }, Modifier.padding(start = 6.dp), height = 32.dp, size = 10, hPad = 6.dp, vPad = 4.dp)
            }
        }
        LaunchedEffect(status) { status?.let { CogitatorBanner.show(it); status = null } }

        // -- Playlist selector + management --
        PlaylistSelector(if (favMode) "★ FAVOURITES" else active?.name ?: "---", tracks.size) { showPicker = true }
        genreFilter?.let { g -> Row { Chip("[ GENRE: $g X ]", true) { genreFilter = null } } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IronButton("[ RENAME ]", { showRename = true }, Modifier.weight(1f), enabled = active != null, height = 34.dp, size = 10, vPad = 4.dp)
            IronButton("[ DELETE PLAYLIST ]", { showDelete = true }, Modifier.weight(1f), enabled = canDelete, height = 34.dp, size = 10, vPad = 4.dp)
        }

        // ── Track management ──
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().background(Waaagh.IronLight).cogitatorFrame(),
            contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (tracks.isEmpty()) item {
                Text("ARCHIVE EMPTY", color = Waaagh.Dim, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            }
            items(tracks.size, key = { tracks[it].id }) { i ->
                val t = tracks[i]
                val pct = dl[t.id]
                val stashed = t.localUri != null
                Column(
                    Modifier.fillMaxWidth().background(Waaagh.Iron)
                        .combinedClickable(onClick = {}, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuTrack = t })
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("%02d".format(i + 1), color = Waaagh.Dim, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.width(22.dp))
                        FavStar(t.isFavorite)
                        Text(
                            if (t.artist.isBlank()) t.title else "${t.title} - ${t.artist}", Modifier.weight(1f),
                            color = Color.White, fontFamily = Heavy, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Box(Modifier.padding(start = 4.dp)) { GenrePill(t.genre) { genreFilter = t.genre } }
                        Text(
                            if (stashed) "CACHED" else "VOX-STREAM",
                            color = if (stashed) Waaagh.Green else Waaagh.Hazard, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 8.sp, maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp).border(1.dp, if (stashed) Waaagh.Green else Waaagh.Hazard).padding(horizontal = 3.dp)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IronButton("UP", { scope.launch { dao.moveTrack(activePid, t.id, -1) } }, enabled = !favMode && genreFilter == null && i > 0, height = 28.dp, size = 10, hPad = 10.dp, vPad = 2.dp)
                        IronButton("DN", { scope.launch { dao.moveTrack(activePid, t.id, 1) } }, enabled = !favMode && genreFilter == null && i < tracks.size - 1, height = 28.dp, size = 10, hPad = 10.dp, vPad = 2.dp)
                        Spacer(Modifier.weight(1f))
                        if (!stashed) IronButton(
                            if (pct != null) "$pct%" else "[ CACHE ]",
                            { MusicDownloader.enqueue(ctx, t, active?.name ?: MusicDao.DEFAULT_PLAYLIST) },
                            enabled = pct == null, height = 28.dp, size = 10, hPad = 6.dp, vPad = 2.dp
                        )
                        IronButton("[ MOVE ]", { moveTarget = t }, height = 28.dp, size = 10, hPad = 6.dp, vPad = 2.dp)
                        IronButton("[ PURGE ]", { scrapTarget = t }, height = 28.dp, size = 10, hPad = 6.dp, vPad = 2.dp)
                    }
                }
            }
        }
    }

    if (showPicker) {
        PlaylistPickerDialog(
            playlists = playlists, counts = counts, activeId = if (favMode) MusicDao.FAV_ID else activePid,
            onPick = { setActiveId(it); genreFilter = null; showPicker = false },
            onNew = { showPicker = false; showNew = true },
            onDismiss = { showPicker = false },
            top = {
                IronButton("[ ★ FAVOURITES ]", { setActiveId(MusicDao.FAV_ID); genreFilter = null; showPicker = false }, Modifier.fillMaxWidth(), accent = favMode)
            }
        )
    }
    menuTrack?.let { mt -> TrackActionMenu(mt, playlists, counts, dao, scope) { menuTrack = null; refresh++ } }
    moveTarget?.let { t ->
        var copyMode by remember { mutableStateOf(false) }
        val targets = playlists.filter { it.id != t.playlistId }
        WaaaghDialog({ moveTarget = null }) {
            Text("// MOVE / COPY TRACK", color = Waaagh.Hazard, fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text(t.title, color = Color.White, fontFamily = Mono, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("MOVE", !copyMode) { copyMode = false }
                Chip("COPY", copyMode) { copyMode = true }
            }
            Text("TARGET ARCHIVE:", color = Waaagh.Dim, fontFamily = Mono, fontSize = 10.sp)
            if (targets.isEmpty()) Text("NO OTHER PLAYLISTS - CREATE ONE FIRST", color = Waaagh.Alert, fontFamily = Mono, fontSize = 11.sp)
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).background(Waaagh.IronLight).cogitatorFrame(),
                contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(targets, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().border(1.dp, Waaagh.Dim)
                            .clickable {
                                scope.launch {
                                    val added = dao.copyTrackTo(t.id, p.id, !copyMode)
                                    moveTarget = null
                                    status = (if (copyMode) "COPIED" else "MOVED") + " TO ${p.name.uppercase()}" +
                                        if (!added) " (ALREADY THERE)" else ""
                                }
                            }.padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(p.name.uppercase(), Modifier.weight(1f), color = Color.White, fontFamily = Heavy, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("(${counts[p.id] ?: 0})", color = Waaagh.Hazard, fontFamily = Mono, fontSize = 12.sp)
                    }
                }
            }
            IronButton("[ CANCEL ]", { moveTarget = null }, Modifier.fillMaxWidth())
        }
    }

    // -- Dialogs --
    if (showNew) {
        var name by remember { mutableStateOf("") }
        WaaaghDialog({ showNew = false }) {
            Text("// NEW PLAYLIST", color = Waaagh.Hazard, fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 16.sp)
            NameField(name, { name = it }, "PLAYLIST NAME")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IronButton("[ CANCEL ]", { showNew = false }, Modifier.weight(1f))
                IronButton("[ CREATE ]", {
                    scope.launch {
                        setActiveId(dao.insertPlaylist(MusicPlaylistEntity(name = name.trim(), createdAt = System.currentTimeMillis())))
                        showNew = false
                    }
                }, Modifier.weight(1f), enabled = name.isNotBlank(), accent = true)
            }
        }
    }
    if (showRename && active != null) {
        var name by remember { mutableStateOf(active.name) }
        WaaaghDialog({ showRename = false }) {
            Text("// RENAME PLAYLIST", color = Waaagh.Hazard, fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 16.sp)
            NameField(name, { name = it }, "PLAYLIST NAME")
            Text("Already-cached files stay in their old Music/VoxFeed folder.", color = Waaagh.Dim, fontFamily = Mono, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IronButton("[ CANCEL ]", { showRename = false }, Modifier.weight(1f))
                IronButton("[ RENAME ]", {
                    scope.launch { dao.renamePlaylist(active.id, name.trim()); showRename = false }
                }, Modifier.weight(1f), enabled = name.isNotBlank() && name.trim() != MusicDao.DEFAULT_PLAYLIST, accent = true)
            }
        }
    }
    if (showDelete && active != null && canDelete) {
        val stashedTracks = tracks.filter { it.localUri != null }
        fun finish(deleteFiles: Boolean) {
            scope.launch {
                val failed = if (deleteFiles) withContext(Dispatchers.IO) {
                    stashedTracks.count { !deleteLocalFile(ctx, it.localUri!!) }
                } else 0
                dao.deletePlaylist(active.id)
                setActiveId(-1L)
                showDelete = false
                refresh++
                status = when {
                    !deleteFiles && stashedTracks.isNotEmpty() -> "PLAYLIST DELETED. FILES KEPT IN Music/VoxFeed"
                    failed > 0 -> "FILE FAULT: $failed FILE(S) COULD NOT BE DELETED (PLAYLIST REMOVED)"
                    deleteFiles -> "PLAYLIST DELETED, ${stashedTracks.size} FILE(S) PURGED"
                    else -> "PLAYLIST DELETED"
                }
            }
        }
        WaaaghDialog({ showDelete = false }) {
            Text("// DELETE PLAYLIST?", color = Waaagh.Alert, fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text(
                if (stashedTracks.isEmpty()) "Delete '${active.name}' and its ${tracks.size} track(s)?"
                else "'${active.name}' has ${stashedTracks.size} CACHED (offline) track(s). Keep their files in Music/VoxFeed, or purge them too?",
                color = Color.White, fontFamily = Mono, fontSize = 12.sp
            )
            if (stashedTracks.isEmpty()) {
                IronButton("[ DELETE ]", { finish(false) }, Modifier.fillMaxWidth(), accent = true)
            } else {
                IronButton("[ DELETE PLAYLIST ONLY ]", { finish(false) }, Modifier.fillMaxWidth(), size = 12)
                IronButton("[ DELETE PLAYLIST & PURGE FILES ]", { finish(true) }, Modifier.fillMaxWidth(), accent = true, size = 12)
            }
            IronButton("[ CANCEL ]", { showDelete = false }, Modifier.fillMaxWidth())
        }
    }
    scrapTarget?.let { t ->
        val stashed = t.localUri != null
        val pname = active?.name ?: ""
        AlertDialog(
            onDismissRequest = { scrapTarget = null },
            containerColor = Waaagh.Iron, shape = RectangleShape,
            modifier = Modifier.border(3.dp, Waaagh.Hazard),
            title = { Text("// PURGE TRACK?", color = Waaagh.Alert, fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 18.sp) },
            text = {
                Text(
                    if (stashed) "'${t.title}' is CACHED offline. Remove it from $pname only, or delete the audio file too?"
                    else "Remove '${t.title}' from $pname?",
                    color = Color.White, fontFamily = Mono, fontSize = 13.sp
                )
            },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (stashed) {
                        IronButton("[ REMOVE FROM PLAYLIST ONLY ]", {
                            scope.launch { dao.deleteTrack(t.id); scrapTarget = null; status = "REMOVED. FILE KEPT IN Music/VoxFeed" }
                        }, Modifier.fillMaxWidth(), size = 12)
                        IronButton("[ PURGE TRACK & DELETE FILE ]", {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { deleteLocalFile(ctx, t.localUri!!) }
                                dao.deleteTrack(t.id)
                                scrapTarget = null
                                refresh++
                                status = if (ok) "TRACK PURGED, FILE DELETED" else "FILE FAULT: COULD NOT DELETE FILE (TRACK REMOVED)"
                            }
                        }, Modifier.fillMaxWidth(), accent = true, size = 12)
                    } else {
                        IronButton("[ REMOVE ]", {
                            scope.launch { dao.deleteTrack(t.id); scrapTarget = null }
                        }, Modifier.fillMaxWidth(), accent = true)
                    }
                    IronButton("[ CANCEL ]", { scrapTarget = null }, Modifier.fillMaxWidth())
                }
            }
        )
    }
}