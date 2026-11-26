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

/** Portable backup / restore of subscriptions and read/favorite flags. Does not touch the Room schema. */
class BackupManager(private val context: Context) {
    private val db = VoxDatabase.get(context)
    private val channelIdRegex = Regex("channel_id=(UC[a-zA-Z0-9_-]{22})")

    suspend fun export(uri: Uri): String = withContext(Dispatchers.IO) {
        val channels = JSONArray()
        db.channelDao().getAll().forEach {
            channels.put(
                JSONObject().put("channelId", it.channelId).put("title", it.title)
                    .put("rssUrl", rssUrl(it.channelId))
            )
        }
        val videos = JSONArray()
        db.videoDao().getFlagged().forEach {
            videos.put(
                JSONObject().put("videoId", it.videoId).put("isRead", it.isRead).put("isFavorite", it.isFavorite)
            )
        }
        val root = JSONObject().put("format", "voxplayer-backup").put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("channels", channels).put("videos", videos)
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Cannot open destination")
        out.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
        "Backup saved: ${channels.length()} channels, ${videos.length()} flagged videos"
    }

    /** Imports a JSON backup (current or legacy format) or an OPML file (auto-detected). */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Cannot open source")
        val body = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val channels: List<Triple<String, String, String>>
        var flags: List<Triple<String, Boolean, Boolean>> = emptyList()
        if (body.startsWith("{")) {
            val root = JSONObject(body)
            val ca = root.optJSONArray("channels") ?: JSONArray()
            channels = (0 until ca.length()).mapNotNull {
                val o = ca.getJSONObject(it)
                val id = o.optString("channelId")
                if (id.isBlank()) null else Triple(id, o.optString("title", id), o.optString("rssUrl"))
            }
            val va = root.optJSONArray("videos") ?: JSONArray()
            flags = (0 until va.length()).mapNotNull {
                val o = va.getJSONObject(it)
                val id = o.optString("videoId")
                if (id.isBlank()) null else Triple(id, o.optBoolean("isRead"), o.optBoolean("isFavorite"))
            }
        } else if (body.startsWith("<")) {
            channels = parseOpml(body)
        } else throw IOException("Unrecognised backup format")

        val subFeedId = db.subFeedDao().getAll().firstOrNull()?.id
            ?: db.subFeedDao().insert(SubFeedEntity(name = "Imported", sortOrder = 0))
        var added = 0
        channels.forEach { (id, title, _) ->
            if (db.channelDao().insertIgnore(ChannelEntity(id, subFeedId, title)) != -1L) added++
        }
        // Fetch videos for new channels (existing ones are refreshed too; flags are preserved).
        if (added > 0 || flags.isNotEmpty()) YouTubeRssRepository(db, context).refreshAll()
        var restored = 0
        flags.forEach { (id, r, f) -> restored += db.videoDao().setFlags(id, r, f) }
        "Backup restored: $added new / ${channels.size - added} existing channels, $restored flags restored"
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
