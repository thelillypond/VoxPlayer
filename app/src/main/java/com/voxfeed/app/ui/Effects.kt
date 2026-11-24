package com.voxfeed.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

object Grim {
    val Obsidian = Color(0xFF0B0D0A)
    val Card = Color(0xFF141812)
    val Brass = Color(0xFF6B5B35)
    val BrassBright = Color(0xFFB8A15A)
    val Cream = Color(0xFFD8D5A8)
    val Phosphor = Color(0xFF9EE052)
    val Dim = Color(0xFF7D8871)
    val Amber = Color(0xFFFFB74D)
}

/** CRT scanlines (2px lines every 4px, alpha 0.15) plus a radial edge vignette. Draws only; no input. */
@Composable
fun CrtOverlay(modifier: Modifier = Modifier, vignette: Boolean = true) {
    Canvas(modifier) {
        if (vignette) {
            drawRect(
                Brush.radialGradient(
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.6f),
                    center = center,
                    radius = maxOf(size.width, size.height) * 0.75f
                )
            )
        }
        val line = Color.Black.copy(alpha = 0.15f)
        var y = 0f
        while (y < size.height) {
            drawRect(line, Offset(0f, y), Size(size.width, 2f))
            y += 4f
        }
    }
}

/** Flame positions inside footer_altar as fractions of the image. */
private val flames = listOf(
    0.109f to 0.417f, 0.192f to 0.457f, 0.258f to 0.574f,
    0.150f to 0.619f, 0.304f to 0.641f, 0.228f to 0.726f
)

/** Candle flicker glow + incense smoke, drawn over a footer that has the same bounds. */
@Composable
fun AltarEffects(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "altar")
    val flickerFast by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(90, easing = LinearEasing), RepeatMode.Reverse), label = "flickerFast"
    )
    val flickerSlow by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(230, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "flickerSlow"
    )
    val t by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "t"
    )
    val spillPulse by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "spill"
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val twoPi = 2 * PI.toFloat()
        // ambient light spill: wide soft amber washes cast up onto the CRT and inner brass bezel.
        // (Canvas does not clip, so the washes extend above the footer bounds.)
        val spillMix = (0.5f * flickerFast + 0.5f * spillPulse)
        val leftA = 0.10f + 0.12f * (0.55f * spillMix + 0.45f * (0.5f + 0.5f * sin(twoPi * 5 * t)))
        val rightA = 0.10f + 0.12f * (0.55f * (1f - spillMix) + 0.45f * (0.5f + 0.5f * sin(twoPi * 4 * t + 1.3f)))
        fun spill(c: Offset, r: Float, a: Float) = drawCircle(
            Brush.radialGradient(
                listOf(Grim.Amber.copy(alpha = a), Grim.Amber.copy(alpha = a * 0.4f), Color.Transparent),
                center = c, radius = r
            ),
            radius = r, center = c
        )
        spill(Offset(0.20f * w, 0.50f * h), w * 0.75f, leftA)
        spill(Offset(0.81f * w, 0.66f * h), w * 0.60f, rightA)
        // candle glows (integer harmonics keep the loop seamless)
        flames.forEachIndexed { i, (fx, fy) ->
            val wob = 0.5f + 0.5f * sin(twoPi * (3 + i) * t + i)
            val wob2 = 0.5f + 0.5f * sin(twoPi * (7 + 2 * i) * t + 2f * i)
            val mix = (0.4f * flickerFast + 0.3f * flickerSlow + 0.2f * wob + 0.1f * wob2).coerceIn(0f, 1f)
            val a = 0.65f + 0.35f * mix
            val scale = 0.65f + 0.35f * (0.5f * wob2 + 0.5f * flickerFast)
            val c = Offset(fx * w, fy * h)
            val r = w * (0.030f + 0.020f * scale)
            drawCircle(
                Brush.radialGradient(
                    listOf(Grim.Amber.copy(alpha = 0.6f * a), Grim.Amber.copy(alpha = 0.2f * a), Color.Transparent),
                    center = c, radius = r
                ),
                radius = r, center = c
            )
        }
        // incense smoke above the bowl
        val ox = 0.81f * w
        val oy = 0.68f * h
        val n = 16
        for (i in 0 until n) {
            val p = (t + i / n.toFloat()) % 1f
            val seed = i * 1.7f
            val sway = sin(p * 7f + seed) * w * 0.02f * (0.4f + p * 1.6f)
            val x = ox + sway + sin(seed) * w * 0.012f
            val y = oy - p * h * 0.62f
            val r = w * (0.008f + 0.03f * p)
            val a = sin(p * PI.toFloat()).coerceAtLeast(0f) * 0.22f
            val tint = if (i % 3 == 0) Color(0xFFC9C4B0) else if (i % 3 == 1) Color(0xFF9AA08F) else Color(0xFFB8A98A)
            drawCircle(
                Brush.radialGradient(listOf(tint.copy(alpha = a), Color.Transparent), center = Offset(x, y), radius = r),
                radius = r, center = Offset(x, y)
            )
        }
    }
}

