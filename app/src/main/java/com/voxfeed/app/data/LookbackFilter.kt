package com.voxfeed.app.data

import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale

/** Lookback-window rule shared by the live feed query, scraper inserts and background polls. */
object LookbackFilter {
    /** Windows up to 7D are "strict": videos with unknown (0L) publish time are excluded. */
    private const val STRICT_MAX_HOURS = 168

    fun passes(publishedAt: Long, hours: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (hours <= 0) return true
        if (publishedAt <= 0L) return hours > STRICT_MAX_HOURS
        return publishedAt >= now - hours * 3_600_000L
    }
}

/** Upload-time extraction for NewPipeExtractor items; 0L means unknown. */
object UploadTime {
    private val relative = Regex("(\\d+)\\s*(second|minute|hour|day|week|month|year)s?\\s+ago", RegexOption.IGNORE_CASE)

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    fun of(item: StreamInfoItem, now: Long = System.currentTimeMillis()): Long {
        item.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli()?.let { return it }
        return parseTextual(item.textualUploadDate, now)
    }

    /** "3 hours ago", "Streamed 5 days ago", "4 months ago"... -> approximate epoch ms, or 0L if unparseable. */
    fun parseTextual(text: String?, now: Long = System.currentTimeMillis()): Long {
        if (text.isNullOrBlank()) return 0L
        val m = relative.find(text)
        if (m == null) {
            return when (text.lowercase(Locale.ROOT)) {
                "just now", "moments ago" -> now
                "yesterday" -> now - DAY
                else -> 0L
            }
        }
        val n = m.groupValues[1].toLongOrNull() ?: return 0L
        val unit = when (m.groupValues[2].lowercase(Locale.ROOT)) {
            "second" -> 1_000L
            "minute" -> MIN
            "hour" -> HOUR
            "day" -> DAY
            "week" -> 7 * DAY
            "month" -> 30 * DAY
            else -> 365 * DAY
        }
        return now - n * unit
    }
}
