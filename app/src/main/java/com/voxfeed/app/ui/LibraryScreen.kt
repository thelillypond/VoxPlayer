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
import androidx.compose.material.icons.filled.Star
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

private data class LibraryStats(val total: Int = 0, val cached: Int = 0, val stream: Int = 0, val bytes: Long = 0)

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
            base, cols, "${MediaStore.Audio.Media.RELATIVE_PATH} Like ?",
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
fun LibraryScreen(activeId: Long, setActiveId: (Long) -> Unit, onBack: () -> Unit) {
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
    var deleteTarget by remember { mutableStateOf<MusicTrackEntity?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<MusicTrackEntity?>(null) }

    LaunchedEffect(Unit) { MusicDownloader.messages.collect { status = it } }

    val stats by produceState(LibraryStats(), tracks, refresh) {
        value = withContext(Dispatchers.IO) {
            val cached = tracks.filter { it.localUri != null }
            LibraryStats(tracks.size, cached.size, tracks.size - cached.size, cached.sumOf { fileSize(ctx, it.localUri!!) })
        }
    }

    val canDelete = active != null && playlists.size > 1

    Column(
        Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding().navigationBarsPadding()
            .deckBackground().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // -- Header --
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Library", Modifier.weight(1f), color = Palette.Amber, fontFamily = Heavy,
                fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 2
            )
            ActionButton("Back To Audio Deck", onBack, accent = true, height = 34.dp, size = 10, hPad = 6.dp, vPad = 4.dp)
        }

        // - Telemetry -
        Column(
            Modifier.fillMaxWidth().background(Palette.Surface).panelFrame(Palette.Accent).padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "Music/VoxFeed/${MusicDownloader.sanitize(active?.name ?: MusicDao.DEFAULT_PLAYLIST)}/",
                    color = Palette.Dim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text("Total: ${stats.total} | Cached: ${stats.cached} | Stream: ${stats.stream} | Disk: %.1f MB".format(stats.bytes / 1_048_576.0), color = Palette.Accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 2)
            }
            ActionButton("Rescan Files", {
                scope.launch {
                    val (reset, imported) = withContext(Dispatchers.IO) {
                        // 1) cached files that vanished from disk go back to streaming
                        var missing = 0
                        dao.getCachedTracks().forEach {
                            if (!localFileExists(ctx, it.localUri!!)) { dao.setLocalUri(it.id, null); missing++ }
                        }
                        // 2) orphaned files: attach to the playlist named like their subfolder, else to the recovered-files playlist
                        val known = dao.getCachedTracks().mapNotNull { it.localUri }.toMutableSet()
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
                    status = "Rescan: $reset missing file(s) reset, $imported file(s) imported"
                }
            }, Modifier.padding(start = 6.dp), height = 32.dp, size = 10, hPad = 6.dp, vPad = 4.dp)
            }
        }
        LaunchedEffect(status) { status?.let { StatusBanner.show(it); status = null } }

        // -- Playlist selector + management --
        PlaylistSelector(if (favMode) "★ Favourites" else active?.name ?: "---", tracks.size) { showPicker = true }
        genreFilter?.let { g -> Row { Chip("Genre: $g X", true) { genreFilter = null } } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("Rename", { showRename = true }, Modifier.weight(1f), enabled = active != null, height = 34.dp, size = 10, vPad = 4.dp)
            ActionButton("Delete Playlist", { showDelete = true }, Modifier.weight(1f), enabled = canDelete, height = 34.dp, size = 10, vPad = 4.dp)
        }

        // - Track management -
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().background(Palette.Surface).panelFrame(),
            contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (tracks.isEmpty()) item {
                Text("No tracks", color = Palette.Dim, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            }
            items(tracks.size, key = { tracks[it].id }) { i ->
                val t = tracks[i]
                val pct = dl[t.id]
                val cached = t.localUri != null
                Column(
                    Modifier.fillMaxWidth().background(Palette.Bg)
                        .combinedClickable(onClick = {}, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuTrack = t })
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("%02d".format(i + 1), color = Palette.Dim, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.width(22.dp))
                        FavStar(t.isFavorite)
                        Text(
                            if (t.artist.isBlank()) t.title else "${t.title} - ${t.artist}", Modifier.weight(1f),
                            color = Color.White, fontFamily = Heavy, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Box(Modifier.padding(start = 4.dp)) { GenrePill(t.genre) { genreFilter = t.genre } }
                        Text(
                            if (cached) "Cached" else "Stream",
                            color = if (cached) Palette.Accent else Palette.Amber, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 8.sp, maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp).border(1.dp, if (cached) Palette.Accent else Palette.Amber).padding(horizontal = 3.dp)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionButton("Up", { scope.launch { dao.moveTrack(activePid, t.id, -1) } }, enabled = !favMode && genreFilter == null && i > 0, height = 28.dp, size = 10, hPad = 10.dp, vPad = 2.dp)
                        ActionButton("Down", { scope.launch { dao.moveTrack(activePid, t.id, 1) } }, enabled = !favMode && genreFilter == null && i < tracks.size - 1, height = 28.dp, size = 10, hPad = 10.dp, vPad = 2.dp)
                        Spacer(Modifier.weight(1f))
                        if (!cached) ActionButton(
                            if (pct != null) "$pct%" else "Cache",
                            { MusicDownloader.enqueue(ctx, t, active?.name ?: MusicDao.DEFAULT_PLAYLIST) },
                            enabled = pct == null, height = 28.dp, size = 10, hPad = 6.dp, vPad = 2.dp
                        )
                        ActionButton("Move", { moveTarget = t }, height = 28.dp, size = 10, hPad = 6.dp, vPad = 2.dp)
                        ActionButton("Delete", { deleteTarget = t }, height = 28.dp, size = 10, hPad = 6.dp, vPad = 2.dp)
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
                ActionButton("Favourites", { setActiveId(MusicDao.FAV_ID); genreFilter = null; showPicker = false }, Modifier.fillMaxWidth(), accent = favMode, icon = androidx.compose.material.icons.Icons.Default.Star)
            }
        )
    }
    menuTrack?.let { mt -> TrackActionMenu(mt, playlists, counts, dao, scope) { menuTrack = null; refresh++ } }
    moveTarget?.let { t ->
        var copyMode by remember { mutableStateOf(false) }
        val targets = playlists.filter { it.id != t.playlistId }
        AppDialog({ moveTarget = null }) {
            Text("Move / Copy Track", color = Palette.Amber, fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text(t.title, color = Color.White, fontFamily = Mono, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Move", !copyMode) { copyMode = false }
                Chip("Copy", copyMode) { copyMode = true }
            }
            Text("Target playlist:", color = Palette.Dim, fontFamily = Mono, fontSize = 10.sp)
            if (targets.isEmpty()) Text("No other playlists. Create one first.", color = Palette.Alert, fontFamily = Mono, fontSize = 11.sp)
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).background(Palette.Surface).panelFrame(),
                contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(targets, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().border(1.dp, Palette.Dim)
                            .clickable {
                                scope.launch {
                                    val added = dao.copyTrackTo(t.id, p.id, !copyMode)
                                    moveTarget = null
                                    status = (if (copyMode) "Copied" else "Moved") + " to ${p.name}" +
                                        if (!added) " (Already There)" else ""
                                }
                            }.padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(p.name, Modifier.weight(1f), color = Color.White, fontFamily = Heavy, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("(${counts[p.id] ?: 0})", color = Palette.Amber, fontFamily = Mono, fontSize = 12.sp)
                    }
                }
            }
            ActionButton("Cancel", { moveTarget = null }, Modifier.fillMaxWidth())
        }
    }

    // -- Dialogs --
    if (showNew) {
        var name by remember { mutableStateOf("") }
        AppDialog({ showNew = false }) {
            Text("New Playlist", color = Palette.Amber, fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 16.sp)
            NameField(name, { name = it }, "Playlist Name")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Cancel", { showNew = false }, Modifier.weight(1f))
                ActionButton("Create", {
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
        AppDialog({ showRename = false }) {
            Text("Rename Playlist", color = Palette.Amber, fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 16.sp)
            NameField(name, { name = it }, "Playlist Name")
            Text("Already-cached files stay in their old Music/VoxFeed folder.", color = Palette.Dim, fontFamily = Mono, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Cancel", { showRename = false }, Modifier.weight(1f))
                ActionButton("Rename", {
                    scope.launch { dao.renamePlaylist(active.id, name.trim()); showRename = false }
                }, Modifier.weight(1f), enabled = name.isNotBlank() && name.trim() != MusicDao.DEFAULT_PLAYLIST, accent = true)
            }
        }
    }
    if (showDelete && active != null && canDelete) {
        val cachedTracks = tracks.filter { it.localUri != null }
        fun finish(deleteFiles: Boolean) {
            scope.launch {
                val failed = if (deleteFiles) withContext(Dispatchers.IO) {
                    cachedTracks.count { !deleteLocalFile(ctx, it.localUri!!) }
                } else 0
                dao.deletePlaylist(active.id)
                setActiveId(-1L)
                showDelete = false
                refresh++
                status = when {
                    !deleteFiles && cachedTracks.isNotEmpty() -> "Playlist deleted. Files kept in Music/VoxFeed"
                    failed > 0 -> "File error: $failed file(s) could not be deleted (playlist removed)"
                    deleteFiles -> "Playlist deleted, ${cachedTracks.size} file(s) deleted"
                    else -> "Playlist deleted"
                }
            }
        }
        AppDialog({ showDelete = false }) {
            Text("Delete Playlist?", color = Palette.Alert, fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text(
                if (cachedTracks.isEmpty()) "Delete '${active.name}' and its ${tracks.size} track(s)?"
                else "'${active.name}' has ${cachedTracks.size} cached (offline) track(s). Keep their files in Music/VoxFeed, or delete them too?",
                color = Color.White, fontFamily = Mono, fontSize = 12.sp
            )
            if (cachedTracks.isEmpty()) {
                ActionButton("Delete", { finish(false) }, Modifier.fillMaxWidth(), accent = true)
            } else {
                ActionButton("Delete Playlist Only", { finish(false) }, Modifier.fillMaxWidth(), size = 12)
                ActionButton("Delete Playlist & Files", { finish(true) }, Modifier.fillMaxWidth(), accent = true, size = 12)
            }
            ActionButton("Cancel", { showDelete = false }, Modifier.fillMaxWidth())
        }
    }
    deleteTarget?.let { t ->
        val cached = t.localUri != null
        val pname = active?.name ?: ""
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = Palette.Bg, shape = RectangleShape,
            modifier = Modifier.border(3.dp, Palette.Amber),
            title = { Text("Purge Track?", color = Palette.Alert, fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 18.sp) },
            text = {
                Text(
                    if (cached) "'${t.title}' is cached offline. Remove it from $pname only, or delete the audio file too?"
                    else "Remove '${t.title}' from $pname?",
                    color = Color.White, fontFamily = Mono, fontSize = 13.sp
                )
            },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cached) {
                        ActionButton("Remove From Playlist Only", {
                            scope.launch { dao.deleteTrack(t.id); deleteTarget = null; status = "Removed. File kept in Music/VoxFeed" }
                        }, Modifier.fillMaxWidth(), size = 12)
                        ActionButton("Delete Track & File", {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { deleteLocalFile(ctx, t.localUri!!) }
                                dao.deleteTrack(t.id)
                                deleteTarget = null
                                refresh++
                                status = if (ok) "Track and file deleted" else "File error: Could Not Delete File (Track Removed)"
                            }
                        }, Modifier.fillMaxWidth(), accent = true, size = 12)
                    } else {
                        ActionButton("Remove", {
                            scope.launch { dao.deleteTrack(t.id); deleteTarget = null }
                        }, Modifier.fillMaxWidth(), accent = true)
                    }
                    ActionButton("Cancel", { deleteTarget = null }, Modifier.fillMaxWidth())
                }
            }
        )
    }
}