/** Pulsing bionic-eye glow and bezel gauge lamps, drawn over header_cogitator (same bounds). */
@Composable
fun HeaderEffects(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "eye")
    val pulse by inf.animateFloat(
        0.65f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "eyePulse"
    )
    val green by inf.animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "gGauge"
    )
    val amber by inf.animateFloat(
        0.30f, 1f, infiniteRepeatable(tween(950, easing = LinearEasing), RepeatMode.Reverse), label = "aGauge"
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // lens centre measured on the art: ~44% of width, ~37% of image height
        val eye = Offset(0.443f * w, 0.368f * h)
        val r = w * (0.075f + 0.035f * pulse)
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFFFF1744).copy(alpha = 0.85f * pulse),
                0.35f to Color(0xFFFF1744).copy(alpha = 0.45f * pulse),
                0.7f to Color(0xFFD50000).copy(alpha = 0.18f * pulse),
                1f to Color.Transparent,
                center = eye, radius = r
            ),
            radius = r, center = eye
        )
        fun lamp(c: Offset, color: Color, a: Float) {
            val lr = w * 0.022f
            drawCircle(
                Brush.radialGradient(
                    listOf(color.copy(alpha = 0.8f * a), color.copy(alpha = 0.25f * a), Color.Transparent),
                    center = c, radius = lr
                ),
                radius = lr, center = c
            )
        }
        lamp(Offset(0.497f * w, 0.282f * h), Color(0xFF69F0AE), green)
        lamp(Offset(0.403f * w, 0.362f * h), Grim.Amber, amber)
        lamp(Offset(0.479f * w, 0.325f * h), Color(0xFF69F0AE), 1.35f - green)
    }
}

/** Phosphor glow behind the active hardware switch. Fractions are of the bezel image. */
val switchCenters = listOf(0.184f, 0.394f, 0.608f, 0.817f)
const val SWITCH_TOP = 0.82f
const val SWITCH_HEIGHT = 0.15f
const val SWITCH_WIDTH = 0.2f

@Composable
fun SwitchGlow(active: Int, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "glow")
    val pulse by inf.animateFloat(
        0.65f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse"
    )
    Canvas(modifier) {
        val c = Offset(switchCenters[active] * size.width, (SWITCH_TOP + SWITCH_HEIGHT * 0.62f) * size.height)
        val r = size.width * 0.13f
        drawCircle(
            Brush.radialGradient(
                listOf(Grim.Phosphor.copy(alpha = 0.55f * pulse), Grim.Phosphor.copy(alpha = 0.15f * pulse), Color.Transparent),
                center = c, radius = r
            ),
            radius = r, center = c
        )
        // indicator bar on the switch slider
        drawRect(
            Grim.Phosphor.copy(alpha = 0.85f * pulse),
            Offset(c.x - size.width * 0.05f, c.y - size.height * 0.004f),
            Size(size.width * 0.1f, size.height * 0.008f)
        )
    }
}

/** Translucent CRT diagnostic overlay with rotating brass cog and block progress bar. */
@Composable
fun DiagnosticOverlay(text: String, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "diag")
    val angle by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "cog")
    val prog by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "bar")
    val blink by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "blink")
    val blocks = 14
    val lit = (prog * (blocks + 1)).toInt()
    Box(
        modifier
            .background(Color(0xCC0B0D0A))
            .pointerInput(Unit) { detectTapGestures { } }, // swallow touches while busy
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Default.Settings, null,
                tint = Grim.BrassBright, modifier = Modifier.size(36.dp).rotate(angle)
            )
            Text(
                text, color = Grim.Phosphor.copy(alpha = blink), fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, fontSize = 11.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(blocks) { i ->
                    Box(
                        Modifier.size(width = 9.dp, height = 12.dp)
                            .background(if (i < lit) Grim.Phosphor else Grim.Phosphor.copy(alpha = 0.12f))
                    )
                }
            }
        }
    }
}
