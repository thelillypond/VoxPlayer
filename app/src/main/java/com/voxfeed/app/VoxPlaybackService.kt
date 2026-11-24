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
import androidx.media3.session.MediaSessionService
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
class VoxPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            // Pre-create media3's default notification channel so the launcher icon never gets a badge dot.
            val nm = getSystemService(android.app.NotificationManager::class.java)
            val channel = android.app.NotificationChannel("default_channel_id", "Vox Playback", android.app.NotificationManager.IMPORTANCE_LOW)
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

        val session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()
        mediaSession = session

        // Tapping the lock-screen / shade notification returns to the player for the current video.
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) AudioSpectrum.stopDecay() else AudioSpectrum.startDecay()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val extras = mediaItem?.requestMetadata?.extras ?: return
                val intent = if (extras.getBoolean(EXTRA_MUSIC, false)) {
                    // Music track: notification returns to the app (MainActivity), not the video player.
                    Intent(this@VoxPlaybackService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                } else Intent(this@VoxPlaybackService, PictFeedActivity::class.java)
                    .putExtra(PictFeedActivity.VIDEO_ID, extras.getString(EXTRA_VIDEO_ID))
                    .putExtra(PictFeedActivity.VIDEO_TITLE, mediaItem.mediaMetadata.title?.toString().orEmpty())
                    .putExtra(PictFeedActivity.VIDEO_DESCRIPTION, extras.getString(EXTRA_DESCRIPTION).orEmpty())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                session.setSessionActivity(
                    PendingIntent.getActivity(
                        this@VoxPlaybackService, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

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
        super.onDestroy()
    }

    /** Resolves controller-supplied items (which have no localConfiguration) into playable ones. */
    private class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val first = item.requestMetadata.extras?.getStringArray(EXTRA_URIS)?.firstOrNull()
                val md = item.mediaMetadata
                fun clean(s: CharSequence?, fallback: String) = s?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: fallback
                val safe = item.buildUpon().setMediaMetadata(
                    md.buildUpon()
                        .setTitle(clean(md.title, "Unknown Audio-Stream"))
                        .setArtist(clean(md.artist, "VoxFeed Audio"))
                        .setAlbumTitle(clean(md.albumTitle, "Vox-Archive"))
                        .build()
                )
                if (first != null) safe.setUri(first).build() else safe.build()
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    /**
     * Lazily swaps a YouTube watch URL (streamed music tracks) for a direct audio stream URL on ExoPlayer's
     * loader thread, so nothing blocks the main thread. Local files and googlevideo URLs pass straight through.
     */
    private object YouTubeResolver : androidx.media3.datasource.ResolvingDataSource.Resolver {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
        private const val TTL_MS = 90L * 60 * 1000

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
        /** Marks a MediaItem as a Mek-Amp music track (vs. a VoxFeed video). */
        const val EXTRA_MUSIC = "vox.music"
        /** Set on music items playing from a local (downloaded) file. */
        const val EXTRA_STASHED = "vox.stashed"

        fun extras(videoId: String, description: String, uris: List<String>): Bundle = Bundle().apply {
            putStringArray(EXTRA_URIS, uris.toTypedArray())
            putString(EXTRA_VIDEO_ID, videoId)
            putString(EXTRA_DESCRIPTION, description)
        }
    }
}
