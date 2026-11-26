package com.voxfeed.app.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxfeed.app.MainActivity
import com.voxfeed.app.data.VideoEntity
import com.voxfeed.app.data.VoxDatabase
import com.voxfeed.app.data.YouTubeRssRepository

/**
 * Polls only alert-enabled channels, applies the lookback window, inserts new videos into Room
 * and posts a grouped "New videos" notification. Existing rows are never overwritten (IGNORE inserts).
 */
class VoxAlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext
        if (!PollPrefs.voxAlertsEnabled(c) || PollPrefs.pollIntervalHours(c) == 0) return Result.success()
        val scan = YouTubeRssRepository(VoxDatabase.get(c), c).refreshForAlerts()
        if (scan.total == 0) return Result.success()
        if (scan.failed >= scan.total) return Result.retry() // everything failed: likely transient network

        PollPrefs.setLastSynced(c, System.currentTimeMillis())
        if (scan.newVideos.isNotEmpty()) notifyNew(scan.newVideos.sortedByDescending { it.publishedAt })
        return Result.success()
    }

    private fun notifyNew(videos: List<VideoEntity>) {
        val c = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= 26) {
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "New videos", NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(false) }
            )
        }
        val nm = NotificationManagerCompat.from(c)

        fun pending(code: Int, videoId: String?): PendingIntent {
            val intent = Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (videoId != null) intent.putExtra(MainActivity.EXTRA_OPEN_VIDEO_ID, videoId)
            return PendingIntent.getActivity(c, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        videos.take(MAX_INDIVIDUAL).forEach { v ->
            val n = NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(v.title.ifBlank { "New video" })
                .setContentText(v.channelTitle)
                .setGroup(GROUP_KEY)
                .setContentIntent(pending(v.videoId.hashCode(), v.videoId))
                .setAutoCancel(true)
                .build()
            nm.notify(v.videoId.hashCode(), n)
        }

        val inbox = NotificationCompat.InboxStyle()
        videos.take(MAX_INDIVIDUAL).forEach { inbox.addLine("${it.channelTitle} - ${it.title}") }
        if (videos.size > MAX_INDIVIDUAL) inbox.addLine("+${videos.size - MAX_INDIVIDUAL} more")
        val count = videos.size
        val summaryText = "$count new video${if (count == 1) "" else "s"}"
        val summary = NotificationCompat.Builder(c, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("New videos")
            .setContentText(summaryText)
            .setStyle(inbox.setSummaryText(summaryText))
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(pending(0, null))
            .setAutoCancel(true)
            .build()
        nm.notify(SUMMARY_ID, summary)
    }

    private companion object {
        const val CHANNEL_ID = "vox_alerts_channel"
        const val GROUP_KEY = "com.voxfeed.app.VOX_ALERTS"
        const val SUMMARY_ID = 4101
        const val MAX_INDIVIDUAL = 5
    }
}
