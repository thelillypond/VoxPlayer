package com.voxfeed.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.voxfeed.app.data.AdHocMeta
import com.voxfeed.app.data.AdHocVideo
import com.voxfeed.app.data.AiSummaryManager
import com.voxfeed.app.data.DigestResult
import com.voxfeed.app.data.SummaryDepth
import com.voxfeed.app.data.failureMessage
import com.voxfeed.app.worker.PollPrefs
import com.voxfeed.app.data.ChannelEntity
import com.voxfeed.app.data.MusicPlaylistEntity
import com.voxfeed.app.data.SubFeedEntity
import com.voxfeed.app.data.VideoEntity
import com.voxfeed.app.data.VideoPlaylistEntity
import com.voxfeed.app.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: FeedViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.voxfeed.app.worker.PollPrefs.schedule(applicationContext)
        if (savedInstanceState == null) handleAlertIntent(intent)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Palette.Accent, background = Palette.Bg, surface = Palette.Surface,
                    surfaceContainer = Palette.Surface, onSurface = Palette.Text, onBackground = Palette.Text
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = Palette.Bg) { VoxApp(vm) }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlertIntent(intent)
    }

    /** New-video notification tap: jump straight to that video's player and mark it read. */
    private fun handleAlertIntent(intent: android.content.Intent?) {
        val id = intent?.getStringExtra(EXTRA_OPEN_VIDEO_ID) ?: return
        intent.removeExtra(EXTRA_OPEN_VIDEO_ID)
        lifecycleScope.launch {
            val v = com.voxfeed.app.data.VoxDatabase.get(applicationContext).videoDao().get(id) ?: return@launch
            vm.markRead(id)
            startActivity(videoIntent(this@MainActivity, v, emptyList()))
        }
    }

    companion object {
        const val EXTRA_OPEN_VIDEO_ID = "OPEN_VIDEO_ID"
    }
}

/** Intent that opens [v] in the player, optionally followed by [queue] (video ids played in order). */
internal fun videoIntent(ctx: android.content.Context, v: VideoEntity, queue: List<String>): android.content.Intent =
    android.content.Intent(ctx, VideoPlayerActivity::class.java)
        .putExtra(VideoPlayerActivity.VIDEO_ID, v.videoId)
        .putExtra(VideoPlayerActivity.VIDEO_TITLE, v.title)
        .putExtra(VideoPlayerActivity.VIDEO_DESCRIPTION, v.description)
        .putExtra(VideoPlayerActivity.QUEUE_IDS, queue.toTypedArray())

private fun channelName(ch: ChannelEntity, titles: Map<String, String>): String =
    ch.title.takeIf { it.isNotBlank() && !it.equals("Videos", true) }
        ?: titles[ch.channelId]?.takeIf { it.isNotBlank() && !it.equals("Videos", true) }
        ?: ch.channelId

