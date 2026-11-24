package com.voxfeed.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Cogitator Vox-Terminal skin: pure Canvas, no assets. Brass #D49B27 frames, phosphor #39FF14 ticks. */

/** Faint horizontal CRT scanlines (alpha 0.04) drawn behind content so text stays sharp. */
internal fun Modifier.cogitatorScanlines(): Modifier = drawBehind {
    drawRect(Waaagh.Iron)
    val line = Color(0xFF39FF14).copy(alpha = 0.04f)
    val step = 3.dp.toPx()
    var y = 0f
    while (y < size.height) { drawRect(line, Offset(0f, y), Size(size.width, 1f)); y += step }
}

/** Crisp 1dp chamfered terminal frame with phosphor corner tick marks (tactical readout style). */
internal fun Modifier.cogitatorFrame(color: Color = Waaagh.Hazard, tick: Color = Waaagh.Green): Modifier = drawBehind {
    val sw = 1.dp.toPx()
    val e = sw / 2f
    val cut = 6.dp.toPx()
    val w = size.width
    val h = size.height
    val frame = Path().apply {
        moveTo(cut, e); lineTo(w - cut, e); lineTo(w - e, cut); lineTo(w - e, h - cut)
        lineTo(w - cut, h - e); lineTo(cut, h - e); lineTo(e, h - cut); lineTo(e, cut); close()
    }
    drawPath(frame, color, style = Stroke(sw))
    // corner ticks just inside each chamfer
    val t = 4.dp.toPx()
    val g = 3.dp.toPx()
    val ts = Stroke(sw)
    fun tickL(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(tick, Offset(x, y), Offset(x + dx * t, y), sw)
        drawLine(tick, Offset(x, y), Offset(x, y + dy * t), sw)
    }
    tickL(cut + g, g, 1f, 1f)
    tickL(w - cut - g, g, -1f, 1f)
    tickL(cut + g, h - g, 1f, -1f)
    tickL(w - cut - g, h - g, -1f, -1f)
}

/** Scrub track: dark slate channel, brass outline, phosphor-green fill. */
@Composable
internal fun ScrubTrack(fraction: Float) {
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        drawRect(Color(0xFF070908))
        val fw = size.width * fraction.coerceIn(0f, 1f)
        if (fw > 0f) {
            drawRect(Color(0xFF39FF14).copy(alpha = 0.25f), Offset(0f, -1.5.dp.toPx()), Size(fw, size.height + 3.dp.toPx()))
            drawRect(Color(0xFF39FF14), Offset.Zero, Size(fw, size.height))
        }
        drawRect(Waaagh.Hazard, style = Stroke(1.dp.toPx()))
    }
}
