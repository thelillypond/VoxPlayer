package com.voxfeed.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Polling preferences (SharedPreferences) plus the WorkManager scheduling entry point. */
object PollPrefs {
    const val WORK_NAME = "vox_alert_poll"
    private const val LEGACY_WORK_NAME = "voxfeed_rss_poll"
    val INTERVAL_HOURS = listOf(0, 4, 6, 12, 24)
    /** hours -> label; 0 = ALL. */
    val LOOKBACK_OPTIONS = listOf(24 to "24H", 72 to "3D", 168 to "7D", 720 to "30D", 0 to "ALL")
    private const val FILE = "voxfeed_poll"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 0 = OFF, else 4/6/12/24. Default 6. */
    fun pollIntervalHours(c: Context): Int = sp(c).getInt("poll_interval_hours", 6)
    fun setPollIntervalHours(c: Context, v: Int) = sp(c).edit().putInt("poll_interval_hours", v).apply()

    /** 24, 72, 168, 720, or 0 = ALL. Default 168 (7D). */
    fun lookbackWindowHours(c: Context): Int = sp(c).getInt("lookback_window_hours", 168)
    fun setLookbackWindowHours(c: Context, v: Int) = sp(c).edit().putInt("lookback_window_hours", v).apply()

    /** Oldest publish timestamp (epoch ms) that passes the lookback filter, or null for ALL. */
    fun lookbackCutoff(c: Context): Long? =
        lookbackWindowHours(c).takeIf { it > 0 }?.let { System.currentTimeMillis() - it * 3_600_000L }

    fun wifiOnly(c: Context) = sp(c).getBoolean("wifi_only", false)
    fun setWifiOnly(c: Context, v: Boolean) = sp(c).edit().putBoolean("wifi_only", v).apply()

    fun voxAlertsEnabled(c: Context) = sp(c).getBoolean("vox_alerts", false)
    fun setVoxAlertsEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("vox_alerts", v).apply()

    /** Google AI Studio (Gemini) API key for the Abominable Intelligence digest. */
    fun geminiKey(c: Context): String = sp(c).getString("gemini_key", "").orEmpty()
    fun setGeminiKey(c: Context, v: String) = sp(c).edit().putString("gemini_key", v.trim()).apply()

    /** Silica Animus depth: "BRIEF" or "DEEP_DOSSIER". */
    fun summaryDepth(c: Context): String = sp(c).getString("summary_depth", "BRIEF") ?: "BRIEF"
    fun setSummaryDepth(c: Context, v: String) = sp(c).edit().putString("summary_depth", v).apply()

    /** Hyperlink taps in digests; default OFF to avoid accidental taps while scrolling. */
    fun hyperlinks(c: Context) = sp(c).getBoolean("hyperlinks", false)
    fun setHyperlinks(c: Context, v: Boolean) = sp(c).edit().putBoolean("hyperlinks", v).apply()

    fun lastSynced(c: Context) = sp(c).getLong("last_synced", 0L)
    fun setLastSynced(c: Context, t: Long) = sp(c).edit().putLong("last_synced", t).apply()

    /** Enqueues (UPDATE) the periodic alert job from current prefs; cancels it when OFF / alerts disabled. */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelUniqueWork(LEGACY_WORK_NAME)
        val hours = pollIntervalHours(context)
        if (hours == 0 || !voxAlertsEnabled(context)) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<VoxAlertWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}