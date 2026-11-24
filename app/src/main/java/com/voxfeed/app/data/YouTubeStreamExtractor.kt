package com.voxfeed.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Resolved direct stream URLs. [videoUrl] may be a muxed stream (then [audioUrl] is null). */
data class ResolvedStreams(
    val videoUrl: String, val audioUrl: String?, val heightPx: Int,
    val subtitleUrl: String? = null,
    /** Media3 MIME type of [subtitleUrl] (`text/vtt` or `application/ttml+xml`). */
    val subtitleMimeType: String? = null,
    val subtitleLanguage: String? = null,
    val chapters: List<VideoChapter> = emptyList()
)

/** Audio-only stream for the music console. */
data class ResolvedAudio(
    val videoId: String, val audioUrl: String, val title: String, val uploader: String,
    val thumbnailUrl: String?, val durationSeconds: Long, val isM4a: Boolean = true
)

/** One caption cue, drawn by Compose (never handed to ExoPlayer). */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** One entry of the video's chapter list. */
data class VideoChapter(val title: String, val startSeconds: Int)

object YouTubeStreamExtractor {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Client for ExoPlayer's OkHttpDataSource: open-ended googlevideo.com requests are served as
     * sequential bounded 2 MB Range requests (see [GoogleVideoChunkInterceptor]) to dodge CDN throttling.
     */
    val playbackClient: OkHttpClient by lazy {
        httpClient.newBuilder().addInterceptor(GoogleVideoChunkInterceptor(httpClient)).build()
    }

