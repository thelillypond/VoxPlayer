package com.voxfeed.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Tiny brass rivet dots in the four corners. */
internal fun Modifier.brassRivets(inset: Dp = 5.dp, radius: Dp = 1.dp): Modifier = drawBehind {
    if (size.width < 24.dp.toPx() || size.height < 16.dp.toPx()) return@drawBehind
    val i = inset.toPx()
    val r = radius.toPx()
    listOf(
        Offset(i, i), Offset(size.width - i, i), Offset(i, size.height - i), Offset(size.width - i, size.height - i)
    ).forEach {
        drawCircle(Waaagh.BrassLo, r * 1.4f, it)
        drawCircle(Waaagh.BrassHi, r, it)
        drawCircle(Color.White.copy(alpha = 0.5f), r * 0.35f, it - Offset(r * 0.3f, r * 0.3f))
    }
}

/** 0..1 pulse that only animates while [playing]. */
@Composable
private fun rememberPulse(playing: Boolean): State<Float> {
    if (!playing) return remember { mutableStateOf(0f) }
    return rememberInfiniteTransition(label = "optic").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "opticPulse"
    )
}

/**
 * Mechanicus cog with a central optic lens: glowing red when idle, phosphor green while audio plays
 * (the same servo-skull optic language as the VoxFeed header).
 */
@Composable
internal fun CogitatorEmblem(playing: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val pulse = rememberPulse(playing)
    Canvas(modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2f
        val brass = Waaagh.BrassHi
        for (i in 0 until 8) {
            rotate(i * 45f, c) {
                drawRect(brass, Offset(c.x - r * 0.11f, c.y - r), Size(r * 0.22f, r * 0.3f))
            }
        }
        drawCircle(brass, r * 0.70f, c, style = Stroke(r * 0.14f))
        drawCircle(Color(0xFF070908), r * 0.62f, c)
        val lens = if (playing) Color(0xFF39FF14) else Color(0xFFFF1744)
        val a = 0.55f + 0.45f * (if (playing) pulse.value else 0.7f)
        drawCircle(
            Brush.radialGradient(listOf(lens.copy(alpha = a), lens.copy(alpha = a * 0.45f), Color.Transparent), center = c, radius = r * 0.5f),
            r * 0.5f, c
        )
        drawCircle(lens.copy(alpha = a), r * 0.17f, c)
        drawCircle(Color.White.copy(alpha = 0.7f), r * 0.05f, c - Offset(r * 0.05f, r * 0.05f))
    }
}
