package com.voxfeed.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.voxfeed.app.data.MediaArchiveManager
import com.voxfeed.app.data.MusicDao
import com.voxfeed.app.data.MusicTrackEntity
import com.voxfeed.app.data.VideoEntity
import com.voxfeed.app.data.VoxDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Browse tree + playback entry points for Android Auto / Google Assistant. It is attached to the same
 * [MediaLibrarySession] (and therefore the same ExoPlayer) the in-app Audio Deck controls, so the car and
 * the phone always stay in sync.
 *
 * Tree:  root -> [Cached / Offline | Audio Playlists -> playlist -> tracks | Favorites]
 * Track ids are "music:<rowId>", identical to the ids the Audio Deck queues.
 */
@UnstableApi
class VoxLibraryCallback(private val context: Context) : MediaLibrarySession.Callback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao: MusicDao by lazy { VoxDatabase.get(context.applicationContext).musicDao() }
    private val channelDao by lazy { VoxDatabase.get(context.applicationContext).channelDao() }
    private val videoDao by lazy { VoxDatabase.get(context.applicationContext).videoDao() }
    private val vpDao by lazy { VoxDatabase.get(context.applicationContext).videoPlaylistDao() }
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun release() = scope.cancel()

    /** Persists the queue + position so [onPlaybackResumption] can restart it (media button, car reconnect). */
    fun attach(session: MediaLibrarySession, player: Player) {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = saveState(player)
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) saveState(player) }
        })
    }

    private fun saveState(player: Player) {
        runCatching {
            val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            val idx = player.currentMediaItemIndex
            if (ids.isEmpty() || idx !in ids.indices || !isPlayableId(ids[idx])) return
            prefs.edit()
                .putString(KEY_QUEUE, ids.filter { isPlayableId(it) }.joinToString(","))
                .putString(KEY_CURRENT, ids[idx])
                .putLong(KEY_POSITION, player.currentPosition.coerceAtLeast(0))
                .apply()
        }
    }

    /** Makes sure every controller (Android Auto, Assistant, phone) may read the queue and jump within it. */
    override fun onConnect(
        session: MediaSession, controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val player = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_SET_MEDIA_ITEM)
            .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SET_SHUFFLE_MODE)
            .add(Player.COMMAND_SET_REPEAT_MODE)
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
            .setAvailablePlayerCommands(player)
            .build()
    }

    // ------- browsing -------

    override fun onGetLibraryRoot(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> =
        immediate(LibraryResult.ofItem(folder(ROOT, "VoxPlayer"), params))


    override fun onGetItem(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> = async {
        val action = parseAction(mediaId)
        val item = when {
            mediaId == ROOT -> folder(ROOT, "VoxPlayer")
            mediaId == CACHED -> folder(CACHED, "Cached / Offline Tracks")
            mediaId == PLAYLISTS -> folder(PLAYLISTS, "Audio Playlists")
            mediaId == FAVORITES -> folder(FAVORITES, "Favorites")
            mediaId == PODCASTS -> folder(PODCASTS, "Podcasts")
            mediaId == VPLAYLISTS -> folder(VPLAYLISTS, "Video Playlists")
            mediaId.startsWith(VPLAYLIST_PREFIX) -> mediaId.removePrefix(VPLAYLIST_PREFIX).toLongOrNull()
                ?.let { vpDao.getPlaylist(it) }?.let { folder(mediaId, it.name) }
            action != null -> actionItem(action.second, action.first)
            mediaId.startsWith(PLAYLIST_PREFIX) -> mediaId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                ?.let { dao.getPlaylist(it) }?.let { folder(mediaId, it.name) }
            mediaId.startsWith(CHANNEL_PREFIX) ->
                channelDao.getAllChannels().firstOrNull { "$CHANNEL_PREFIX${it.channelId}" == mediaId }?.let { folder(mediaId, it.title) }
            else -> itemById(mediaId, null, resolve = false)
        }
        if (item != null) LibraryResult.ofItem(item, null)
        else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
    }

    override fun onGetChildren(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
        page: Int, pageSize: Int, params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = async {
        val items: List<MediaItem>? = when {
            parentId == ROOT -> listOf(
                folder(CACHED, "Cached / Offline Tracks"),
                folder(PLAYLISTS, "Audio Playlists"),
                folder(FAVORITES, "Favorites"),
                folder(PODCASTS, "Podcasts"),
                folder(VPLAYLISTS, "Video Playlists"),
            )
            parentId == PLAYLISTS -> dao.getPlaylists().map { folder("$PLAYLIST_PREFIX${it.id}", it.name) }
            parentId == VPLAYLISTS -> vpDao.getPlaylists().map { folder("$VPLAYLIST_PREFIX${it.id}", it.name) }
            parentId == PODCASTS -> channelDao.getAllChannels().map { folder("$CHANNEL_PREFIX${it.channelId}", it.title) }
            else -> listItems(parentId, resolve = false)?.let { list ->
                // Quick-play actions pinned above the list.
                if (list.isEmpty()) list else listOf(actionItem(parentId, false), actionItem(parentId, true)) + list
            }
        }
        if (items == null) LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        else LibraryResult.ofItemList(ImmutableList.copyOf(paginate(items, page, pageSize)), params)
    }

    /** Playable items under a browse container (null = not a track container). Ctx of each item = [parentId]. */
    private suspend fun listItems(parentId: String, resolve: Boolean): List<MediaItem>? = when {
        parentId == CACHED -> dao.getCachedTracks().distinctBy { it.sourceUrl }.map { buildItem(it, parentId, resolve) }
        parentId == FAVORITES -> dao.getFavoriteTracks().distinctBy { it.sourceUrl }.map { buildItem(it, parentId, resolve) }
        parentId.startsWith(PLAYLIST_PREFIX) ->
            parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()?.let { id -> dao.getTracks(id).map { buildItem(it, parentId, resolve) } }
        parentId.startsWith(VPLAYLIST_PREFIX) -> parentId.removePrefix(VPLAYLIST_PREFIX).toLongOrNull()?.let { id -> vpDao.getVideos(id).map { buildVideoItem(it, parentId) } }
        parentId.startsWith(CHANNEL_PREFIX) ->
            videoDao.getRecentByChannel(parentId.removePrefix(CHANNEL_PREFIX), MAX_EPISODES).map { buildVideoItem(it, parentId) }
        parentId.startsWith(SEARCH_CTX) -> search(parentId.removePrefix(SEARCH_CTX)).map { buildItem(it, parentId, resolve) }
        else -> null
    }

    /** "Play All" / "Shuffle" pseudo-track: playable, queues the entire [parentId] list when tapped. */
    private fun actionItem(parentId: String, shuffle: Boolean): MediaItem = MediaItem.Builder()
        .setMediaId((if (shuffle) SHUFFLE_PREFIX else PLAYALL_PREFIX) + parentId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(if (shuffle) "Shuffle" else "Play All")
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MIXED)
                .build()
        ).build()

    /** Returns (shuffle, parentId) for an action id, else null. */
    private fun parseAction(mediaId: String): Pair<Boolean, String>? = when {
        mediaId.startsWith(PLAYALL_PREFIX) -> false to mediaId.removePrefix(PLAYALL_PREFIX)
        mediaId.startsWith(SHUFFLE_PREFIX) -> true to mediaId.removePrefix(SHUFFLE_PREFIX)
        else -> null
    }

    // ------- search / voice -------

    override fun onSearch(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        scope.launch {
            val count = runCatching { search(query).size }.getOrDefault(0)
            session.notifySearchResultChanged(browser, query, count, params)
        }
        return immediate(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
        page: Int, pageSize: Int, params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = async {
        val items = search(query).map { buildItem(it, SEARCH_CTX + query, resolve = false) }
        LibraryResult.ofItemList(ImmutableList.copyOf(paginate(items, page, pageSize)), params)
    }

    /** Case-insensitive, every query word must hit title / artist / genre. Cached tracks sort first. */
    private suspend fun search(query: String): List<MusicTrackEntity> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val all = dao.getAllTracks().distinctBy { it.sourceUrl }
        val hits = if (words.isEmpty()) all else all.filter { t ->
            val hay = "${t.title} ${t.artist} ${t.genre}".lowercase()
            words.all { it in hay }
        }
        return hits.sortedByDescending { it.localUri != null }.take(MAX_SEARCH)
    }

    // ------- playback entry points -------

    override fun onAddMediaItems(
        mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>
    ): ListenableFuture<MutableList<MediaItem>> = async { mediaItems.flatMap { resolveItem(it) }.toMutableList() }

    override fun onSetMediaItems(
        mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        startIndex: Int, startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = async {
        val only = mediaItems.singleOrNull()
        val fromApp = only?.requestMetadata?.extras?.getStringArray(VoxMediaLibraryService.EXTRA_URIS) != null
        if (only != null && !fromApp) {
            val ctx = only.mediaMetadata.extras?.getString(EXTRA_CTX)
            if (parseAction(only.mediaId) == null && isPlayableId(only.mediaId) && ctx != null) {
                // Tapping one track in a list: queue the whole list so Next / Previous / Queue work on the head unit.
                val queue = listItems(ctx, resolve = true)
                val idx = queue?.indexOfFirst { it.mediaId == only.mediaId } ?: -1
                if (queue != null && idx >= 0) {
                    return@async MediaSession.MediaItemsWithStartPosition(queue, idx, startPositionMs.coerceAtLeast(0))
                }
            }
        }
        val resolved = mediaItems.flatMap { resolveItem(it) }
        if (resolved.isEmpty()) return@async MediaSession.MediaItemsWithStartPosition(resolved, C.INDEX_UNSET, C.TIME_UNSET)
        MediaSession.MediaItemsWithStartPosition(resolved, if (startIndex in resolved.indices) startIndex else 0, startPositionMs)
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession, controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = async {
        val ids = prefs.getString(KEY_QUEUE, null)?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        val current = prefs.getString(KEY_CURRENT, null)
        val queue = ids.mapNotNull { id -> itemById(id, null, resolve = true) }
        if (queue.isEmpty()) {
            // Nothing remembered: fall back to the cached library so "play" always does something.
            val cached = listItems(CACHED, resolve = true).orEmpty()
            if (cached.isEmpty()) throw UnsupportedOperationException("Nothing to resume")
            return@async MediaSession.MediaItemsWithStartPosition(cached, 0, 0)
        }
        val idx = queue.indexOfFirst { it.mediaId == current }.coerceAtLeast(0)
        val pos = if (queue[idx].mediaId == current) prefs.getLong(KEY_POSITION, 0) else 0
        MediaSession.MediaItemsWithStartPosition(queue, idx, pos)
    }

    /** Turns a controller-supplied item (browse item, action, voice query, or the Audio Deck's own item) into playable ones. */
    private suspend fun resolveItem(item: MediaItem): List<MediaItem> {
        val extras = item.requestMetadata.extras
        // Items from the in-app decks / video player already carry their URI.
        if (extras?.getStringArray(VoxMediaLibraryService.EXTRA_URIS) != null) return listOf(legacyResolve(item))
        parseAction(item.mediaId)?.let { (shuffle, parent) ->
            val list = listItems(parent, resolve = true).orEmpty()
            return if (shuffle) list.shuffled() else list
        }
        if (isPlayableId(item.mediaId)) {
            val ctx = item.mediaMetadata.extras?.getString(EXTRA_CTX)
            return listOfNotNull(itemById(item.mediaId, ctx, resolve = true))
        }
        val query = item.requestMetadata.searchQuery
        if (query != null) {
            return search(query).map { buildItem(it, SEARCH_CTX + query, resolve = true) }
        }
        return listOf(legacyResolve(item))
    }

    private fun legacyResolve(item: MediaItem): MediaItem {
        val first = item.requestMetadata.extras?.getStringArray(VoxMediaLibraryService.EXTRA_URIS)?.firstOrNull()
        val md = item.mediaMetadata
        fun clean(s: CharSequence?, fallback: String) = s?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: fallback
        val safe = item.buildUpon().setMediaMetadata(
            md.buildUpon()
                .setTitle(clean(md.title, "Unknown Audio-Stream"))
                .setArtist(clean(md.artist, "Unknown Artist"))
                .setAlbumTitle(clean(md.albumTitle, "VoxPlayer"))
                .build()
        )
        return if (first != null) safe.setUri(first).build() else safe.build()
    }

    // ------- item building -------

    private fun isPlayableId(id: String) = id.startsWith(MUSIC_PREFIX) || id.startsWith(VIDEO_PREFIX)

    /** Loads a track ("music:") or podcast episode ("video:") by media id. */
    private suspend fun itemById(mediaId: String, ctx: String?, resolve: Boolean): MediaItem? = when {
        mediaId.startsWith(VIDEO_PREFIX) -> videoDao.get(mediaId.removePrefix(VIDEO_PREFIX))?.let { buildVideoItem(it, ctx) }
        else -> trackById(mediaId)?.let { buildItem(it, ctx, resolve) }
    }

    /**
     * Subscribed-channel video -> audio-only item. Prefers the downloaded file (the audio track when the archive
     * holds separate video + audio); otherwise the watch URL, which the service's resolver swaps for the audio stream.
     */
    private fun buildVideoItem(v: VideoEntity, ctx: String?): MediaItem {
        val files = MediaArchiveManager.localSources(v.localVideoPath)
        val local = files?.let { if (it.size >= 2) it[1] else it[0] }
        val play = local?.let { Uri.fromFile(it).toString() } ?: "https://www.youtube.com/watch?v=${v.videoId}"
        val mdExtras = Bundle().apply { if (ctx != null) putString(EXTRA_CTX, ctx) }
        return MediaItem.Builder()
            .setMediaId("$VIDEO_PREFIX${v.videoId}")
            .setUri(play)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(v.title.takeIf { it.isNotBlank() } ?: "Unknown Episode")
                    .setArtist(v.channelTitle.takeIf { it.isNotBlank() } ?: "Unknown Channel")
                    .setAlbumTitle("Podcasts")
                    .setArtworkUri(v.thumbnailUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .setExtras(mdExtras)
                    .build()
            )
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setExtras(
                    VoxMediaLibraryService.extras(v.videoId, "", listOf(play)).apply {
                        // Audio-only: notification returns to the app, not the video player.
                        putBoolean(VoxMediaLibraryService.EXTRA_MUSIC, true)
                        putBoolean(VoxMediaLibraryService.EXTRA_CACHED, local != null)
                    }
                ).build()
            )
            .build()
    }

    private suspend fun trackById(mediaId: String): MusicTrackEntity? =
        mediaId.removePrefix(MUSIC_PREFIX).toLongOrNull()?.let { dao.getTrack(it) }

    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        ).build()

    /**
     * Track -> MediaItem. Playable URI prefers the cached local file; falls back to the stream (watch) URL.
     * [resolve] verifies the local file still exists (I/O) – only needed when the item will actually be played.
     */
    private fun buildItem(t: MusicTrackEntity, ctx: String?, resolve: Boolean): MediaItem {
        val local = t.localUri?.takeIf { !resolve || localExists(it) }
        val play = local ?: t.sourceUrl
        val title = t.title.takeIf { it.isNotBlank() && it != "null" } ?: "Unknown Audio"
        val artist = t.artist.takeIf { it.isNotBlank() && it != "null" } ?: "Unknown Artist"
        val mdExtras = Bundle().apply { if (ctx != null) putString(EXTRA_CTX, ctx) }
        return MediaItem.Builder()
            .setMediaId("$MUSIC_PREFIX${t.id}")
            .setUri(play)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle("VoxPlayer")
                    .setArtworkUri(t.thumbnailUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .setDurationMs(t.durationMs.takeIf { it > 0 })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(mdExtras)
                    .build()
            )
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setExtras(
                    VoxMediaLibraryService.extras(t.id.toString(), "", listOf(play)).apply {
                        putBoolean(VoxMediaLibraryService.EXTRA_MUSIC, true)
                        putBoolean(VoxMediaLibraryService.EXTRA_CACHED, local != null)
                    }
                ).build()
            )
            .build()
    }

    private fun localExists(uri: String): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun <T> paginate(list: List<T>, page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return list
        val from = (page.toLong() * pageSize).coerceAtMost(list.size.toLong()).toInt()
        return list.subList(from, (from + pageSize).coerceAtMost(list.size))
    }

    private fun <T> immediate(v: T): ListenableFuture<T> = SettableFuture.create<T>().also { it.set(v) }

    private fun <T> async(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch {
            try { f.set(block()) }
            catch (e: CancellationException) { f.cancel(false); throw e }
            catch (e: Throwable) { f.setException(e) }
        }
        return f
    }

    companion object {
        private const val PREFS = "vox_auto_state"
        private const val KEY_QUEUE = "queue"
        private const val KEY_CURRENT = "current"
        private const val KEY_POSITION = "position"
        private const val MUSIC_PREFIX = "music:"
        private const val VIDEO_PREFIX = "video:"
        private const val PODCASTS = "vox_podcasts"
        private const val VPLAYLISTS = "vox_vplaylists"
        private const val VPLAYLIST_PREFIX = "vox_vplaylist:"
        private const val CHANNEL_PREFIX = "vox_channel:"
        private const val PLAYALL_PREFIX = "vox_playall:"
        private const val SHUFFLE_PREFIX = "vox_shuffle:"
        private const val MAX_EPISODES = 50
        private const val ROOT = "vox_root"
        private const val CACHED = "vox_cached"
        private const val PLAYLISTS = "vox_playlists"
        private const val FAVORITES = "vox_favorites"
        private const val PLAYLIST_PREFIX = "vox_playlist:"
        private const val SEARCH_CTX = "vox_search:"
        private const val EXTRA_CTX = "vox.ctx"
        private const val MAX_SEARCH = 50
    }
}
