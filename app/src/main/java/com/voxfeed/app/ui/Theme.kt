package com.voxfeed.app.ui

import android.graphics.Typeface
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ColumnScope

/** High-contrast dark palette shared by every screen. */
object Palette {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF121212)
    val SurfaceHi = Color(0xFF1C1C1C)
    val Border = Color(0xFF3A3A3A)
    val BorderHi = Color(0xFF6A6A6A)
    val Text = Color(0xFFF5F5F5)
    val Dim = Color(0xFFA0A0A0)
    /** Primary accent. */
    val Accent = Color(0xFF39FF14)
    /** Secondary accent. */
    val Amber = Color(0xFFD49B27)
    val Alert = Color(0xFFFF3B30)
}

internal val Heavy = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))
internal val Mono = FontFamily.Monospace
internal val ButtonShape = RoundedCornerShape(8.dp)

/** Solid app background. */
internal fun Modifier.deckBackground(): Modifier = background(Palette.Bg)

/** Simple 1dp rounded outline used around list panels. */
internal fun Modifier.panelFrame(color: Color = Palette.Border, @Suppress("UNUSED_PARAMETER") tick: Color = Palette.Accent): Modifier =
    border(1.dp, color, RoundedCornerShape(8.dp))

/** Scrub track: dark channel with an accent fill. */
@Composable
internal fun ScrubTrack(fraction: Float) {
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        drawRoundRect(Color(0xFF2A2A2A), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        val fw = size.width * fraction.coerceIn(0f, 1f)
        if (fw > 0f) drawRoundRect(Palette.Accent, size = Size(fw, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
    }
}

/** Standard button. Primary actions use [accent]; toggled-on state uses [active]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ActionButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    active: Boolean = false, accent: Boolean = false, height: Dp = 44.dp, size: Int = 13,
    hPad: Dp = 10.dp, vPad: Dp = 8.dp,
    icon: ImageVector? = null, iconAtEnd: Boolean = false
) {
    Box(
        modifier.heightIn(min = height).alpha(if (enabled) 1f else 0.4f)
            .clip(ButtonShape)
            .background(if (accent) Palette.SurfaceHi else Palette.Surface)
            .border(1.dp, if (active) Palette.Accent else if (accent) Palette.BorderHi else Palette.Border, ButtonShape)
            .combinedClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = hPad, vertical = vPad),
        contentAlignment = Alignment.Center
    ) {
        val tint = if (accent || active) Palette.Accent else Palette.Text
        val iconContent: @Composable () -> Unit = {
            if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        val textContent: @Composable () -> Unit = {
            if (label.isNotEmpty()) Text(
                label, color = tint,
                fontFamily = Heavy, fontWeight = FontWeight.Black, fontSize = size.sp, textAlign = TextAlign.Center, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        if (icon == null) textContent()
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (label.isEmpty()) 0.dp else 4.dp)) {
            if (iconAtEnd) { textContent(); iconContent() } else { iconContent(); textContent() }
        }
    }
}

@Composable
internal fun Chip(label: String, selected: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val c = if (danger) Palette.Alert else if (selected) Palette.Accent else Palette.Text
    Text(
        label, color = c, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1,
        modifier = Modifier.border(if (selected) 2.dp else 1.dp, if (selected) c else Palette.Border, RoundedCornerShape(16.dp))
            .background(if (selected) Palette.Surface else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp)
    )
}

@Composable
internal fun NameField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    BasicTextField(
        value, onChange, singleLine = true,
        textStyle = TextStyle(color = Palette.Text, fontFamily = Mono, fontSize = 13.sp),
        cursorBrush = SolidColor(Palette.Accent),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
                if (value.isEmpty()) Text(hint, color = Palette.Dim, fontFamily = Mono, fontSize = 13.sp)
                inner()
            }
        },
        modifier = modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(8.dp)).padding(10.dp)
    )
}

/** Centered modal card used by every small dialog. */
@Composable
internal fun AppDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(Palette.Bg, RoundedCornerShape(12.dp))
                .border(1.dp, Palette.BorderHi, RoundedCornerShape(12.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content
        )
    }
}

/** Full-width scrollable modal for long forms (settings, add channel). */
@Composable
internal fun AppFormDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .imePadding().systemBarsPadding().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier.fillMaxWidth().clip(shape).background(Palette.Bg).border(1.dp, Palette.BorderHi, shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content
                )
            }
        }
    }
}

@Composable
internal fun AppLabel(text: String, color: Color = Palette.Text, size: androidx.compose.ui.unit.TextUnit = 12.sp) {
    Text(text, color = color, fontFamily = Heavy, fontWeight = FontWeight.Bold, fontSize = size)
}

/** Translucent busy overlay with spinner; swallows touches while visible. */
@Composable
internal fun LoadingOverlay(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.background(Color(0xCC000000)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = Palette.Accent)
            Text(text, color = Palette.Text, fontFamily = Heavy, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}
