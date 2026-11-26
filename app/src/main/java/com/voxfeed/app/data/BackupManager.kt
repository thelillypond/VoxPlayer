package com.voxfeed.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader

/**
 * Portable backup / restore (JSON v2): subscribed feeds (groups + channels), video playlists, Audio Deck
 * playlists with track metadata, and read/favorite flags. Does not touch the Room schema.
 * Restores merge into existing data and de-duplicate (channels by id, playlists by name,
 * tracks by source URL within a playlist, videos by id).
 */
class BackupManager(private val context: Context) {
    private val db = VoxDatabase.get(context)
    private val channelIdRegex = Regex("channel_id=(UC[a-zA-Z0-9_-]{22})")

    private data class ChannelRow(val id: String, val title: String, val group: String?, val excludeShorts: Boolean, val alerts: Boolean)

    suspend fun export(uri: Uri): String = withContext(Dispatchers.IO) {
        val subFeeds = db.subFeedDao().getAll()
        val groupName = subFeeds.associate { it.id to it.name }

        val feeds = JSONArray()
        subFeeds.forEach { feeds.put(JSONObject().put("name", it.name).put("sortOrder", it.sortOrder)) }

        val channels = JSONArray()
        db.channelDao().getAll().forEach {
            channels.put(
                JSONObject().put("channelId", it.channelId).put("title", it.title)
                    .put("rssUrl", rssUrl(it.channelId))
                    .put("group", groupName[it.subFeedId] ?: JSONObject.NULL)
                    .put("excludeShorts", it.excludeShorts).put("alertsEnabled", it.alertsEnabled)
            )
        }

        val flagged = JSONArray()
        db.videoDao().getFlagged().forEach {
            flagged.put(JSONObject().put("videoId", it.videoId).put("isRead", it.isRead).put("isFavorite", it.isFavorite))
        }

        val vpDao = db.videoPlaylistDao()
        val items = vpDao.getAllItems()
        val videoPlaylists = JSONArray()
        vpDao.getPlaylists().forEach { p ->
            val vids = JSONArray()
            vpDao.getVideos(p.id).forEach { v ->
                val addedAt = items.firstOrNull { it.playlistId == p.id && it.videoId == v.videoId }?.addedAt ?: 0L
                vids.put(
                    JSONObject().put("videoId", v.videoId).put("channelId", v.channelId).put("title", v.title)
                        .put("channelTitle", v.channelTitle).put("thumbnailUrl", v.thumbnailUrl)
                        .put("publishedAt", v.publishedAt).put("description", v.description).put("addedAt", addedAt)
                )
            }
            videoPlaylists.put(JSONObject().put("name", p.name).put("createdAt", p.createdAt).put("videos", vids))
        }

        val md = db.musicDao()
        val allTracks = md.getAllTracks()
        val musicPlaylists = JSONArray()
        md.getPlaylists().forEach { p ->
            val tr = JSONArray()
            allTracks.filter { it.playlistId == p.id }.sortedBy { it.position }.forEach { t ->
                tr.put(
                    JSONObject().put("title", t.title).put("artist", t.artist).put("durationMs", t.durationMs)
                        .put("sourceUrl", t.sourceUrl).put("thumbnailUrl", t.thumbnailUrl ?: JSONObject.NULL)
                        .put("isFavorite", t.isFavorite).put("genre", t.genre)
                )
            }
            musicPlaylists.put(JSONObject().put("name", p.name).put("createdAt", p.createdAt).put("tracks", tr))
        }

        val root = JSONObject().put("format", "voxplayer-backup").put("version", 2)
            .put("exportedAt", System.currentTimeMillis())
            .put("feeds", feeds).put("channels", channels).put("videos", flagged)
            .put("videoPlaylists", videoPlaylists).put("musicPlaylists", musicPlaylists)
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Cannot open destination")
        out.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
        "Backup saved: ${channels.length()} channels, ${videoPlaylists.length()} video playlists, " +
            "${musicPlaylists.length()} audio playlists (${allTracks.size} tracks)"
    }