@Composable
fun VoxApp(vm: FeedViewModel) {
    var showDeck by rememberSaveable { mutableStateOf(false) }
    if (showDeck) {
        MusicConsoleScreen(onExit = { showDeck = false })
        return
    }
    val ctx = LocalContext.current
    val subFeeds by vm.subFeeds.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()
    val channelFilter by vm.channelFilter.collectAsStateWithLifecycle()
    val listFilter by vm.listFilter.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val archiveProgress by vm.archiveProgress.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val hiddenChannels by vm.hiddenChannels.collectAsStateWithLifecycle()
    val videoChannelTitles by vm.videoChannelTitles.collectAsStateWithLifecycle()
    val playlists by vm.videoPlaylists.collectAsStateWithLifecycle()
    val counts by vm.playlistCounts.collectAsStateWithLifecycle()
    val membership by vm.playlistMembership.collectAsStateWithLifecycle()
    val adHocState by vm.adHoc.collectAsStateWithLifecycle()

    var showAddChannel by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var deleteGroupTarget by remember { mutableStateOf<SubFeedEntity?>(null) }
    var deleteDownloadTarget by remember { mutableStateOf<VideoEntity?>(null) }
    var playlistTarget by remember { mutableStateOf<VideoEntity?>(null) } // video being added to a playlist
    var newPlaylistFor by remember { mutableStateOf<String?>(null) }       // "" = just create, else videoId to add
    var renameTarget by remember { mutableStateOf<VideoPlaylistEntity?>(null) }
    var deletePlaylistTarget by remember { mutableStateOf<VideoPlaylistEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    fun open(v: VideoEntity, queue: List<String>) {
        try {
            vm.markRead(v.videoId)
            ctx.startActivity(videoIntent(ctx, v, queue))
        } catch (e: Exception) {
            android.util.Log.e("VoxFeedCrash", "Opening video failed", e)
            android.widget.Toast.makeText(ctx, "Playback error: ${e.message ?: e.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val activePlaylist = (listFilter as? ListFilter.Playlist)?.let { f -> playlists.firstOrNull { it.id == f.id } }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            // - Header -
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Videos", Modifier.weight(1f).padding(horizontal = 6.dp), color = Palette.Text,
                    fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = 20.sp, maxLines = 1
                )
                IconButton({ showAddChannel = true }) { Icon(Icons.Filled.Add, "Add channel", tint = Palette.Text) }
                IconButton({ showSettings = true }) { Icon(Icons.Filled.Settings, "Settings", tint = Palette.Text) }
                Spacer(Modifier.width(4.dp))
                ActionButton("Audio Deck", { showDeck = true }, accent = true, height = 36.dp, size = 12, hPad = 10.dp, vPad = 4.dp)
            }
            Spacer(Modifier.height(6.dp))

            // - Filters: channel + playlist dropdowns, then Play All -
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val channelLabel = when (val f = channelFilter) {
                    ChannelFilter.All -> "All Channels"
                    is ChannelFilter.Group -> subFeeds.firstOrNull { it.id == f.id }?.name ?: "Group"
                    is ChannelFilter.Channel -> channels.firstOrNull { it.channelId == f.id }?.let { channelName(it, videoChannelTitles) } ?: "Channel"
                }
                DropdownSelector(channelLabel, Modifier.weight(1f)) { close ->
                    MenuRow("All Channels", channelFilter == ChannelFilter.All) { vm.selectChannel(ChannelFilter.All); close() }
                    if (subFeeds.isNotEmpty()) {
                        MenuHeader("Groups")
                        subFeeds.forEach { g ->
                            MenuRow(
                                g.name, (channelFilter as? ChannelFilter.Group)?.id == g.id,
                                trailing = {
                                    Icon(
                                        Icons.Filled.Delete, "Delete group", tint = Palette.Dim,
                                        modifier = Modifier.size(32.dp).clickable { close(); deleteGroupTarget = g }.padding(6.dp)
                                    )
                                }
                            ) { vm.selectChannel(ChannelFilter.Group(g.id)); close() }
                        }
                    }
                    if (channels.isNotEmpty()) {
                        MenuHeader("Channels")
                        channels.sortedBy { channelName(it, videoChannelTitles).lowercase() }.forEach { ch ->
                            MenuRow(channelName(ch, videoChannelTitles), (channelFilter as? ChannelFilter.Channel)?.id == ch.channelId) {
                                vm.selectChannel(ChannelFilter.Channel(ch.channelId)); close()
                            }
                        }
                    }
                }
                val listLabel = when (val f = listFilter) {
                    ListFilter.Feed -> "All Videos"
                    ListFilter.Favorites -> "Favorites"
                    ListFilter.Downloads -> "Downloads"
                    is ListFilter.Playlist -> activePlaylist?.let { "${it.name} (${counts[it.id] ?: 0})" } ?: "Playlist"
                }
                DropdownSelector(listLabel, Modifier.weight(1f)) { close ->
                    MenuRow("All Videos", listFilter == ListFilter.Feed) { vm.selectList(ListFilter.Feed); close() }
                    MenuRow("Favorites", listFilter == ListFilter.Favorites) { vm.selectList(ListFilter.Favorites); close() }
                    MenuRow("Downloads", listFilter == ListFilter.Downloads) { vm.selectList(ListFilter.Downloads); close() }
                    MenuHeader("Playlists")
                    playlists.forEach { p ->
                        MenuRow("${p.name} (${counts[p.id] ?: 0})", (listFilter as? ListFilter.Playlist)?.id == p.id) {
                            vm.selectList(ListFilter.Playlist(p.id)); close()
                        }
                    }
                    MenuRow("+ New Playlist", false, color = Palette.Accent) { close(); newPlaylistFor = "" }
                    activePlaylist?.let { p ->
                        HorizontalDivider(color = Palette.Border)
                        MenuRow("Rename \"${p.name}\"", false) { close(); renameTarget = p }
                        MenuRow("Delete \"${p.name}\"", false, color = Palette.Alert) { close(); deletePlaylistTarget = p }
                    }
                }
                ActionButton(
                    "▶ Play", { videos.firstOrNull()?.let { first -> open(first, videos.drop(1).map { it.videoId }) } },
                    enabled = videos.isNotEmpty(), accent = true, height = 38.dp, size = 12, hPad = 10.dp, vPad = 4.dp
                )
            }
            Spacer(Modifier.height(6.dp))

            VideoList(
                videos = videos, refreshing = busy != null, onRefresh = vm::refresh,
                emptyText = when (listFilter) {
                    ListFilter.Feed -> "No videos yet. Pull down to refresh or add a channel."
                    ListFilter.Favorites -> "No favorites yet."
                    ListFilter.Downloads -> "No downloaded videos."
                    is ListFilter.Playlist -> "This playlist is empty. Use the ⋮ menu on a video to add it."
                },
                progress = archiveProgress, membership = membership, activePlaylistId = activePlaylist?.id,
                onOpen = { v ->
                    val queue = if (listFilter is ListFilter.Playlist) {
                        videos.dropWhile { it.videoId != v.videoId }.drop(1).map { it.videoId }
                    } else emptyList()
                    open(v, queue)
                },
                onFav = vm::setFavorite, onRead = vm::markRead,
                onDeleteDownload = { deleteDownloadTarget = it },
                onAddToPlaylist = { playlistTarget = it },
                onRemoveFromPlaylist = { v -> activePlaylist?.let { vm.removeFromPlaylist(it.id, it.name, v.videoId) } }
            )
        }
        busy?.let { LoadingOverlay(it, Modifier.fillMaxSize()) }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    adHocState?.let { st ->
        VideoLinkSheet(
            state = st, vm = vm, subscribedIds = channels.map { it.channelId }.toSet(),
            onDismiss = { vm.dismissAdHoc() },
            onAddToPlaylist = { v -> playlistTarget = v }
        )
    }
    // - Dialogs -
    if (showAddChannel) AddChannelSheet(
        subFeeds, (channelFilter as? ChannelFilter.Group)?.id,
        onDismiss = { showAddChannel = false },
        onSave = { input, sub, ex ->
            val vid = AdHocVideo.videoIdOf(input)
            if (vid != null) vm.openVideoLink(vid) else vm.addChannel(input, sub, ex)
            showAddChannel = false
        },
        onAddCategory = vm::addSubFeed
    )
    if (showSettings) SettingsDialog(
        syncing = busy != null,
        channels = channels, subFeeds = subFeeds, hiddenChannels = hiddenChannels, videoChannelTitles = videoChannelTitles,
        onToggleHidden = vm::toggleHidden,
        onToggleChannelAlerts = vm::setChannelAlerts,
        onLookbackChange = vm::setLookbackHours,
        onDeleteChannel = vm::deleteChannel,
        onSync = { if (busy == null) vm.refreshAll(); showSettings = false },
        onDismiss = { showSettings = false }
    )
    deleteDownloadTarget?.let { v ->
        DeleteDownloadDialog(v.title, { deleteDownloadTarget = null }, { vm.purgeArchive(v.videoId); deleteDownloadTarget = null })
    }
    deleteGroupTarget?.let { t ->
        AppDialog(onDismiss = { deleteGroupTarget = null }) {
            AppLabel("Delete group?", Palette.Alert, 16.sp)
            Text(
                "\"${t.name}\" and its channels and videos will be removed.",
                color = Palette.Text, fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Cancel", { deleteGroupTarget = null }, Modifier.weight(1f))
                ActionButton("Delete", { vm.deleteSubFeed(t.id); deleteGroupTarget = null }, Modifier.weight(1f), accent = true)
            }
        }
    }
    // Add to playlist: same picker the Audio deck uses.
    playlistTarget?.let { v ->
        PlaylistPickerDialog(
            title = "Add to Playlist",
            playlists = playlists.map { MusicPlaylistEntity(it.id, it.name, it.createdAt) }, counts = counts, activeId = -1L,
            onPick = { id ->
                playlists.firstOrNull { it.id == id }?.let { vm.addToPlaylist(it.id, it.name, v.videoId) }
                playlistTarget = null
            },
            onNew = { playlistTarget = null; newPlaylistFor = v.videoId },
            onDismiss = { playlistTarget = null }
        )
    }
    newPlaylistFor?.let { videoId ->
        var name by remember { mutableStateOf("") }
        AppDialog({ newPlaylistFor = null }) {
            AppLabel("New Playlist", Palette.Amber, 16.sp)
            NameField(name, { name = it }, "Playlist name")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Cancel", { newPlaylistFor = null }, Modifier.weight(1f))
                ActionButton("Create", {
                    vm.createPlaylist(name, videoId.ifEmpty { null }, select = videoId.isEmpty())
                    newPlaylistFor = null
                }, Modifier.weight(1f), enabled = name.isNotBlank(), accent = true)
            }
        }
    }
    renameTarget?.let { p ->
        var name by remember { mutableStateOf(p.name) }
        AppDialog({ renameTarget = null }) {
            AppLabel("Rename Playlist", Palette.Amber, 16.sp)
            NameField(name, { name = it }, "Playlist name")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Cancel", { renameTarget = null }, Modifier.weight(1f))
                ActionButton("Rename", { vm.renamePlaylist(p.id, name); renameTarget = null }, Modifier.weight(1f), enabled = name.isNotBlank(), accent = true)
            }
        }
    }
    deletePlaylistTarget?.let { p ->
        AppDialog({ deletePlaylistTarget = null }) {
            AppLabel("Delete playlist?", Palette.Alert, 16.sp)
            Text("Delete \"${p.name}\"? The videos themselves are kept.", color = Palette.Text, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Cancel", { deletePlaylistTarget = null }, Modifier.weight(1f))
                ActionButton("Delete", { vm.deletePlaylist(p.id); deletePlaylistTarget = null }, Modifier.weight(1f), accent = true)
            }
        }
    }
}

