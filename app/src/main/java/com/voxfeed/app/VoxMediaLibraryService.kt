package com.voxfeed.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.voxfeed.app.data.YouTubeStreamExtractor

/**
 * Owns the single ExoPlayer + MediaSession so playback survives the Activity (lock screen,
 * Home, screen off). The system renders lock-screen / shade / Bluetooth controls from the session.
 *
 * Controllers send a MediaItem whose requestMetadata extras carry [EXTRA_URIS] (video URI first,
 * optional audio URI second -> merged), [EXTRA_VIDEO_ID], [EXTRA_DESCRIPTION].
 */
class VoxMediaLibraryService : MediaLibraryService() {
    private var mediaSession: MediaLibrarySession? = null
    private var libraryCallback: VoxLibraryCallback? = null

    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            // Pre-create media3's default notification channel so the launcher icon never gets a badge dot.
            val nm = getSystemService(android.app.NotificationManager::class.java)
            val channel = android.app.NotificationChannel("default_channel_id", "Playback", android.app.NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
        val okHttpFactory = OkHttpDataSource.Factory(YouTubeStreamExtractor.playbackClient)
            .setUserAgent(YouTubeStreamExtractor.USER_AGENT)
        // Aggressive read-ahead + 30s back-buffer so [ -15S ] skips are served from memory.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 90_000, 1_500, 3_000)
            .setBackBuffer(30_000, true)
            .build()
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false) // keep 16-bit PCM so the spectrum tap can read it
                    .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(AudioSpectrum.sink)))
                    .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(VoxMediaSourceFactory(this, okHttpFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true // handleAudioFocus: calls/alerts pause or duck us
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val libraryCallback = VoxLibraryCallback(this).also { this.libraryCallback = it }
        val session = MediaLibrarySession.Builder(this, player, libraryCallback)
            .build()
        mediaSession = session

        // Tapping the lock-screen / shade notification returns to the player for the current video.
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) AudioSpectrum.stopDecay() else AudioSpectrum.startDecay()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                handlePlayerError(player, error)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                retriedMediaId = null
                val extras = mediaItem?.requestMetadata?.extras ?: return
                val intent = if (extras.getBoolean(EXTRA_MUSIC, false)) {
                    // Music track: notification returns to the app (MainActivity), not the video player.
                    Intent(this@VoxMediaLibraryService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                } else Intent(this@VoxMediaLibraryService, VideoPlayerActivity::class.java)
                    .putExtra(VideoPlayerActivity.VIDEO_ID, extras.getString(EXTRA_VIDEO_ID))
                    .putExtra(VideoPlayerActivity.VIDEO_TITLE, mediaItem.mediaMetadata.title?.toString().orEmpty())
                    .putExtra(VideoPlayerActivity.VIDEO_DESCRIPTION, extras.getString(EXTRA_DESCRIPTION).orEmpty())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                session.setSessionActivity(
                    PendingIntent.getActivity(
                        this@VoxMediaLibraryService, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
        })
    }

    /** Media id of the item that already got one automatic stream-URL refresh (so we never loop). */
    private var retriedMediaId: String? = null

    private fun isExpiredStreamError(error: androidx.media3.common.PlaybackException): Boolean {
        var t: Throwable? = error
        while (t != null) {
            if (t is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && (t.responseCode == 403 || t.responseCode == 410)) return true
            if (t.javaClass.simpleName == "SourceException") return true
            t = t.cause
        }
        return error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
    }

    /**
     * Uncached streamed track failed (typically an expired googlevideo URL): drop the cached URL so the resolver
     * re-extracts a fresh one from the stored sourceUrl, then retry once from the same position. Otherwise skip.
     */
    private fun handlePlayerError(player: androidx.media3.common.Player, error: androidx.media3.common.PlaybackException) {
        val item = player.currentMediaItem
        val extras = item?.requestMetadata?.extras
        val mediaId = item?.mediaId
        val streamed = extras?.getBoolean(EXTRA_MUSIC, false) == true && !extras.getBoolean(EXTRA_CACHED, false)
        if (streamed && mediaId != null && retriedMediaId != mediaId && isExpiredStreamError(error)) {
            retriedMediaId = mediaId
            val pos = player.currentPosition.coerceAtLeast(0L)
            YouTubeResolver.invalidateAll()
            player.seekTo(player.currentMediaItemIndex, pos)
            player.prepare()
            player.play()
            return
        }
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    /**
     * Swiped away from recents: pause, stop and clear the queue, then stop the service. onDestroy() releases
     * the session and nulls the reference, so the next MediaController connection starts a fresh service
     * (onCreate) instead of binding to a dead session.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching {
            mediaSession?.player?.run {
                pause()
                stop()
                clearMediaItems()
            }
        }
        pauseAllPlayersAndStopSelf()
    }
    override fun onDestroy() {
        val session = mediaSession
        mediaSession = null // drop the reference first so no new controller can bind to a dying session
        session?.run {
            runCatching { player.release() }
            runCatching { release() }
        }
        libraryCallback?.release()
        super.onDestroy()
    }

    /**
     * Lazily swaps a YouTube watch URL (streamed music tracks) for a direct audio stream URL on ExoPlayer's
     * loader thread, so nothing blocks the main thread. Local files and googlevideo URLs pass straight through.
     */
    private object YouTubeResolver : androidx.media3.datasource.ResolvingDataSource.Resolver {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
        private const val TTL_MS = 90L * 60 * 1000

        fun invalidateAll() = cache.clear()

        override fun resolveDataSpec(dataSpec: androidx.media3.datasource.DataSpec): androidx.media3.datasource.DataSpec {
            val host = dataSpec.uri.host?.lowercase() ?: return dataSpec
            if (host != "youtu.be" && !host.endsWith("youtube.com")) return dataSpec
            val key = dataSpec.uri.toString()
            val now = System.currentTimeMillis()
            val url = cache[key]?.takeIf { now - it.second < TTL_MS }?.first ?: try {
                kotlinx.coroutines.runBlocking { YouTubeStreamExtractor.resolveAudioOnly(key, bestQuality = true).audioUrl }
            } catch (e: Exception) {
                throw java.io.IOException("Audio stream resolution failed: ${e.message}", e)
            }.also { cache[key] = it to now }
            return dataSpec.withUri(android.net.Uri.parse(url))
        }
    }

    /** Progressive sources; two URIs (separate video + audio streams) are merged. Handles http(s) and file. */
    private class VoxMediaSourceFactory(context: Context, okHttp: OkHttpDataSource.Factory) : MediaSource.Factory {
        private val progressive = ProgressiveMediaSource.Factory(
            androidx.media3.datasource.ResolvingDataSource.Factory(DefaultDataSource.Factory(context, okHttp), YouTubeResolver)
        )

        override fun setDrmSessionManagerProvider(p: DrmSessionManagerProvider): MediaSource.Factory =
            apply { progressive.setDrmSessionManagerProvider(p) }

        override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy): MediaSource.Factory =
            apply { progressive.setLoadErrorHandlingPolicy(p) }

        override fun getSupportedTypes(): IntArray = progressive.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val uris = mediaItem.requestMetadata.extras?.getStringArray(EXTRA_URIS)
            if (uris == null || uris.size < 2) return progressive.createMediaSource(mediaItem)
            return MergingMediaSource(
                *uris.map { progressive.createMediaSource(MediaItem.fromUri(it)) }.toTypedArray()
            )
        }
    }

    companion object {
        const val EXTRA_URIS = "vox.uris"
        const val EXTRA_VIDEO_ID = "vox.videoId"
        const val EXTRA_DESCRIPTION = "vox.description"
        /** Marks a MediaItem as a audio-deck track (vs. a video). */
        const val EXTRA_MUSIC = "vox.music"
        /** Set on music items playing from a local (downloaded) file. */
        const val EXTRA_CACHED = "vox.cached"

        fun extras(videoId: String, description: String, uris: List<String>): Bundle = Bundle().apply {
            putStringArray(EXTRA_URIS, uris.toTypedArray())
            putString(EXTRA_VIDEO_ID, videoId)
            putString(EXTRA_DESCRIPTION, description)
        }
    }
}
