package com.voxfeed.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo

/** Metadata of a single pasted video, fetched without subscribing to its channel. */
data class AdHocMeta(
    val videoId: String,
    val title: String,
    val channelName: String,
    /** UC... id, or null when YouTube did not expose it. */
    val channelId: String?,
    /** @handle when the uploader URL carries one. */
    val channelHandle: String?,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val chapters: List<VideoChapter>,
    val description: String
)

object AdHocVideo {
    /** Reserved channel row that owns unsubscribed videos (hidden from channel lists and refreshes by the DAO filters). */
    const val CHANNEL_ID = "ADHOC"

    private val ucRegex = Regex("UC[a-zA-Z0-9_-]{22}")
    private val handleRegex = Regex("@([A-Za-z0-9._-]+)")

    /** Video id for watch / youtu.be / shorts / embed / live links; null for channel or playlist-only input. */
    fun videoIdOf(raw: String): String? {
        val p = MusicUrl.parse(raw) ?: return null
        if (p.kind != MusicUrl.Kind.TRACK) return null
        return p.url.substringAfter("v=").takeIf { it.isNotBlank() }
    }

    suspend fun fetch(videoId: String): AdHocMeta = withContext(Dispatchers.IO) {
        YouTubeStreamExtractor.ensureInit()
        val url = "https://www.youtube.com/watch?v=$videoId"
        val info = try {
            StreamInfo.getInfo(url)
        } catch (e: Exception) {
            if (e.message?.contains("page needs to be reloaded", ignoreCase = true) == true) {
                delay(500); StreamInfo.getInfo(url)
            } else throw e
        }
        val uploaderUrl = info.uploaderUrl.orEmpty()
        val chapters = runCatching {
            info.streamSegments.mapNotNull { s ->
                val t = s.title?.trim().orEmpty()
                if (t.isEmpty() || s.startTimeSeconds < 0) null else VideoChapter(t, s.startTimeSeconds)
            }.sortedBy { it.startSeconds }
        }.getOrDefault(emptyList())
        AdHocMeta(
            videoId = videoId,
            title = info.name.orEmpty(),
            channelName = info.uploaderName.orEmpty(),
            channelId = ucRegex.find(uploaderUrl)?.value,
            channelHandle = handleRegex.find(uploaderUrl)?.value,
            thumbnailUrl = info.thumbnails.firstOrNull()?.url ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            durationSeconds = info.duration.coerceAtLeast(0),
            chapters = chapters,
            description = runCatching { info.description?.content.orEmpty() }.getOrDefault("")
        )
    }
}