// - Dropdown building blocks ------------------------

/** Compact top-bar dropdown: a button that opens a [DropdownMenu]. */
@Composable
private fun DropdownSelector(
    label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.(close: () -> Unit) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        ActionButton("$label ▼", { open = true }, Modifier.fillMaxWidth(), height = 38.dp, size = 12, hPad = 8.dp, vPad = 4.dp)
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.background(Palette.Surface).border(1.dp, Palette.Border)
        ) { content { open = false } }
    }
}

@Composable
private fun MenuRow(
    text: String, selected: Boolean = false, color: Color = Palette.Text,
    trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text, color = if (selected) Palette.Accent else color, fontFamily = Heavy,
                fontWeight = if (selected) FontWeight.Black else FontWeight.Bold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        },
        onClick = onClick,
        leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, null, tint = Palette.Accent, modifier = Modifier.size(18.dp)) }) else null,
        trailingIcon = trailing
    )
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text.uppercase(), color = Palette.Dim, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
    )
}

// - Video list -------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoList(
    videos: List<VideoEntity>, refreshing: Boolean, onRefresh: () -> Unit, emptyText: String,
    progress: Map<String, Int>, membership: Map<String, Set<Long>>, activePlaylistId: Long?,
    onOpen: (VideoEntity) -> Unit, onFav: (String, Boolean) -> Unit, onRead: (String, Boolean) -> Unit,
    onDeleteDownload: (VideoEntity) -> Unit, onAddToPlaylist: (VideoEntity) -> Unit,
    onRemoveFromPlaylist: (VideoEntity) -> Unit
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing, onRefresh = onRefresh, state = state, modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state, isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = Palette.Surface, color = Palette.Accent
            )
        }
    ) {
        LazyColumn(
            Modifier.fillMaxSize().background(Palette.Surface, RoundedCornerShape(8.dp)).border(1.dp, Palette.Border, RoundedCornerShape(8.dp)),
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (videos.isEmpty()) item {
                Box(Modifier.fillParentMaxSize().padding(24.dp), Alignment.Center) {
                    Text(emptyText, color = Palette.Dim, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            items(videos, key = { it.videoId }) { v ->
                VideoCard(
                    v, progress[v.videoId], inActivePlaylist = activePlaylistId != null && activePlaylistId in (membership[v.videoId] ?: emptySet()),
                    onOpen = { onOpen(v) }, onFav = onFav, onRead = onRead, onDeleteDownload = onDeleteDownload,
                    onAddToPlaylist = onAddToPlaylist, onRemoveFromPlaylist = onRemoveFromPlaylist
                )
            }
        }
    }
}

@Composable
private fun VideoCard(
    v: VideoEntity, archivePct: Int?, inActivePlaylist: Boolean,
    onOpen: () -> Unit, onFav: (String, Boolean) -> Unit, onRead: (String, Boolean) -> Unit,
    onDeleteDownload: (VideoEntity) -> Unit, onAddToPlaylist: (VideoEntity) -> Unit, onRemoveFromPlaylist: (VideoEntity) -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Palette.Bg, shape).border(1.dp, Palette.Border, shape)
            .clickable(onClick = onOpen).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = v.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).alpha(if (v.isRead) 0.5f else 1f)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).alpha(if (v.isRead) 0.6f else 1f)) {
            Text(
                v.title, color = Palette.Text, fontFamily = Heavy, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(v.channelTitle, color = Palette.Accent, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(relativeTime(v.publishedAt), color = Palette.Dim, fontFamily = Mono, fontSize = 10.sp)
            if (archivePct != null && !v.isArchived) {
                Text(
                    "Downloading: ${archivePct.coerceIn(0, 99)}%", Modifier.padding(top = 2.dp),
                    color = Palette.Amber, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp
                )
            }
            if (v.isArchived) {
                Row(
                    Modifier.padding(top = 3.dp).border(1.dp, Palette.Accent, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("Downloaded", color = Palette.Accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1)
                    Icon(Icons.Filled.Check, null, tint = Palette.Accent, modifier = Modifier.size(11.dp))
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                IconButton({ menu = true }, Modifier.size(40.dp)) { Icon(Icons.Filled.MoreVert, "More actions", tint = Palette.Text) }
                DropdownMenu(
                    expanded = menu, onDismissRequest = { menu = false },
                    modifier = Modifier.background(Palette.Surface).border(1.dp, Palette.Border)
                ) {
                    MenuRow("Add to Playlist...") { menu = false; onAddToPlaylist(v) }
                    if (inActivePlaylist) MenuRow("Remove from This Playlist") { menu = false; onRemoveFromPlaylist(v) }
                    MenuRow(if (v.isFavorite) "Remove Favorite" else "Add to Favorites") { menu = false; onFav(v.videoId, !v.isFavorite) }
                    MenuRow(if (v.isRead) "Mark as Unwatched" else "Mark as Watched") { menu = false; onRead(v.videoId, !v.isRead) }
                    if (v.isArchived) MenuRow("Delete Download", color = Palette.Alert) { menu = false; onDeleteDownload(v) }
                }
            }
            IconButton({ onFav(v.videoId, !v.isFavorite) }, Modifier.size(40.dp)) {
                Icon(
                    if (v.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    if (v.isFavorite) "Remove favorite" else "Add favorite",
                    tint = if (v.isFavorite) Palette.Accent else Palette.Dim
                )
            }
        }
    }
}

/** Confirmation before a downloaded video file is deleted. */
@Composable
fun DeleteDownloadDialog(videoTitle: String, onCancel: () -> Unit, onDelete: () -> Unit) {
    AppDialog(onDismiss = onCancel) {
        AppLabel("Delete download?", Palette.Alert, 16.sp)
        Text(
            "Permanently delete the downloaded copy of '$videoTitle'? This action cannot be undone.",
            color = Palette.Text, fontSize = 13.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Cancel", onCancel, Modifier.weight(1f))
            ActionButton("Delete", onDelete, Modifier.weight(1f), accent = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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

    AppFormDialog(onDismiss) {
        AppLabel("Add Channel", Palette.Text, 18.sp)
        NameField(input, { input = it }, "@handle, channel URL, or a single video link")
        val isVideoLink = AdHocVideo.videoIdOf(input) != null
        if (isVideoLink) Text("Video link detected. You can watch, summarize or save it without subscribing.", color = Palette.Accent, fontFamily = Mono, fontSize = 11.sp)
        AppLabel("Group", Palette.Dim, 11.sp)
        if (subFeeds.isEmpty()) Text("No groups yet. Create one below.", color = Palette.Dim, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            subFeeds.forEach { Chip(it.name, selected?.id == it.id) { selected = it } }
        }
        NameField(newCategory, { newCategory = it }, "New group name")
        ActionButton(
            "+ Create Group",
            { pendingName = newCategory.trim(); onAddCategory(newCategory.trim()); newCategory = "" },
            Modifier.fillMaxWidth(), enabled = newCategory.isNotBlank()
        )
        Chip(if (excludeShorts) "✓ Exclude Shorts" else "Exclude Shorts", excludeShorts) { excludeShorts = !excludeShorts }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Cancel", onDismiss, Modifier.weight(1f))
            ActionButton(
                if (isVideoLink) "Open Video" else "Add Channel", { onSave(input, selected?.id ?: -1L, excludeShorts) },
                Modifier.weight(2f), enabled = input.isNotBlank() && (selected != null || isVideoLink), accent = true
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
