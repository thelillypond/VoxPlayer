package com.voxfeed.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voxfeed.app.data.*
import com.voxfeed.app.worker.PollPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface FeedFilter {
    data object All : FeedFilter
    data object Favorites : FeedFilter
    data object Archived : FeedFilter
    data class Sub(val id: Long) : FeedFilter
}

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModel(app: Application) : AndroidViewModel(app) {
    private val db = VoxDatabase.get(app)
    private val repo = YouTubeRssRepository(db, app)

    val subFeeds: StateFlow<List<SubFeedEntity>> = db.subFeedDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val prefs = app.getSharedPreferences("vox_prefs", android.content.Context.MODE_PRIVATE)

    /** All subscribed channels, for the FEEDS management list. */
    val channels: StateFlow<List<ChannelEntity>> = db.channelDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Channel ids hidden from the ALL feed (SharedPreferences; no Room migration). */
    /** channelId -> channelTitle from stored videos; fallback label for channels named "Videos". */
    val videoChannelTitles: StateFlow<Map<String, String>> = db.videoDao().observeChannelTitles()
        .map { rows -> rows.associate { it.channelId to it.channelTitle } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

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

    /** Deletes the channel and its non-archived videos. Archived slates are kept (FK cascade would remove them). */
    fun deleteChannel(channelId: String) = viewModelScope.launch {
        val videoDao = db.videoDao()
        val archived = videoDao.countArchivedByChannel(channelId)
        videoDao.deleteUnarchivedByChannel(channelId)
        if (archived == 0) {
            db.channelDao().delete(channelId)
            setHidden(hiddenChannels.value - channelId)
            message.value = "CHANNEL EXPUNGED"
        } else {
            setHidden(hiddenChannels.value + channelId)
            message.value = "UNARCHIVED ENTRIES PURGED // $archived ARCHIVED SLATE(S) RETAINED, CHANNEL HIDDEN - PURGE THEM TO FULLY REMOVE"
        }
    }

    val filter = MutableStateFlow<FeedFilter>(FeedFilter.All)
    /** Non-null label while scraping / resolving; drives the diagnostic overlay. */
    val busy = MutableStateFlow<String?>(null)
    val favCategory = MutableStateFlow<Long?>(null)
    val message = MutableStateFlow<String?>(null)
    /** videoId -> live archive download percent (0..100). */
    val archiveProgress: StateFlow<Map<String, Int>> = MediaArchiveManager.get(app).progress

    /** Active Scrape Window (hours; 0 = ALL). Changing it re-queries the displayed feed immediately. */
    val lookbackHours = MutableStateFlow(PollPrefs.lookbackWindowHours(app))
    fun setLookbackHours(h: Int) {
        PollPrefs.setLookbackWindowHours(getApplication(), h)
        lookbackHours.value = h
    }

    private data class Query(val f: FeedFilter, val c: Long?, val hidden: Set<String>, val hours: Int)

    val videos: StateFlow<List<VideoEntity>> = combine(filter, favCategory, hiddenChannels, lookbackHours) { f, c, h, l -> Query(f, c, h, l) }
        .flatMapLatest { (f, c, hidden, hours) ->
            // Display-time window (feed views only; favorites/archive are user-saved and never hidden by it).
            fun List<VideoEntity>.windowed(): List<VideoEntity> {
                val now = System.currentTimeMillis()
                return filter { LookbackFilter.passes(it.publishedAt, hours, now) && it.channelId !in hidden }
            }
            val dao = db.videoDao()
            when (f) {
                FeedFilter.All -> dao.observeAll().map { it.windowed() }
                FeedFilter.Archived -> dao.observeArchived()
                FeedFilter.Favorites -> if (c == null) dao.observeFavorites() else dao.observeFavoritesBySubFeed(c)
                is FeedFilter.Sub -> dao.observeBySubFeed(f.id).map { it.windowed() }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun select(f: FeedFilter) { filter.value = f }
    fun selectFavCategory(id: Long?) { favCategory.value = id }

    fun refresh() = viewModelScope.launch {
        busy.value = "COMMUNING WITH THE MACHINE SPIRITS..."
        val failed = runCatching {
            when (val f = filter.value) {
                is FeedFilter.Sub -> repo.refreshSubFeed(f.id)
                else -> repo.refreshAll()
            }
        }.getOrElse { message.value = "Refresh failed: ${it.message}"; 0 }
        if (failed > 0) message.value = "$failed channel(s) failed to refresh"
        busy.value = null
    }

    fun refreshAll() = viewModelScope.launch {
        busy.value = "COMMUNING WITH THE MACHINE SPIRITS..."
        val failed = runCatching { repo.refreshAll() }.getOrElse { message.value = "Refresh failed: ${it.message}"; 0 }
        if (failed > 0) message.value = "$failed channel(s) failed to refresh"
        busy.value = null
    }

    fun addChannel(input: String, subFeedId: Long, excludeShorts: Boolean) = viewModelScope.launch {
        busy.value = "POLLING TRANS-VOX..."
        runCatching { repo.addChannel(input, subFeedId, excludeShorts) }
            .onSuccess { message.value = "Channel added" }
            .onFailure { message.value = "Add failed: ${it.message}" }
        busy.value = null
    }

    fun addSubFeed(name: String) = viewModelScope.launch {
        val dao = db.subFeedDao()
        dao.insert(SubFeedEntity(name = name.trim(), sortOrder = dao.maxSortOrder() + 1))
    }

    fun deleteSubFeed(id: Long) = viewModelScope.launch {
        if ((filter.value as? FeedFilter.Sub)?.id == id) filter.value = FeedFilter.All
        if (favCategory.value == id) favCategory.value = null
        db.subFeedDao().delete(id)
    }

    fun markRead(id: String, read: Boolean = true) = viewModelScope.launch { db.videoDao().setRead(id, read) }
    fun setFavorite(id: String, fav: Boolean) = viewModelScope.launch { db.videoDao().setFavorite(id, fav) }
    fun purgeArchive(id: String) = viewModelScope.launch {
        runCatching { MediaArchiveManager.get(getApplication()).deleteArchive(id) }
            .onFailure { message.value = "Purge failed: ${it.message}" }
    }
        fun clearMessage() { message.value = null }

    private companion object { const val HIDDEN_KEY = "hidden_channels" }
}


