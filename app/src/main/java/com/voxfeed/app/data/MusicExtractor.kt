package com.voxfeed.app.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo

/** URL normalization: music.youtube.com / youtu.be / m.youtube.com -> www.youtube.com canonical forms. */
object MusicUrl {
    enum class Kind { TRACK, PLAYLIST }
    data class Parsed(val kind: Kind, val url: String)

    fun parse(raw: String): Parsed? {
        var s = raw.trim()
        if (s.isEmpty() || s.contains(' ')) return null
        if (!s.contains("://")) s = "https://$s"
        val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return null
        val host = (uri.host ?: return null).lowercase().removePrefix("www.").removePrefix("m.")
        val segs = uri.pathSegments.orEmpty()
        val list = uri.getQueryParameter("list")?.takeIf { it.isNotBlank() }
        val video: String? = when (host) {
            "youtu.be" -> segs.firstOrNull()
            "youtube.com", "music.youtube.com" -> uri.getQueryParameter("v")
                ?: if (segs.size >= 2 && segs[0] in setOf("shorts", "embed", "live")) segs[1] else null
            else -> return null
        }?.takeIf { it.isNotBlank() }
        return when {
            // A concrete video id is a single-track override even when &list= is present.
            video != null -> Parsed(Kind.TRACK, "https://www.youtube.com/watch?v=$video")
            list != null -> Parsed(Kind.PLAYLIST, "https://www.youtube.com/playlist?list=$list")
            else -> null
        }
    }

    fun isPlaylist(raw: String) = parse(raw)?.kind == Kind.PLAYLIST
}

data class ScannedTrack(
    val title: String, val artist: String, val durationMs: Long, val url: String, val thumbnailUrl: String?
)

data class ScanResult(val title: String, val isPlaylist: Boolean, val tracks: List<ScannedTrack>)

object MusicExtractor {
    suspend fun scan(raw: String): ScanResult = withContext(Dispatchers.IO) {
        YouTubeStreamExtractor.ensureInit()
        val p = MusicUrl.parse(raw) ?: throw IllegalArgumentException("Not a YouTube or YouTube Music link")
        if (p.kind == MusicUrl.Kind.TRACK) {
            val info = StreamInfo.getInfo(p.url)
            ScanResult(
                info.name.orEmpty(), false,
                listOf(
                    ScannedTrack(
                        info.name.orEmpty(), info.uploaderName.orEmpty(), info.duration.coerceAtLeast(0) * 1000,
                        p.url, info.thumbnails.firstOrNull()?.url
                    )
                )
            )
        } else {
            val info = PlaylistInfo.getInfo(p.url)
            val items = ArrayList(info.relatedItems)
            var hasNext = info.hasNextPage()
            var page = info.nextPage
            var guard = 0
            while (hasNext && page != null && guard++ < 100) {
                val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, p.url, page)
                items += more.items
                hasNext = more.hasNextPage()
                page = more.nextPage
            }
            ScanResult(
                info.name.orEmpty().ifBlank { "Imported Playlist" }, true,
                items.map {
                    ScannedTrack(
                        it.name.orEmpty(), it.uploaderName.orEmpty(), it.duration.coerceAtLeast(0) * 1000,
                        MusicUrl.parse(it.url)?.url ?: it.url, it.thumbnails.firstOrNull()?.url
                    )
                }.distinctBy { it.url }
            )
        }
    }
}