    /** Imports a JSON backup (v2, v1 or legacy) or an OPML file (auto-detected). */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Cannot open source")
        val body = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val channels: List<ChannelRow>
        var flags: List<Triple<String, Boolean, Boolean>> = emptyList()
        var root: JSONObject? = null
        var feedNames: List<Pair<String, Int>> = emptyList()
        if (body.startsWith("{")) {
            val r = JSONObject(body).also { root = it }
            val fa = r.optJSONArray("feeds") ?: JSONArray()
            feedNames = (0 until fa.length()).mapNotNull {
                val o = fa.optJSONObject(it) ?: return@mapNotNull null
                o.optString("name").takeIf { n -> n.isNotBlank() }?.let { n -> n to o.optInt("sortOrder", it) }
            }
            val ca = r.optJSONArray("channels") ?: JSONArray()
            channels = (0 until ca.length()).mapNotNull {
                val o = ca.optJSONObject(it) ?: return@mapNotNull null
                val id = o.optString("channelId")
                if (id.isBlank()) null else ChannelRow(
                    id, o.optString("title", id),
                    if (o.isNull("group")) null else o.optString("group").takeIf { g -> g.isNotBlank() },
                    o.optBoolean("excludeShorts", true), o.optBoolean("alertsEnabled", true)
                )
            }
            val va = r.optJSONArray("videos") ?: JSONArray()
            flags = (0 until va.length()).mapNotNull {
                val o = va.optJSONObject(it) ?: return@mapNotNull null
                val id = o.optString("videoId")
                if (id.isBlank()) null else Triple(id, o.optBoolean("isRead"), o.optBoolean("isFavorite"))
            }
        } else if (body.startsWith("<")) {
            channels = parseOpml(body).map { (id, title, _) -> ChannelRow(id, title, null, true, true) }
        } else throw IOException("Unrecognised backup format")

        // Feeds (groups): create missing ones by name.
        val subDao = db.subFeedDao()
        val groups = HashMap<String, Long>()
        subDao.getAll().forEach { groups.putIfAbsent(it.name.lowercase(), it.id) }
        var nextSort = subDao.maxSortOrder() + 1
        suspend fun groupId(name: String, sort: Int?): Long = groups.getOrPut(name.lowercase()) {
            subDao.insert(SubFeedEntity(name = name, sortOrder = sort ?: nextSort++))
        }
        feedNames.forEach { (n, s) -> groupId(n, s.coerceAtLeast(nextSort)) }
        val defaultFeedId = subDao.getAll().firstOrNull()?.id ?: groupId("Imported", 0)

        var added = 0
        channels.forEach { c ->
            val gid = c.group?.let { groupId(it, null) } ?: defaultFeedId
            if (db.channelDao().insertIgnore(ChannelEntity(c.id, gid, c.title, c.excludeShorts, 0, c.alerts)) != -1L) added++
        }
        // Fetch videos for new channels (existing ones are refreshed too; flags are preserved).
        if (added > 0 || flags.isNotEmpty()) runCatching { YouTubeRssRepository(db, context).refreshAll() }
        var restored = 0
        flags.forEach { (id, r, f) -> restored += db.videoDao().setFlags(id, r, f) }

