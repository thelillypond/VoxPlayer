package com.voxfeed.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException

enum class AudioQuality(val label: String) { STANDARD("STANDARD M4A"), BEST("BEST AUDIO") }

/**
 * Stashes tracks offline into Music/VoxFeed/<Playlist>/ via MediaStore (no storage permission),
 * tags M4A files with title/artist/album/cover, then flips MusicTrackEntity.localUri.
 * Runs in an app-lifetime scope, 2 downloads at a time.
 */
object MusicDownloader {
    private const val TAG = "MusicDownloader"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val active = HashSet<Long>()

    private val _progress = MutableStateFlow<Map<Long, Int>>(emptyMap())
    /** trackId -> percent (0..100) for queued/running downloads. */
    val progress = _progress.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    fun enqueue(ctx: Context, track: MusicTrackEntity, playlistName: String) {
        if (track.localUri != null) return
        synchronized(active) { if (!active.add(track.id)) return }
        val app = ctx.applicationContext
        _progress.update { it + (track.id to 0) }
        scope.launch {
            try {
                gate.withPermit { download(app, track, playlistName) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Cache failed for ${track.title}", e)
                _messages.tryEmit("CACHE FAULT [${track.title.take(24)}]: ${e.message ?: e.javaClass.simpleName}".take(140))
            } finally {
                synchronized(active) { active.remove(track.id) }
                _progress.update { it - track.id }
            }
        }
    }

    private suspend fun download(ctx: Context, t: MusicTrackEntity, playlist: String) {
        val audio = YouTubeStreamExtractor.resolveAudioOnly(t.sourceUrl, bestQuality = true) // always the highest averageBitrate stream
        val tmp = File(ctx.cacheDir, "stash_${t.id}.tmp")
        try {
            val req = okhttp3.Request.Builder().url(audio.audioUrl)
                .header("User-Agent", YouTubeStreamExtractor.USER_AGENT).build()
            YouTubeStreamExtractor.playbackClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("empty body")
                val total = body.contentLength()
                var done = 0L
                tmp.outputStream().use { out ->
                    val src = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 99)
                            _progress.update { it + (t.id to pct) }
                        }
                    }
                }
            }
            val cover = t.thumbnailUrl?.let { runCatching { fetchBytes(it) }.getOrNull() }
            val ext = if (audio.isM4a) "m4a" else "webm"
            val mime = if (audio.isM4a) "audio/mp4" else "audio/webm"
            val name = sanitize("${t.title} - ${t.artist}".trim(' ', '-')).take(100) + ".$ext"
            val album = sanitize(playlist)
            val uri = saveToMusic(ctx, name, mime, album, t, playlist) { out ->
                val tagged = audio.isM4a && runCatching {
                    Mp4Tagger.tag(tmp, out, Mp4Tagger.Meta(t.title, t.artist, playlist, cover))
                }.getOrDefault(false)
                if (!tagged) tmp.inputStream().use { it.copyTo(out) }
            }
            VoxDatabase.get(ctx).musicDao().setLocalUri(t.id, uri.toString())
        } finally {
            tmp.delete()
        }
    }

    private fun fetchBytes(url: String): ByteArray? {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", YouTubeStreamExtractor.USER_AGENT).build()
        return YouTubeStreamExtractor.httpClient.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.bytes() else null
        }
    }

    private fun saveToMusic(
        ctx: Context, fileName: String, mime: String, folder: String,
        t: MusicTrackEntity, album: String, write: (java.io.OutputStream) -> Unit
    ): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/VoxFeed/$folder")
                put(MediaStore.Audio.Media.TITLE, t.title)
                put(MediaStore.Audio.Media.ARTIST, t.artist)
                put(MediaStore.Audio.Media.ALBUM, album)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)?.use { write(it) } ?: throw IOException("cannot open output")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            return uri
        }
        // API 26-28: app-specific external Music dir (no permission needed).
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "VoxFeed/$folder").apply { mkdirs() }
        val f = File(dir, fileName)
        f.outputStream().use { write(it) }
        return Uri.fromFile(f)
    }

    fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').ifBlank { "VOX" }
}
