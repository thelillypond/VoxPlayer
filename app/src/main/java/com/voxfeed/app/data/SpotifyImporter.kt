package com.voxfeed.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit

/** One `Artist - Title` pair from a Spotify playlist or an imported file. */
data class ImportTrack(val title: String, val artist: String) {
    val query: String get() = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ")
}

data class ImportList(val name: String, val tracks: List<ImportTrack>)

/** Zero-login Spotify playlist import: public embed page, CSV/TXT parsing and YouTube matching. */
object SpotifyImporter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private val ID_RE = Regex("""open\.spotify\.com/(?:intl-[a-z-]+/)?(?:embed/)?playlist/([A-Za-z0-9]+)""")

    fun isSpotifyPlaylistUrl(raw: String) = ID_RE.containsMatchIn(raw.trim())

    fun playlistId(raw: String): String? = ID_RE.find(raw.trim())?.groupValues?.get(1)

    /** Fetches title + tracks from the public embed page (query strings like ?si= are ignored). */
    suspend fun fetchPlaylist(raw: String): ImportList = withContext(Dispatchers.IO) {
        val id = playlistId(raw) ?: throw IllegalArgumentException("Not a Spotify playlist link")
        val req = Request.Builder().url("https://open.spotify.com/embed/playlist/$id")
            .header("User-Agent", YouTubeStreamExtractor.USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9").build()
        val html = client.newCall(req).execute().use {
            if (!it.isSuccessful) throw IllegalStateException("Spotify returned HTTP ${it.code} (playlist may be private)")
            it.body?.string().orEmpty()
        }
        parseEmbedHtml(html)
    }

    internal fun parseEmbedHtml(html: String): ImportList {
        val m = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: throw IllegalStateException("Spotify page had no playlist data")
        val root = JSONObject(m.groupValues[1])
        val entity = root.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity")
            ?: throw IllegalStateException("Spotify playlist data unavailable")
        val name = entity.optString("name").ifBlank { entity.optString("title") }.ifBlank { "Spotify Import" }
        val list: JSONArray = entity.optJSONArray("trackList") ?: JSONArray()
        val out = ArrayList<ImportTrack>()
        for (i in 0 until list.length()) {
            val t = list.optJSONObject(i) ?: continue
            val title = t.optString("title").trim()
            val artist = t.optString("subtitle").replace('\u00a0', ' ').trim()
            if (title.isNotEmpty()) out += ImportTrack(title, artist)
        }
        if (out.isEmpty()) throw IllegalStateException("No tracks found (playlist may be private)")
        return ImportList(name, out.distinct())
    }

    // ---- CSV / TXT ----

    fun parseFile(fileName: String, text: String): ImportList {
        val base = fileName.substringBeforeLast('.').ifBlank { "Imported Playlist" }
        val clean = text.removePrefix("\uFEFF")
        val lines = clean.lines().filter { it.isNotBlank() }
        val header = lines.firstOrNull()?.let { splitCsv(it).map { h -> h.trim().lowercase() } }.orEmpty()
        val ti = header.indexOfFirst { it == "track name" || it == "track" || it == "title" || it == "song" || it == "name" || it == "song name" || it == "track title" }
        val ai = header.indexOfFirst { it.startsWith("artist") }
        val tracks = if (ti >= 0 && ai >= 0) {
            lines.drop(1).mapNotNull { l ->
                val c = splitCsv(l)
                val title = c.getOrNull(ti)?.trim().orEmpty()
                // Multi-artist cells use ';' (Exportify) or ',' inside quotes; keep the primary artist for search.
                val artist = c.getOrNull(ai)?.split(';')?.firstOrNull()?.trim().orEmpty()
                if (title.isEmpty()) null else ImportTrack(title, artist)
            }
        } else {
            lines.mapNotNull { l ->
                val s = l.trim()
                val sep = Regex("\\s[-\u2013\u2014]\\s").find(s)
                if (sep != null) ImportTrack(s.substring(sep.range.last + 1).trim(), s.substring(0, sep.range.first).trim())
                else if (s.isNotEmpty()) ImportTrack(s, "") else null
            }
        }
        return ImportList(base, tracks.distinct())
    }

    /** Minimal RFC-4180 line splitter (quotes, escaped quotes). */
    internal fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                q && ch == '"' && line.getOrNull(i + 1) == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                ch == ',' && !q -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    // ---- YouTube matching ----

    /** Resolves a track via the existing NewPipe YouTube pipeline; null when nothing is found. */
    suspend fun match(t: ImportTrack): ScannedTrack? = withContext(Dispatchers.IO) {
        YouTubeStreamExtractor.ensureInit()
        val yt = ServiceList.YouTube
        for (filter in listOf("music_songs", "videos")) {
            val hit = runCatching {
                val h = yt.searchQHFactory.fromQuery(t.query, listOf(filter), "")
                SearchInfo.getInfo(yt, h).relatedItems.filterIsInstance<StreamInfoItem>().firstOrNull()
            }.getOrNull()
            if (hit != null) {
                return@withContext ScannedTrack(
                    t.title.ifBlank { hit.name.orEmpty() }, t.artist.ifBlank { hit.uploaderName.orEmpty() },
                    hit.duration.coerceAtLeast(0) * 1000, MusicUrl.parse(hit.url)?.url ?: hit.url,
                    hit.thumbnails.firstOrNull()?.url
                )
            }
        }
        null
    }
}
