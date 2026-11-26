package com.voxfeed.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voxfeed.app.data.*
import com.voxfeed.app.worker.PollPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Which channel(s) the video list is restricted to. */
sealed interface ChannelFilter {
    data object All : ChannelFilter
    data class Group(val id: Long) : ChannelFilter
    data class Channel(val id: String) : ChannelFilter
}

/** Which collection the video list shows. */
sealed interface ListFilter {
    data object Feed : ListFilter
    data object Favorites : ListFilter
    data object Downloads : ListFilter
    data class Playlist(val id: Long) : ListFilter
}

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModel(app: Application) : AndroidViewModel(app) {
    private val db = VoxDatabase.get(app)
    private val repo = YouTubeRssRepository(db, app)

    val subFeeds: StateFlow<List<SubFeedEntity>> = db.subFeedDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val prefs = app.getSharedPreferences("vox_prefs", android.content.Context.MODE_PRIVATE)

    /** All subscribed channels. */
    val channels: StateFlow<List<ChannelEntity>> = db.channelDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** channelId -> channelTitle from stored videos; fallback label for channels named "Videos". */
    val videoChannelTitles: StateFlow<Map<String, String>> = db.videoDao().observeChannelTitles()
        .map { rows -> rows.associate { it.channelId to it.channelTitle } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Channel ids hidden from the main feed (SharedPreferences; no Room migration). */
    val hiddenChannels = MutableStateFlow((prefs.getStringSet(HIDDEN_KEY, null) ?: prefs.getStringSet("hidden_channel_ids", null))?.toSet() ?: emptySet())

    private fun setHidden(ids: Set<String>) {
        hiddenChannels.value = ids
        prefs.edit().putStringSet(HIDDEN_KEY, ids).apply()
    }

    fun setChannelAlerts(channelId: String, enabled: Boolean) = viewModelScope.launch {
        db.channelDao().setAlertsEnabled(channelId, enabled)
    }

    fun toggleHidden(channelId: String) {
        val cur = hiddenChannels.value
        setHidden(if (channelId in cur) cur - channelId else cur + channelId)
    }

    /** Deletes the channel and its non-downloaded videos. Downloaded videos are kept (FK cascade would remove them). */
    fun deleteChannel(channelId: String) = viewModelScope.launch {
        val videoDao = db.videoDao()
        val archived = videoDao.countArchivedByChannel(channelId)
        videoDao.deleteUnarchivedByChannel(channelId)
        if (archived == 0) {
            db.channelDao().delete(channelId)
            setHidden(hiddenChannels.value - channelId)
            if ((channelFilter.value as? ChannelFilter.Channel)?.id == channelId) channelFilter.value = ChannelFilter.All
            message.value = "Channel removed"
        } else {
            setHidden(hiddenChannels.value + channelId)
            message.value = "Videos removed. $archived downloaded video(s) kept and the channel is hidden. Delete the downloads to fully remove it."
        }
    }

    val channelFilter = MutableStateFlow<ChannelFilter>(ChannelFilter.All)
    val listFilter = MutableStateFlow<ListFilter>(ListFilter.Feed)
    /** Non-null label while refreshing / resolving; drives the loading overlay. */
    val busy = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    /** videoId -> live download percent (0..100). */
    val archiveProgress: StateFlow<Map<String, Int>> = MediaArchiveManager.get(app).progress

    /** Active scrape window (hours; 0 = all). Changing it re-queries the displayed feed immediately. */
    val lookbackHours = MutableStateFlow(PollPrefs.lookbackWindowHours(app))
    fun setLookbackHours(h: Int) {
        PollPrefs.setLookbackWindowHours(getApplication(), h)
        lookbackHours.value = h
    }

    // - Video playlists -
    private val playlistDao = db.videoPlaylistDao()
    val videoPlaylists: StateFlow<List<VideoPlaylistEntity>> = playlistDao.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlistCounts: StateFlow<Map<Long, Int>> = playlistDao.observeItems()
        .map { items -> items.groupingBy { it.playlistId }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    /** videoId -> ids of the playlists containing it. */
    val playlistMembership: StateFlow<Map<String, Set<Long>>> = playlistDao.observeItems()
        .map { items -> items.groupBy({ it.videoId }, { it.playlistId }).mapValues { it.value.toSet() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private data class Query(val ch: ChannelFilter, val list: ListFilter, val hidden: Set<String>, val hours: Int)

    val videos: StateFlow<List<VideoEntity>> =
        combine(channelFilter, listFilter, hiddenChannels, lookbackHours) { c, l, h, hours -> Query(c, l, h, hours) }
            .flatMapLatest { (ch, list, hidden, hours) ->
                val dao = db.videoDao()
                val source: Flow<List<VideoEntity>> = when (list) {
                    ListFilter.Feed -> dao.observeAll().map { all ->
                        // Display-time window applies to the feed only; saved lists are never hidden by it.
                        val now = System.currentTimeMillis()
                        all.filter { LookbackFilter.passes(it.publishedAt, hours, now) && it.channelId !in hidden && it.channelId != AdHocVideo.CHANNEL_ID }
                    }
                    ListFilter.Favorites -> dao.observeFavorites()
                    ListFilter.Downloads -> dao.observeArchived()
                    is ListFilter.Playlist -> playlistDao.observeVideos(list.id)
                }
                source.map { vids ->
                    when (ch) {
                        ChannelFilter.All -> vids
                        is ChannelFilter.Group -> vids.filter { it.subFeedId == ch.id }
                        is ChannelFilter.Channel -> vids.filter { it.channelId == ch.id }
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectChannel(f: ChannelFilter) { channelFilter.value = f }
    fun selectList(f: ListFilter) { listFilter.value = f }

    fun refresh() = viewModelScope.launch {
        busy.value = "Refreshing feeds..."
        val failed = runCatching {
            when (val f = channelFilter.value) {
                is ChannelFilter.Group -> repo.refreshSubFeed(f.id)
                else -> repo.refreshAll()
            }
        }.getOrElse { message.value = "Refresh failed: ${it.message}"; 0 }
        if (failed > 0) message.value = "$failed channel(s) failed to refresh"
        busy.value = null
    }

    fun refreshAll() = viewModelScope.launch {
        busy.value = "Refreshing feeds..."
        val failed = runCatching { repo.refreshAll() }.getOrElse { message.value = "Refresh failed: ${it.message}"; 0 }
        if (failed > 0) message.value = "$failed channel(s) failed to refresh"
        busy.value = null
    }

    fun addChannel(input: String, subFeedId: Long, excludeShorts: Boolean) = viewModelScope.launch {
        busy.value = "Adding channel..."
        runCatching { repo.addChannel(input, subFeedId, excludeShorts) }
            .onSuccess { message.value = "Channel added" }
            .onFailure { message.value = "Add failed: ${it.message}" }
        busy.value = null
    }

    // -- Single-video link (ad-hoc, no subscription) --
    sealed interface AdHocState {
        data class Loading(val videoId: String) : AdHocState
        data class Ready(val meta: AdHocMeta) : AdHocState
        data class Failed(val message: String) : AdHocState
    }
    val adHoc = MutableStateFlow<AdHocState?>(null)
    /** Set once the user taps Subscribe on the sheet, so the sheet can show the result right away. */
    private var adHocJob: kotlinx.coroutines.Job? = null

    fun openVideoLink(videoId: String) {
        adHocJob?.cancel()
        adHoc.value = AdHocState.Loading(videoId)
        adHocJob = viewModelScope.launch {
            adHoc.value = runCatching { AdHocVideo.fetch(videoId) }
                .fold({ AdHocState.Ready(it) }, { AdHocState.Failed(it.message ?: it.javaClass.simpleName) })
        }
    }

    fun dismissAdHoc() { adHocJob?.cancel(); adHoc.value = null }

    /** Persists the pasted video (if needed) and hands back the stored row. */
    fun withAdHocVideo(meta: AdHocMeta, block: (VideoEntity) -> Unit) = viewModelScope.launch {
        runCatching { repo.ensureAdHocVideo(meta) }
            .onSuccess(block)
            .onFailure { message.value = "Could not save video: ${it.message}" }
    }

    fun subscribeFromAdHoc(meta: AdHocMeta) = viewModelScope.launch {
        val subFeedId = (channelFilter.value as? ChannelFilter.Group)?.id
            ?: subFeeds.value.firstOrNull()?.id ?: db.subFeedDao().getAll().firstOrNull()?.id
            ?: db.subFeedDao().insert(SubFeedEntity(name = "General", sortOrder = 0))
        repo.ensureAdHocVideo(meta)
        runCatching { repo.subscribeToVideoChannel(meta, subFeedId) }
            .onSuccess { message.value = "Subscribed to ${meta.channelName}" }
            .onFailure { message.value = "Subscribe failed: ${it.message}" }
    }

    /** Caches the video's audio straight into the Audio Deck's default playlist. */
    fun extractToAudioDeck(meta: AdHocMeta) = viewModelScope.launch {
        val dao = db.musicDao()
        val pl = dao.getOrCreatePlaylist(MusicDao.DEFAULT_PLAYLIST)
        val url = "https://www.youtube.com/watch?v=${meta.videoId}"
        if (dao.countBySource(pl.id, url) == 0) dao.appendTracks(
            pl.id, listOf(
                MusicTrackEntity(
                    playlistId = pl.id, title = meta.title, artist = meta.channelName,
                    durationMs = meta.durationSeconds * 1000, sourceUrl = url, thumbnailUrl = meta.thumbnailUrl,
                    localUri = null, position = 0
                )
            )
        )
        val row = dao.getTracks(pl.id).firstOrNull { it.sourceUrl == url } ?: return@launch
        if (row.localUri != null) message.value = "Already in Audio Deck (cached)"
        else { MusicDownloader.enqueue(getApplication(), row, pl.name); message.value = "Caching audio to ${pl.name}..." }
    }

    fun digestFlow(videoId: String, depth: SummaryDepth): Flow<String?> = db.digestDao().observe(videoId, depth.name)

    suspend fun generateDigest(meta: AdHocMeta, depth: SummaryDepth): DigestResult {
        repo.ensureAdHocVideo(meta) // FK parent for the cached digest
        return try {
            AiSummaryManager.generate(PollPrefs.geminiKey(getApplication()), meta.videoId, meta.title, meta.description, depth, db.digestDao())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DigestResult.NetworkOrApiError(e.message ?: e.javaClass.simpleName)
        }
    }
    fun addSubFeed(name: String) = viewModelScope.launch {
        val dao = db.subFeedDao()
        dao.insert(SubFeedEntity(name = name.trim(), sortOrder = dao.maxSortOrder() + 1))
    }

    fun deleteSubFeed(id: Long) = viewModelScope.launch {
        if ((channelFilter.value as? ChannelFilter.Group)?.id == id) channelFilter.value = ChannelFilter.All
        db.subFeedDao().delete(id)
    }

    fun markRead(id: String, read: Boolean = true) = viewModelScope.launch { db.videoDao().setRead(id, read) }
    fun setFavorite(id: String, fav: Boolean) = viewModelScope.launch { db.videoDao().setFavorite(id, fav) }
    fun purgeArchive(id: String) = viewModelScope.launch {
        runCatching { MediaArchiveManager.get(getApplication()).deleteArchive(id) }
            .onFailure { message.value = "Delete failed: ${it.message}" }
    }
    fun clearMessage() { message.value = null }

    // - Playlist actions -
    /** Creates a playlist, optionally adding [videoId] to it, and returns nothing (observers refresh the UI). */
    fun createPlaylist(name: String, videoId: String? = null, select: Boolean = false) = viewModelScope.launch {
        val id = playlistDao.insertPlaylist(VideoPlaylistEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
        if (videoId != null) playlistDao.addItem(VideoPlaylistItemEntity(id, videoId, System.currentTimeMillis()))
        if (select) listFilter.value = ListFilter.Playlist(id)
        message.value = if (videoId != null) "Added to ${name.trim()}" else "Playlist created"
    }

    fun addToPlaylist(playlistId: Long, name: String, videoId: String) = viewModelScope.launch {
        val added = playlistDao.addItem(VideoPlaylistItemEntity(playlistId, videoId, System.currentTimeMillis())) != -1L
        message.value = if (added) "Added to $name" else "Already in $name"
    }

    fun removeFromPlaylist(playlistId: Long, name: String, videoId: String) = viewModelScope.launch {
        playlistDao.removeItem(playlistId, videoId)
        message.value = "Removed from $name"
    }

    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { playlistDao.rename(id, name.trim()) }

    fun deletePlaylist(id: Long) = viewModelScope.launch {
        if ((listFilter.value as? ListFilter.Playlist)?.id == id) listFilter.value = ListFilter.Feed
        playlistDao.deletePlaylist(id)
        message.value = "Playlist deleted"
    }

    private companion object { const val HIDDEN_KEY = "hidden_channels" }
}
