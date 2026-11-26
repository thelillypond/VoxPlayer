package com.voxfeed.app.data

import android.content.Context
import com.voxfeed.app.worker.PollPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime

class YouTubeRssRepository(private val db: VoxDatabase, context: Context) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient()
    private val channelDao = db.channelDao()
    private val videoDao = db.videoDao()

    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    private val idRegex = Regex("UC[a-zA-Z0-9_-]{22}")
    private val channelUrlRegex = Regex("youtube\\.com/channel/(UC[a-zA-Z0-9_-]{22})")
    private val handleRegex = Regex("@([A-Za-z0-9._-]+)")
    private val externalIdRegex = Regex("\"externalId\":\"(UC[a-zA-Z0-9_-]{22})\"")
    private val channelIdParamRegex = Regex("channel_id=(UC[a-zA-Z0-9_-]{22})")

    /** Resolves a raw UC id, /channel/UC... URL, or @handle (string or URL) to a channel id. */
    suspend fun resolveChannelId(input: String): String = withContext(Dispatchers.IO) {
        val s = input.trim()
        if (idRegex.matchEntire(s) != null) return@withContext s
        channelUrlRegex.find(s)?.let { return@withContext it.groupValues[1] }
        val handle = handleRegex.find(s)?.groupValues?.get(1)
            ?: throw IOException("Unrecognized channel input")
        val html = httpGet("https://www.youtube.com/@$handle", desktopUa)
            ?: throw IOException("Could not load channel page for @$handle")
        externalIdRegex.find(html)?.groupValues?.get(1)
            ?: channelIdParamRegex.find(html)?.groupValues?.get(1)
            ?: throw IOException("Channel ID not found for @$handle")
    }

    /** Adds (or updates) a channel and scrapes it immediately. */
    suspend fun addChannel(input: String, subFeedId: Long, excludeShorts: Boolean): String {
        val id = resolveChannelId(input)
        val inserted = channelDao.insertIgnore(
            ChannelEntity(id, subFeedId, input.trim(), excludeShorts, 0)
        )
        if (inserted == -1L) {
            channelDao.updateSettings(id, subFeedId, excludeShorts)
            videoDao.moveChannelVideos(id, subFeedId)
        }
        refreshChannel(channelDao.get(id)!!)
        return id
    }

    /**
     * Makes sure [meta] exists as a video row so playlists / summaries / playback state can reference it.
     * Unsubscribed channels park the video under the hidden ADHOC channel. Returns the stored row.
     */
    suspend fun ensureAdHocVideo(meta: AdHocMeta): VideoEntity = withContext(Dispatchers.IO) {
        videoDao.get(meta.videoId)?.let { return@withContext it }
        val subDao = db.subFeedDao()
        val subFeedId = (subDao.getAll().firstOrNull()
            ?: subDao.insert(SubFeedEntity(name = "General", sortOrder = 0)).let { SubFeedEntity(it, "General", 0) }).id
        val real = meta.channelId?.let { channelDao.get(it) }
        val owner = real ?: run {
            channelDao.insertIgnore(ChannelEntity(AdHocVideo.CHANNEL_ID, subFeedId, "Single videos", true, 0, alertsEnabled = false))
            channelDao.get(AdHocVideo.CHANNEL_ID)!!
        }
        val v = VideoEntity(
            videoId = meta.videoId, channelId = owner.channelId, subFeedId = owner.subFeedId,
            title = meta.title, channelTitle = meta.channelName, thumbnailUrl = meta.thumbnailUrl,
            publishedAt = System.currentTimeMillis(), description = meta.description
        )
        videoDao.insertAll(listOf(v))
        videoDao.get(meta.videoId) ?: v
    }

    /** Subscribes to the video's channel via RSS and re-homes the pasted video under it. Returns the channel id. */
    suspend fun subscribeToVideoChannel(meta: AdHocMeta, subFeedId: Long, excludeShorts: Boolean = true): String {
        val channelId = meta.channelId ?: throw IOException("Channel id unavailable for this video")
        addChannel(channelId, subFeedId, excludeShorts)
        videoDao.get(meta.videoId)?.takeIf { it.channelId == AdHocVideo.CHANNEL_ID }
            ?.let { videoDao.moveVideo(meta.videoId, channelId, subFeedId) }
        return channelId
    }
    suspend fun refreshAll(): Int = refresh(channelDao.getAll())

    class AlertScan(val failed: Int, val total: Int, val newVideos: List<VideoEntity>)

    /** Background poll: refreshes only alert-enabled channels and reports videos that were newly inserted. */
    suspend fun refreshForAlerts(): AlertScan = coroutineScope {
        val channels = channelDao.getAlertEnabled()
        val results = channels.map { ch -> async(Dispatchers.IO) { runCatching { refreshChannel(ch) } } }.awaitAll()
        AlertScan(results.count { it.isFailure }, channels.size, results.flatMap { it.getOrNull().orEmpty() })
    }

    suspend fun refreshSubFeed(subFeedId: Long): Int = refresh(channelDao.getBySubFeed(subFeedId))

    /** Returns number of channels that failed. */
    private suspend fun refresh(channels: List<ChannelEntity>): Int = coroutineScope {
        channels.map { ch ->
            async(Dispatchers.IO) { runCatching { refreshChannel(ch) }.isFailure }
        }.awaitAll().count { it }
    }

    private class ParsedFeed(val title: String?, val videos: List<VideoEntity>) {
        val newest: Long get() = videos.maxOfOrNull { it.publishedAt } ?: 0L
    }

    /** Applies the lookback window, inserts, and returns the videos that were actually new. */
    private suspend fun refreshChannel(ch: ChannelEntity): List<VideoEntity> = withContext(Dispatchers.IO) {
        val feed = fetchFeed(ch)
        val hours = PollPrefs.lookbackWindowHours(appContext)
        val now = System.currentTimeMillis()
        val videos = feed.videos.filter { LookbackFilter.passes(it.publishedAt, hours, now) }
        // IGNORE on conflict: existing rows are never replaced, so isRead / isFavorite / localVideoPath /
        // isArchived / voxDigest survive refreshes. Only description is ever updated, via a column-scoped query.
        val ids = videoDao.insertAll(videos)
        videos.forEach { if (it.description.isNotEmpty()) videoDao.updateDescription(it.videoId, it.description) }
        channelDao.updateChecked(ch.channelId, feed.title ?: ch.title, System.currentTimeMillis())
        videos.filterIndexed { i, _ -> ids.getOrNull(i) != -1L }
    }

    /**
     * Shorts-free UULF playlist feed first; if it is missing, empty or stale, the standard
     * channel feed is consulted and whichever has the newer upload wins.
     */
    private fun fetchFeed(ch: ChannelEntity): ParsedFeed {
        val base = "https://www.youtube.com/feeds/videos.xml?"
        var uulf: ParsedFeed? = null
        if (ch.excludeShorts) {
            uulf = httpGet(base + "playlist_id=UULF" + ch.channelId.removePrefix("UC"), null)
                ?.let { runCatching { parseFeed(it, ch) }.getOrNull() }
            val stale = uulf == null || uulf.videos.isEmpty() ||
                System.currentTimeMillis() - uulf.newest > STALE_MS
            if (!stale) return uulf!!
        }
        val std = httpGet(base + "channel_id=" + ch.channelId, null)?.let { parseFeed(it, ch) }
        return when {
            std == null -> uulf ?: throw IOException("Feed not found for ${ch.channelId}")
            uulf == null || uulf.videos.isEmpty() -> std
            std.newest > uulf.newest -> std
            else -> uulf
        }
    }
    /** Returns body, or null on 404. Throws on other failures. */
    private fun httpGet(url: String, userAgent: String?): String? {
        val req = Request.Builder().url(url).apply {
            if (userAgent != null) {
                header("User-Agent", userAgent)
                header("Cookie", "CONSENT=YES+1; SOCS=CAI")
                header("Accept-Language", "en-US,en;q=0.9")
            }
        }.build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            return resp.body?.string()
        }
    }

    private fun parseTime(s: String): Long {
        val v = s.trim()
        return runCatching { OffsetDateTime.parse(v).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(v).toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun parseFeed(xml: String, ch: ChannelEntity): ParsedFeed {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(StringReader(xml))
        var feedTitle: String? = null
        var feedAuthor: String? = null
        val out = mutableListOf<VideoEntity>()
        var inEntry = false
        var inAuthor = false
        var videoId = ""; var title = ""; var author = ""; var thumb = ""; var published = 0L; var desc = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "entry" -> {
                        inEntry = true
                        videoId = ""; title = ""; author = ""; thumb = ""; published = 0L; desc = ""
                    }
                    "author" -> inAuthor = true
                    "title" -> {
                        val t = p.nextText()
                        if (inEntry) title = t else if (feedTitle == null) feedTitle = t
                    }
                    "yt:videoId" -> if (inEntry) videoId = p.nextText()
                    "name" -> if (inAuthor) { val n = p.nextText(); if (inEntry) author = n else if (feedTitle == null) feedTitle = n; if (feedAuthor.isNullOrBlank()) feedAuthor = n }
                    "published" -> if (inEntry) published = parseTime(p.nextText())
                    "updated" -> if (inEntry) { val u = parseTime(p.nextText()); if (published == 0L) published = u }
                    "media:description", "description" -> if (inEntry) desc = p.nextText()
                    "media:thumbnail" -> if (inEntry && thumb.isEmpty()) thumb = p.getAttributeValue(null, "url") ?: ""
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "author" -> inAuthor = false
                    "entry" -> {
                        inEntry = false
                        if (videoId.isNotEmpty()) {
                            out += VideoEntity(
                                videoId = videoId, channelId = ch.channelId, subFeedId = ch.subFeedId,
                                title = title, channelTitle = author.ifEmpty { feedAuthor ?: feedTitle ?: ch.title },
                                thumbnailUrl = thumb.ifEmpty { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
                                publishedAt = published, description = desc
                            )
                        }
                    }
                }
            }
            ev = p.next()
        }
        out.sortByDescending { it.publishedAt }
        // Playlist/UULF feeds title themselves "Videos"; prefer the author name in that case.
        val resolved = if (feedTitle.isNullOrBlank() || feedTitle.equals("Videos", true)) feedAuthor?.takeIf { it.isNotBlank() } ?: feedTitle else feedTitle
        return ParsedFeed(resolved, out)
    }

    private companion object {
        const val STALE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
