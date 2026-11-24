package com.voxfeed.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.voxfeed.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Chroma-keyed overlay art. [viewport] is the CRT screen rect as 0..1 fractions of the bezel. */
class GrimArt(
    val header: ImageBitmap,
    val footer: ImageBitmap,
    val bezel: ImageBitmap,
    val viewport: Rect
)

object ArtLoader {
    @Volatile private var cache: GrimArt? = null

    suspend fun load(context: Context): GrimArt = cache ?: withContext(Dispatchers.Default) {
        val ctx = context.applicationContext
        val header = chromaKey(decode(ctx, R.drawable.header_cogitator, 1200))
        val footer = chromaKey(decode(ctx, R.drawable.footer_altar, 1400))
        val bezelKeyed = chromaKey(decode(ctx, R.drawable.frame_bezel, 800))
        val viewport = clearViewport(bezelKeyed)
        GrimArt(header.asImageBitmap(), footer.asImageBitmap(), bezelKeyed.asImageBitmap(), viewport)
            .also { cache = it }
    }

    private fun ensureMutable(b: Bitmap): Bitmap =
        if (b.isMutable && b.config == Bitmap.Config.ARGB_8888) b
        else b.copy(Bitmap.Config.ARGB_8888, true).also { b.recycle() }

    /** Decodes with power-of-two downsampling so the multi-megapixel JPEGs stay small in memory. */
    private fun decode(ctx: Context, @DrawableRes id: Int, targetWidth: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(ctx.resources, id, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return ensureMutable(BitmapFactory.decodeResource(ctx.resources, id, opts)!!)
    }

    /**
     * Pure-blue pixels (b > 140 && b > r + 60 && b > g + 60) become fully transparent.
     * Near-blue pixels get a soft alpha ramp plus blue despill to anti-alias the edges.
     */
    private fun chromaKey(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF
            if (b > 140 && b > r + 60 && b > g + 60) {
                px[i] = 0
                continue
            }
            val m = max(r, g)
            val d = b - m
            if (d > 30 && b > 60) {
                val t = ((60 - d) / 30f).coerceIn(0f, 1f)
                b = minOf(b, m + 10) // despill
                px[i] = ((t * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        src.recycle()
        val out = ensureMutable(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Finds the transparent centre region (flood fill from the middle), then explicitly wipes its
     * whole bounding box so no JPEG ghosting survives. Returns the box as 0..1 fractions.
     */
    private fun clearViewport(source: Bitmap): Rect {
        // chromaKey() always returns a mutable ARGB_8888 bitmap; never copy here, since the caller keeps using it
        check(source.isMutable) { "bezel bitmap must be mutable" }
        val bmp = source
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        fun clear(i: Int) = (px[i] ushr 24) < 16
        var start = (h / 2) * w + w / 2
        if (!clear(start)) {
            // fall back to the most central transparent pixel
            start = px.indices.filter { clear(it) }.minByOrNull {
                val dx = it % w - w / 2; val dy = it / w - h / 2; dx * dx + dy * dy
            } ?: return Rect(0.1f, 0.27f, 0.9f, 0.69f)
        }
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0; var tail = 0
        queue[tail++] = start; seen[start] = true
        var minX = w; var minY = h; var maxX = 0; var maxY = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % w; val y = i / w
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (x > 0 && !seen[i - 1] && clear(i - 1)) { seen[i - 1] = true; queue[tail++] = i - 1 }
            if (x < w - 1 && !seen[i + 1] && clear(i + 1)) { seen[i + 1] = true; queue[tail++] = i + 1 }
            if (y > 0 && !seen[i - w] && clear(i - w)) { seen[i - w] = true; queue[tail++] = i - w }
            if (y < h - 1 && !seen[i + w] && clear(i + w)) { seen[i + w] = true; queue[tail++] = i + w }
        }
        val rw = maxX - minX + 1
        val rh = maxY - minY + 1
        val zero = IntArray(rw)
        for (y in minY..maxY) bmp.setPixels(zero, 0, rw, minX, y, rw, 1)
        return Rect(minX / w.toFloat(), minY / h.toFloat(), (maxX + 1) / w.toFloat(), (maxY + 1) / h.toFloat())
    }
}


