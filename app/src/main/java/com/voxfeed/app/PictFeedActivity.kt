package com.voxfeed.app

import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.voxfeed.app.data.SubtitleCue
import com.voxfeed.app.data.VideoChapter
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.google.common.util.concurrent.ListenableFuture
import com.voxfeed.app.data.MediaArchiveManager
import com.voxfeed.app.data.VoxDatabase
import com.voxfeed.app.data.YouTubeStreamExtractor
import com.voxfeed.app.ui.ArchiveUiState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import android.net.Uri
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import androidx.compose.runtime.snapshotFlow
import com.voxfeed.app.ui.PictFeedScreen
import com.voxfeed.app.ui.IntelState
import com.voxfeed.app.data.CogitatorSummaryManager
import com.voxfeed.app.data.DigestResult
import com.voxfeed.app.data.failureMessage
import com.voxfeed.app.worker.PollPrefs
import com.voxfeed.app.ui.StreamState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

/**
 * Dedicated top-level window for the native player: Media3 ExoPlayer fed by NewPipeExtractor,
 * with a fully custom grimdark HUD (no YouTube web UI).
 */
class PictFeedActivity : ComponentActivity() {
    private var controller: MediaController? by mutableStateOf<MediaController?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var voxFilterEnabled by mutableStateOf(true)
    private var ended by mutableStateOf(false)
    private var landscape by mutableStateOf(false)
    private var streamState by mutableStateOf<StreamState>(StreamState.Resolving)
    private var archiveState by mutableStateOf(ArchiveUiState.IDLE)
    private var archivePercent by mutableStateOf(0)
    private var subsEnabled by mutableStateOf(false)
    private var subtitleCues by mutableStateOf<List<SubtitleCue>>(emptyList())
    private var currentCue by mutableStateOf<String?>(null)
    private var chapters by mutableStateOf<List<VideoChapter>>(emptyList())
    private var voxDigest by mutableStateOf<String?>(null)
    private var intelState by mutableStateOf<IntelState>(IntelState.Idle)
    // Initialised in onCreate: PollPrefs needs a Context, which is not attached during field initialisation.
    private var summaryDepth by mutableStateOf(com.voxfeed.app.data.SummaryDepth.BRIEF)
    private val db by lazy { VoxDatabase.get(applicationContext) }
    private val archiveManager by lazy { MediaArchiveManager.get(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        super.onCreate(savedInstanceState)
        summaryDepth = com.voxfeed.app.data.SummaryDepth.from(PollPrefs.summaryDepth(applicationContext))

        val videoId = intent.getStringExtra(VIDEO_ID)?.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (videoId.isNullOrBlank()) { finish(); return }
        val title = intent.getStringExtra(VIDEO_TITLE).orEmpty()
        val description = intent.getStringExtra(VIDEO_DESCRIPTION).orEmpty()

        lifecycleScope.launch { startPlayback(videoId, title, description) }

        setContent {
            val player = controller
            Box(Modifier.fillMaxSize()) {
            if (player == null) {
                Box(Modifier.fillMaxSize().background(Color.Black))
            } else PictFeedScreen(
                player = player, state = streamState, title = title, description = description,
                landscape = landscape, voxFilterEnabled = voxFilterEnabled, ended = ended,
                onToggleFilter = { voxFilterEnabled = !voxFilterEnabled },
                onClose = { stopPlayback(); finish() },
                archiveState = archiveState,
                archivePercent = archivePercent,
                onArchive = {
                    archivePercent = 0
                    archiveState = ArchiveUiState.ARCHIVING
                    archiveManager.archive(videoId) // application-scoped; keeps running if we leave
                },
                videoTitle = title,
                onPurge = {
                    lifecycleScope.launch {
                        stopPlayback()
                        runCatching { archiveManager.deleteArchive(videoId) } // posts DATA-SLATE PURGED banner
                        kotlinx.coroutines.delay(1800)
                        finish()
                    }
                },
                subsAvailable = subtitleCues.isNotEmpty(),
                subtitleText = if (subsEnabled) currentCue else null,
                subsEnabled = subsEnabled,
                onToggleSubs = {
                    // Only flips Compose state; ExoPlayer is never touched.
                    if (subtitleCues.isNotEmpty()) subsEnabled = !subsEnabled
                },
                chapters = chapters,
                voxDigest = voxDigest,
                intelState = intelState,
                summaryDepth = summaryDepth,
                onDepthChange = { d -> summaryDepth = d; PollPrefs.setSummaryDepth(applicationContext, d.name) },
                onGenerateDigest = {
                    if (intelState !is IntelState.Loading) {
                        intelState = IntelState.Loading
                        val depth = summaryDepth // the digest is cached under the depth it was requested with
                        lifecycleScope.launch {
                            val result = try {
                                CogitatorSummaryManager.generate(
                                    PollPrefs.geminiKey(applicationContext), videoId, title, description, depth, db.digestDao()
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                DigestResult.NetworkOrApiError(e.message ?: e.javaClass.simpleName)
                            }
                            // On success the digest arrives through the Room observer below.
                            intelState = if (result is DigestResult.Success) IntelState.Idle
                            else IntelState.Failed(result.failureMessage())
                        }
                    }
                }
            )
            com.voxfeed.app.ui.CogitatorBannerHost()
            }
        }
        applyOrientation(resources.configuration)

        // Mirror Room + live download progress into the ARCHIVE button.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    archiveManager.errors.collect {
                        com.voxfeed.app.ui.CogitatorBanner.show(it)
                    }
                }
                launch {
                    snapshotFlow { summaryDepth }.flatMapLatest { db.digestDao().observe(videoId, it.name) }
                        .collect { voxDigest = it }
                }
                combine(db.videoDao().observe(videoId), archiveManager.progress) { v, progress ->
                    val pct = progress[videoId]
                    archivePercent = pct ?: 0
                    when {
                        v?.isArchived == true -> ArchiveUiState.ARCHIVED
                        pct != null -> ArchiveUiState.ARCHIVING
                        else -> ArchiveUiState.IDLE
                    }
                }.collect { archiveState = it }
            }
        }

        // Poll playback position every 75 ms and resolve the active caption cue.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val cues = subtitleCues
                    currentCue = if (subsEnabled && cues.isNotEmpty()) {
                        val adjustedPos = (controller?.currentPosition ?: 0L) + 650L // caption lead offset
                        cues.firstOrNull { adjustedPos in it.startMs..it.endMs }?.text
                    } else null
                    kotlinx.coroutines.delay(75)
                }
            }
        }

    }

    private fun buildItem(videoId: String, title: String, description: String, uris: List<String>): MediaItem =
        MediaItem.Builder()
            .setMediaId(videoId)
            .setUri(uris.first())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("VoxFeed").build())
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setExtras(VoxPlaybackService.extras(videoId, description, uris)).build()
            )
            .build()

    /** Stops playback and clears the session (TERMINATE / purge). Back/Home leave playback running. */
    private fun stopPlayback() {
        controller?.takeIf { it.isConnected }?.run { stop(); clearMediaItems() }
    }

    /**
     * Returns a connected MediaController, (re)building it with buildAsync() when it is null or the
     * service connection dropped. Never throws; returns null (and logs) if the service is unreachable.
     * Binding through the controller lets Media3 start VoxPlaybackService safely (it promotes itself to the
     * foreground when playback begins, so no startForegroundService deadline can be missed).
     */
    private suspend fun awaitController(): MediaController? {
        controller?.takeIf { it.isConnected }?.let { return it }
        controller = null
        return try {
            val token = SessionToken(this, ComponentName(this, VoxPlaybackService::class.java))
            val future = MediaController.Builder(this, token)
                .setListener(object : MediaController.Listener {
                    override fun onDisconnected(c: MediaController) {
                        if (controller === c) controller = null
                        if (!isDestroyed && !isFinishing) lifecycleScope.launch { awaitController() }
                    }
                })
                .buildAsync()
            controllerFuture = future
            val c = kotlinx.coroutines.suspendCancellableCoroutine<MediaController> { cont ->
                future.addListener({
                    try { cont.resumeWith(Result.success(future.get())) }
                    catch (e: Exception) { cont.resumeWith(Result.failure(e)) }
                }, ContextCompat.getMainExecutor(this))
                cont.invokeOnCancellation { MediaController.releaseFuture(future) }
            }
            if (isDestroyed) { c.release(); return null }
            attachPlayerListener(c)
            controller = c
            c
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "MediaController connection failed", e)
            null
        }
    }

    private fun attachPlayerListener(player: MediaController) {
        player.addListener(object : Player.Listener {
            private var kick: kotlinx.coroutines.Job? = null
            override fun onPlaybackStateChanged(playbackState: Int) {
                ended = playbackState == Player.STATE_ENDED
                if (playbackState == Player.STATE_READY && player.playWhenReady) scheduleKick() else kick?.cancel()
            }
            /** First-frame kickstart: if still parked at 0 shortly after READY, nudge past the first keyframe. */
            private fun scheduleKick() {
                kick?.cancel()
                kick = lifecycleScope.launch {
                    kotlinx.coroutines.delay(600)
                    if (player.isConnected && player.playbackState == Player.STATE_READY && player.playWhenReady && player.currentPosition == 0L) {
                        player.seekTo(1L)
                        player.play()
                    }
                }
            }
        })
        ended = player.playbackState == Player.STATE_ENDED
    }

    private class StreamPlan(val uris: List<String>?, val chapters: List<VideoChapter>, val subUrl: String?, val subMime: String?)

    private fun reportFailure(e: Throwable, attached: Boolean) {
        Log.e(TAG, "Video start failed", e)
        val msg = e.message ?: e.javaClass.simpleName
        if (!attached) streamState = StreamState.Failed(msg)
        android.widget.Toast.makeText(applicationContext, "SIGNAL FAULT: $msg", android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * Whole click pipeline: connect -> (IO) resolve archive/stream -> (Main) controller calls.
     * Every failure is logged under VoxFeedCrash and surfaced as a toast; nothing escapes uncaught.
     */
    private suspend fun startPlayback(videoId: String, title: String, description: String) {
        var attached = false
        try {
            val first = awaitController()
            if (first == null) {
                streamState = StreamState.Failed("PLAYBACK SERVICE UNAVAILABLE")
                return
            }
            attached = first.currentMediaItem?.mediaId == videoId &&
                first.playbackState != Player.STATE_IDLE && first.mediaItemCount > 0
            if (attached) streamState = StreamState.Ready

            val plan = withContext(Dispatchers.IO) {
                // Offline-first: a verified local archive never touches the network / extractor.
                val entity = db.videoDao().get(videoId)
                val sources = MediaArchiveManager.localSources(entity?.localVideoPath)
                if (sources != null) {
                    StreamPlan(sources.map { Uri.fromFile(it).toString() }, emptyList(), null, null)
                } else {
                    if (entity?.isArchived == true) db.videoDao().clearArchive(videoId) // files vanished
                    val s = YouTubeStreamExtractor.resolveStreams(videoId, maxHeight = 720)
                    StreamPlan(listOfNotNull(s.videoUrl, s.audioUrl), s.chapters, s.subtitleUrl, s.subtitleMimeType)
                }
            }
            if (plan.chapters.isNotEmpty()) chapters = plan.chapters

            if (!attached) {
                val uris = plan.uris?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("No playable streams")
                // Re-check the connection: it may have dropped while the extractor ran.
                val player = awaitController() ?: throw IllegalStateException("Playback service disconnected")
                player.setMediaItem(buildItem(videoId, title, description, uris))
                player.prepare()
                player.play()
                streamState = StreamState.Ready
            }
            // Captions are fetched/parsed off-player and drawn by Compose.
            plan.subUrl?.let { url ->
                subtitleCues = withContext(Dispatchers.IO) {
                    runCatching { YouTubeStreamExtractor.fetchSubtitleCues(url, plan.subMime) }.getOrDefault(emptyList())
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            reportFailure(e, attached)
        }
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    private fun applyOrientation(config: Configuration) {
        landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (landscape) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // No pause in onPause: playback lives in VoxPlaybackService and survives Home / screen off.
    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VoxFeedCrash"
        const val VIDEO_ID = "VIDEO_ID"
        const val VIDEO_TITLE = "VIDEO_TITLE"
        const val VIDEO_DESCRIPTION = "VIDEO_DESCRIPTION"
    }
}