package com.voxfeed.app.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Stage 4B: offline archive via direct OkHttp streaming (no DownloadManager).
 * Each video gets `<movies>/VoxFeed/<videoId>/` holding `muxed.mp4`, or split `video.mp4` + `audio.m4a`.
 * Downloads run in an application-scoped coroutine so they survive leaving the player screen.
 * Room's `localVideoPath` stores the folder path; `isArchived` flips only after every file is renamed
 * from its `.part` temp file.
 */
class MediaArchiveManager private constructor(context: Context) {
    private val app = context.applicationContext
    private val dao = VoxDatabase.get(app).videoDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** videoId -> download percent 0..100 for every archive currently in flight. */
    val progress: StateFlow<Map<String, Int>> = _progress

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Human-readable archive failures, for the UI to surface. */
    val errors: SharedFlow<String> = _errors

    private fun setProgress(videoId: String, pct: Int) = _progress.update { it + (videoId to pct.coerceIn(0, 100)) }
    private fun clearProgress(videoId: String) = _progress.update { it - videoId }

    private fun root(): File? = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
    private fun videoDir(videoId: String): File? = root()?.let { File(it, "VoxFeed/$videoId") }

    /** Starts archiving in the background and returns immediately; observe [progress] / [errors]. */
    fun archive(videoId: String) {
        synchronized(jobs) {
            if (jobs[videoId]?.isActive == true) return
            setProgress(videoId, 0)
            jobs[videoId] = scope.launch { runArchive(videoId) }
        }
    }

    private suspend fun runArchive(videoId: String) {
        val dir = videoDir(videoId)
        try {
            dir ?: throw IOException("External media storage unavailable")
            // Fresh resolve right now so the googlevideo URLs are unexpired.
            val streams = YouTubeStreamExtractor.resolveStreams(videoId, maxHeight = 1080)
            dir.deleteRecursively()
            if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create archive folder")

            if (streams.audioUrl == null) {
                download(streams.videoUrl, File(dir, "muxed.mp4"), 0, 100, videoId)
            } else {
                download(streams.videoUrl, File(dir, "video.mp4"), 0, 90, videoId)
                download(streams.audioUrl, File(dir, "audio.m4a"), 90, 100, videoId)
            }
            if (localSources(dir.absolutePath) == null) throw IOException("Downloaded files missing")
            dao.setArchived(videoId, dir.absolutePath)
            clearProgress(videoId)
        } catch (e: CancellationException) {
            dir?.deleteRecursively()
            clearProgress(videoId)
            throw e
        } catch (e: Exception) {
            dir?.deleteRecursively()
            clearProgress(videoId)
            _errors.tryEmit("ARCHIVE FAILED: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            synchronized(jobs) { jobs.remove(videoId) }
        }
    }

    /**
     * Streams [url] to `<target>.part`, reporting overall percent in [from]..[to] at most every 250 ms,
     * then renames to [target]. Uses the extractor's shared OkHttpClient and browser-like headers.
     */
    private suspend fun download(url: String, target: File, from: Int, to: Int, videoId: String) =
        withContext(Dispatchers.IO) {
            val part = File(target.parentFile, target.name + ".part")
            fun rangeRequest(start: Long, end: Long) = Request.Builder().url(url)
                .header("User-Agent", YouTubeStreamExtractor.USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "CONSENT=YES+cb")
                .header("Range", "bytes=$start-$end")
                .build()

            var total = -1L // learned from the first response's Content-Range
            var start = 0L
            val buf = ByteArray(64 * 1024)
            part.outputStream().buffered().use { out ->
                // Sequential 2 MB Range requests: YouTube throttles long open-ended GETs.
                while (total < 0 || start < total) {
                    ensureActive()
                    val end = if (total < 0) start + CHUNK_BYTES - 1 else minOf(start + CHUNK_BYTES, total) - 1
                    YouTubeStreamExtractor.httpClient.newCall(rangeRequest(start, end)).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val body = response.body ?: throw IOException("Empty response body")
                        if (response.code == 200) {
                            // Server ignored Range and is sending the whole file: stream it in one go.
                            if (start != 0L) throw IOException("Server ignored Range request")
                            val len = body.contentLength()
                            var done = 0L
                            body.byteStream().use { input ->
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (len > 0) setProgress(videoId, from + ((to - from) * done / len).toInt())
                                }
                            }
                            total = done
                            start = done
                            return@use
                        }
                        if (total < 0) {
                            // Content-Range: bytes 0-2097151/12345678
                            total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                                ?: throw IOException("Missing Content-Range total")
                            if (total <= 0) throw IOException("Empty stream")
                        }
                        var got = 0L
                        body.byteStream().use { input ->
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                            }
                        }
                        if (got == 0L) throw IOException("Empty chunk at byte $start")
                        start += got
                        setProgress(videoId, from + ((to - from) * start / total).toInt())
                    }
                }
            }
            if (start != total) throw IOException("Incomplete download ($start/$total bytes)")
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Could not finalise ${target.name}")
            // Cap at 99% until Room is updated (the button flips to ARCHIVED then).
            setProgress(videoId, minOf(to, 99))
        }

    /** Cancels any in-flight download, deletes the whole per-video folder (or legacy file), resets Room. */
    suspend fun deleteArchive(videoId: String) = withContext(Dispatchers.IO) {
        val job = synchronized(jobs) { jobs.remove(videoId) }
        job?.cancelAndJoin()
        clearProgress(videoId)
        dao.get(videoId)?.localVideoPath?.let { runCatching { File(it).deleteRecursively() } }
        videoDir(videoId)?.let { runCatching { it.deleteRecursively() } }
        root()?.let { runCatching { File(it, "$videoId.mp4").delete() } } // Stage 4B v1 layout
        dao.clearArchive(videoId)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            com.voxfeed.app.ui.CogitatorBanner.show("DATA-SLATE PURGED // ARCHIVE EXPUNGED")
        }
    }

    companion object {
        private const val CHUNK_BYTES = 2_097_152L // 2 MB per Range request
        @Volatile private var instance: MediaArchiveManager? = null
        fun get(context: Context): MediaArchiveManager = instance ?: synchronized(this) {
            instance ?: MediaArchiveManager(context).also { instance = it }
        }

        /**
         * Playable local files for a stored `localVideoPath`, or null if nothing valid is on disk.
         * One file => play directly; two files => [video, audio] to be merged.
         */
        fun localSources(path: String?): List<File>? {
            if (path.isNullOrBlank()) return null
            val f = File(path)
            fun ok(x: File) = x.isFile && x.length() > 0
            return when {
                f.isFile -> listOf(f).takeIf { ok(f) }
                f.isDirectory -> {
                    val video = File(f, "video.mp4"); val audio = File(f, "audio.m4a"); val muxed = File(f, "muxed.mp4")
                    when {
                        ok(video) && ok(audio) -> listOf(video, audio)
                        ok(muxed) -> listOf(muxed)
                        else -> null
                    }
                }
                else -> null
            }
        }
    }
}