    private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val builder = okhttp3.Request.Builder().url(request.url())
                .method(
                    request.httpMethod(),
                    request.dataToSend()?.toRequestBody()
                        ?: if (request.httpMethod() == "POST") ByteArray(0).toRequestBody() else null
                )
                .header("User-Agent", USER_AGENT)
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            val names = request.headers().keys.map { it.lowercase() }
            if ("cookie" !in names) builder.header("Cookie", "CONSENT=YES+cb")
            if ("accept-language" !in names) builder.header("Accept-Language", "en-US,en;q=0.9")
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
                return Response(
                    resp.code, resp.message, resp.headers.toMultimap(),
                    resp.body?.string(), resp.request.url.toString()
                )
            }
        }
    }

    private val initLock = Any()
    @Volatile private var initialised = false

    internal fun ensureInit() {
        if (initialised) return
        synchronized(initLock) {
            if (!initialised) {
                NewPipe.init(OkHttpDownloader(httpClient), Localization.DEFAULT, ContentCountry.DEFAULT)
                initialised = true
            }
        }
    }

    /** [maxHeight]: 720 for live playback (fast start/seek), 1080 for offline archiving. */
    suspend fun resolveStreams(videoId: String, maxHeight: Int = 720): ResolvedStreams =
        withContext(Dispatchers.IO) {
            ensureInit()
            val url = "https://www.youtube.com/watch?v=$videoId"
            val info = try {
                StreamInfo.getInfo(url)
            } catch (e: Exception) {
                if (e.message?.contains("page needs to be reloaded", ignoreCase = true) == true) {
                    delay(500)
                    StreamInfo.getInfo(url)
                } else throw e
            }

            fun isSupported(f: MediaFormat?) =
                f == MediaFormat.MPEG_4 || f == MediaFormat.M4A || f == MediaFormat.WEBM

            fun isM4a(f: MediaFormat?) = f == MediaFormat.M4A || f == MediaFormat.MPEG_4

            val usable = info.audioStreams.filter { it.isUrl && it.content.isNotBlank() && isSupported(it.format) }
            // 1) original / untyped tracks only (drops dubbed + descriptive), falling back to all.
            val originals = usable.filter {
                it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
            }.ifEmpty { usable }
            // 2) English or unlabelled locale, falling back to the previous pool.
            val english = originals.filter {
                val loc = it.audioLocale
                loc == null || loc.language.equals("en", ignoreCase = true)
            }.ifEmpty { originals }
            // 3) highest bitrate; M4A wins when within 10% of the best.
            val bestRate = english.maxOfOrNull { it.averageBitrate } ?: 0
            val audio = english.sortedByDescending { it.averageBitrate }
                .let { sorted -> sorted.firstOrNull { isM4a(it.format) && it.averageBitrate >= bestRate * 0.9 } ?: sorted.firstOrNull() }

            // Prefer MP4 video so it pairs with M4A audio without timestamp-alignment stalls.
            val videoPool = info.videoOnlyStreams
                .filter { it.isUrl && it.content.isNotBlank() && isSupported(it.format) && it.height <= maxHeight }
            val videoOnly = videoPool.filter { it.format == MediaFormat.MPEG_4 }.ifEmpty { videoPool }
                .maxWithOrNull(compareBy({ it.height }, { it.bitrate }))

            val sub = pickSubtitle(info)
            val chapters = runCatching {
                info.streamSegments.mapNotNull { s ->
                    val t = s.title?.trim().orEmpty()
                    if (t.isEmpty() || s.startTimeSeconds < 0) null else VideoChapter(t, s.startTimeSeconds)
                }.sortedBy { it.startSeconds }
            }.getOrDefault(emptyList())
            fun withExtras(r: ResolvedStreams) = r.copy(
                subtitleUrl = sub?.content,
                subtitleMimeType = sub?.let { if (it.format == MediaFormat.TTML) "application/ttml+xml" else "text/vtt" },
                subtitleLanguage = sub?.languageTag,
                chapters = chapters
            )

            if (videoOnly != null && audio != null) {
                return@withContext withExtras(ResolvedStreams(videoOnly.content, audio.content, videoOnly.height))
            }

            // Fallback: muxed (video+audio) progressive streams.
            val muxed = info.videoStreams
                .filter { it.isUrl && it.content.isNotBlank() && it.height <= maxHeight }
                .maxByOrNull { it.height }
                ?: info.videoStreams.firstOrNull { it.isUrl && it.content.isNotBlank() }
            if (muxed != null) return@withContext withExtras(ResolvedStreams(muxed.content, null, muxed.height))

            throw IllegalStateException("No playable streams found")
        }

    /** Audio-only resolution of any YouTube URL (or bare 11-char id) for the Mek-Amp music console. */
    suspend fun resolveAudioOnly(urlOrId: String, bestQuality: Boolean = false): ResolvedAudio = withContext(Dispatchers.IO) {
        ensureInit()
        val input = urlOrId.trim()
        val url = MusicUrl.parse(input)?.url ?: if (input.startsWith("http", ignoreCase = true)) input else "https://www.youtube.com/watch?v=$input"
        val info = try {
            StreamInfo.getInfo(url)
        } catch (e: Exception) {
            if (e.message?.contains("page needs to be reloaded", ignoreCase = true) == true) {
                delay(500); StreamInfo.getInfo(url)
            } else throw e
        }
        fun isM4a(f: MediaFormat?) = f == MediaFormat.M4A || f == MediaFormat.MPEG_4
        val usable = info.audioStreams.filter {
            it.isUrl && it.content.isNotBlank() &&
                (isM4a(it.format) || it.format == MediaFormat.WEBM)
        }
        val originals = usable.filter {
            it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
        }.ifEmpty { usable }
        val sorted = originals.sortedByDescending { it.averageBitrate }
        val best = sorted.firstOrNull() ?: throw IllegalStateException("No audio stream found")
        val audio = if (bestQuality) best
            else sorted.firstOrNull { isM4a(it.format) } ?: best // STANDARD: native-taggable M4A, WebM/Opus fallback
        ResolvedAudio(
            videoId = info.id,
            audioUrl = audio.content,
            title = info.name.orEmpty(),
            uploader = info.uploaderName.orEmpty(),
            thumbnailUrl = info.thumbnails.firstOrNull()?.url,
            durationSeconds = info.duration,
            isM4a = isM4a(audio.format)
        )
    }

    /** Downloads an English .vtt / .ttml track with OkHttp and parses it into cues. */
    suspend fun fetchSubtitleCues(url: String, mimeType: String?): List<SubtitleCue> = withContext(Dispatchers.IO) {
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        val body = httpClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} fetching subtitles")
            r.body?.string().orEmpty()
        }
        val ttml = mimeType?.contains("ttml") == true || (body.contains("<tt") && !body.trimStart().startsWith("WEBVTT"))
        (if (ttml) parseTtml(body) else parseVtt(body)).sortedBy { it.startMs }
    }

    /** Upper bound on the raw fetched transcript; mode-specific condensing happens in CogitatorSummaryManager. */
    const val TRANSCRIPT_CAP = 120_000
    private const val STAMP_EVERY_MS = 20_000L

    /** "[MM:SS]" or "[H:MM:SS]" (hours only when >= 1h). */
    fun formatStamp(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
        return if (h > 0) "[%d:%02d:%02d]".format(h, m, sec) else "[%02d:%02d]".format(m, sec)
    }

    /**
     * Spoken transcript from the English subtitle track with a "[MM:SS]" marker roughly every 20s
     * (tags and HTML entities stripped, repeated auto-caption lines collapsed), capped at [TRANSCRIPT_CAP].
     * Falls back to title + description (no timestamps) when no captions exist or they cannot be fetched.
     */
    suspend fun fetchTranscriptText(videoId: String, title: String, description: String): String =
        withContext(Dispatchers.IO) {
            val spoken = runCatching {
                ensureInit()
                val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
                val sub = pickSubtitle(info) ?: return@runCatching ""
                val mime = if (sub.format == MediaFormat.TTML) "application/ttml+xml" else "text/vtt"
                var last = ""
                var lastStamp = Long.MIN_VALUE
                val sb = StringBuilder()
                for (cue in fetchSubtitleCues(sub.content, mime)) {
                    for (line in cue.text.lines()) {
                        val t = line.trim()
                        if (t.isEmpty() || t == last) continue
                        last = t
                        if (lastStamp == Long.MIN_VALUE || cue.startMs - lastStamp >= STAMP_EVERY_MS) {
                            if (sb.isNotEmpty()) sb.append('\n')
                            sb.append(formatStamp(cue.startMs)).append(' ')
                            lastStamp = cue.startMs
                        }
                        sb.append(t).append(' ')
                        if (sb.length >= TRANSCRIPT_CAP) break
                    }
                    if (sb.length >= TRANSCRIPT_CAP) break
                }
                sb.toString().trim()
            }.getOrDefault("")
            val text = spoken.ifBlank { "TITLE: $title\n\nDESCRIPTION: $description" }
            text.take(TRANSCRIPT_CAP)
        }
    private fun cleanCueText(raw: String): String = raw
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    /** "01:02:03.456", "02:03.456" or "02:03,456" -> ms. */
    private fun parseClock(s: String): Long? {
        val parts = s.trim().replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        return runCatching {
            val sec = parts.last().toDouble()
            val min = parts[parts.size - 2].toLong()
            val hr = if (parts.size == 3) parts[0].toLong() else 0L
            ((hr * 3600 + min * 60) * 1000 + Math.round(sec * 1000))
        }.getOrNull()
    }

    private fun parseVtt(src: String): List<SubtitleCue> {
        val out = mutableListOf<SubtitleCue>()
        val arrow = Regex("""^\s*([\d:.,]+)\s*-->\s*([\d:.,]+)""")
        val lines = src.replace("\r\n", "\n").lines()
        var i = 0
        while (i < lines.size) {
            val m = arrow.find(lines[i])
            if (m == null) { i++; continue }
            val start = parseClock(m.groupValues[1]); val end = parseClock(m.groupValues[2])
            i++
            val text = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) { text.append(lines[i]).append('\n'); i++ }
            val clean = cleanCueText(text.toString())
            if (start != null && end != null && clean.isNotEmpty()) out += SubtitleCue(start, end, clean)
        }
        return out
    }

    private fun parseTtml(src: String): List<SubtitleCue> {
        fun time(s: String): Long? {
            val v = s.trim()
            return when {
                v.endsWith("ms") -> v.removeSuffix("ms").toDoubleOrNull()?.let { Math.round(it) }
                v.endsWith("s") -> v.removeSuffix("s").toDoubleOrNull()?.let { Math.round(it * 1000) }
                else -> parseClock(v)
            }
        }
        val p = Regex("""<p\b([^>]*)>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL))
        val attr = { attrs: String, name: String -> Regex("""\b$name="([^"]*)"""").find(attrs)?.groupValues?.get(1) }
        return p.findAll(src).mapNotNull { m ->
            val start = attr(m.groupValues[1], "begin")?.let(::time) ?: return@mapNotNull null
            val end = attr(m.groupValues[1], "end")?.let(::time)
                ?: attr(m.groupValues[1], "dur")?.let(::time)?.let { start + it } ?: return@mapNotNull null
            val text = cleanCueText(m.groupValues[2].replace(Regex("<br\\s*/?>"), "\n"))
            if (text.isEmpty()) null else SubtitleCue(start, end, text)
        }.toList()
    }

    /** Best English .vtt / .ttml track: en > en-US > en-GB > other en-*; human-made beats auto-generated; VTT beats TTML. */
    private fun pickSubtitle(info: StreamInfo): SubtitlesStream? = runCatching {
        val usable = info.subtitles.filter {
            it.isUrl && it.content.isNotBlank() && (it.format == MediaFormat.VTT || it.format == MediaFormat.TTML)
        }
        fun rank(tag: String?): Int = when (tag?.lowercase()?.replace('_', '-')) {
            "en" -> 0
            "en-us" -> 1
            "en-gb" -> 2
            else -> if (tag?.lowercase()?.startsWith("en") == true) 3 else 99
        }
        usable.filter { rank(it.languageTag) < 99 }
            .minWithOrNull(
                compareBy<SubtitlesStream>({ it.isAutoGenerated }, { rank(it.languageTag) }, { if (it.format == MediaFormat.VTT) 0 else 1 })
            )
    }.getOrNull()
}

/**
 * Rewrites unbounded googlevideo.com GETs (no Range, or `bytes=N-`) into sequential bounded 2 MB
 * `Range` requests, since the CDN throttles long open-ended connections.
 *
 * A bare bounded response would make ExoPlayer think the file ends after 2 MB, so the response body
 * returned here reports the full remaining length and transparently pulls the following 2 MB chunks
 * (via [chunkClient], which has no interceptor) as ExoPlayer reads. Already-bounded requests pass through.
 */
class GoogleVideoChunkInterceptor(private val chunkClient: OkHttpClient) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        if (req.method != "GET" || !req.url.host.endsWith("googlevideo.com")) return chain.proceed(req)

        val range = req.header("Range")
        val start: Long = when {
            range == null -> 0L
            else -> {
                val m = OPEN_RANGE.matchEntire(range.trim())
                m?.groupValues?.get(1)?.toLongOrNull() ?: return chain.proceed(req) // bounded / unusual: untouched
            }
        }
        val firstEnd = start + CHUNK - 1
        val first = chain.proceed(req.newBuilder().header("Range", "bytes=$start-$firstEnd").build())
        if (first.code != 206) return first
        val total = first.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
        val body = first.body
        if (total == null || body == null || total <= firstEnd + 1) return first // single chunk covers it

        val remaining = total - start
        val chained = ChunkedBody(first, body.contentType(), remaining, firstEnd + 1, total, req, chunkClient)
        return first.newBuilder()
            .header("Content-Range", "bytes $start-${total - 1}/$total")
            .header("Content-Length", remaining.toString())
            .body(chained)
            .build()
    }

    private class ChunkedBody(
        first: okhttp3.Response, private val type: okhttp3.MediaType?, private val length: Long,
        nextStart: Long, total: Long, template: okhttp3.Request, client: OkHttpClient
    ) : ResponseBody() {
        private val buffered: BufferedSource = ChunkSource(first, nextStart, total, template, client).buffer()
        override fun contentType() = type
        override fun contentLength() = length
        override fun source(): BufferedSource = buffered
    }

    private class ChunkSource(
        first: okhttp3.Response, private var next: Long, private val total: Long,
        private val template: okhttp3.Request, private val client: OkHttpClient
    ) : Source {
        private var current: okhttp3.Response? = first
        private var currentSrc: BufferedSource? = first.body?.source()
        private var closed = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            while (true) {
                check(!closed) { "closed" }
                currentSrc?.let { s ->
                    val n = s.read(sink, byteCount)
                    if (n != -1L) return n
                    current?.close(); current = null; currentSrc = null
                }
                if (next >= total) return -1L
                val end = minOf(next + CHUNK, total) - 1
                val resp = client.newCall(template.newBuilder().header("Range", "bytes=$next-$end").build()).execute()
                if (resp.code != 206 || resp.body == null) {
                    val code = resp.code
                    resp.close()
                    throw IOException("HTTP $code on chunk at byte $next")
                }
                current = resp
                currentSrc = resp.body!!.source()
                next = end + 1
            }
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed = true
            current?.close(); current = null; currentSrc = null
        }
    }

    private companion object {
        const val CHUNK = 2_097_152L
        val OPEN_RANGE = Regex("bytes=(\\d+)-")
    }
}