        var vpl = 0; var vItems = 0; var mpl = 0; var mTracks = 0
        root?.let { r ->
            // Video playlists
            val vpDao = db.videoPlaylistDao()
            val existing = vpDao.getPlaylists().associateBy { it.name.lowercase() }.toMutableMap()
            val pa = r.optJSONArray("videoPlaylists") ?: JSONArray()
            for (i in 0 until pa.length()) {
                val po = pa.optJSONObject(i) ?: continue
                val name = (po.optString("name").takeIf { it.isNotBlank() } ?: continue)
                val pl = existing[name.lowercase()] ?: VideoPlaylistEntity(name = name, createdAt = po.optLong("createdAt", System.currentTimeMillis()))
                    .let { it.copy(id = vpDao.insertPlaylist(it)) }.also { existing[name.lowercase()] = it; vpl++ }
                val vs = po.optJSONArray("videos") ?: continue
                for (j in 0 until vs.length()) {
                    val vo = vs.optJSONObject(j) ?: continue
                    val vid = (vo.optString("videoId").takeIf { it.isNotBlank() } ?: continue)
                    val chId = vo.optString("channelId")
                    if (db.channelDao().get(chId) == null) {
                        if (chId == AdHocVideo.CHANNEL_ID) db.channelDao().insertIgnore(ChannelEntity(chId, defaultFeedId, "Videos"))
                        else continue
                    }
                    val subId = db.channelDao().get(chId)?.subFeedId ?: defaultFeedId
                    db.videoDao().insertAll(
                        listOf(
                            VideoEntity(
                                videoId = vid, channelId = chId, subFeedId = subId, title = vo.optString("title"),
                                channelTitle = vo.optString("channelTitle"), thumbnailUrl = vo.optString("thumbnailUrl"),
                                publishedAt = vo.optLong("publishedAt"), description = vo.optString("description")
                            )
                        )
                    )
                    if (vpDao.addItem(VideoPlaylistItemEntity(pl.id, vid, vo.optLong("addedAt", System.currentTimeMillis()))) != -1L) vItems++
                }
            }

            // Audio Deck playlists + track metadata
            val md = db.musicDao()
            val ma = r.optJSONArray("musicPlaylists") ?: JSONArray()
            for (i in 0 until ma.length()) {
                val po = ma.optJSONObject(i) ?: continue
                val name = (po.optString("name").takeIf { it.isNotBlank() } ?: continue)
                val had = md.findPlaylistByName(name) != null
                val pl = md.getOrCreatePlaylist(name)
                if (!had) mpl++
                val ta = po.optJSONArray("tracks") ?: continue
                val have = md.getTracks(pl.id).map { it.sourceUrl }.toHashSet()
                val fresh = ArrayList<MusicTrackEntity>()
                for (j in 0 until ta.length()) {
                    val to = ta.optJSONObject(j) ?: continue
                    val src = (to.optString("sourceUrl").takeIf { it.isNotBlank() } ?: continue)
                    if (!have.add(src)) continue
                    fresh += MusicTrackEntity(
                        playlistId = pl.id, title = to.optString("title"), artist = to.optString("artist"),
                        durationMs = to.optLong("durationMs"), sourceUrl = src,
                        thumbnailUrl = if (to.isNull("thumbnailUrl")) null else to.optString("thumbnailUrl"),
                        localUri = null, position = 0, isFavorite = to.optBoolean("isFavorite"),
                        genre = to.optString("genre", MusicDao.GENRE_NONE).ifBlank { MusicDao.GENRE_NONE }
                    )
                }
                if (fresh.isNotEmpty()) { md.appendTracks(pl.id, fresh); mTracks += fresh.size }
            }
        }
        "Backup restored: $added new / ${channels.size - added} existing channels, $restored flags, " +
            "$vpl video playlists (+$vItems items), $mpl audio playlists (+$mTracks tracks)"
    }

    private fun rssUrl(id: String) = "https://www.youtube.com/feeds/videos.xml?channel_id=$id"

    private fun parseOpml(xml: String): List<Triple<String, String, String>> {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(StringReader(xml))
        val out = LinkedHashMap<String, Triple<String, String, String>>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name.equals("outline", true)) {
                val url = p.getAttributeValue(null, "xmlUrl")
                val id = url?.let { channelIdRegex.find(it)?.groupValues?.get(1) }
                if (id != null) {
                    val title = p.getAttributeValue(null, "title") ?: p.getAttributeValue(null, "text") ?: id
                    out.putIfAbsent(id, Triple(id, title, url))
                }
            }
            ev = p.next()
        }
        return out.values.toList()
    }
}
