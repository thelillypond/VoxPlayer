package com.voxfeed.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.voxfeed.app.data.SummaryDepth
import com.voxfeed.app.worker.PollPrefs
import kotlinx.coroutines.delay

/** Communion state of a digest request (the cached digest itself lives in Room). */
sealed interface IntelState {
    object Idle : IntelState
    object Loading : IntelState
    data class Failed(val message: String) : IntelState
}

private val Obsidian = Color(0xFF0A0D0A)
private val Brass = Color(0xFF8C7832)
private val Cream = Color(0xFFD8D5A8)
private val PhosphorGreen = Color(0xFF00FF41)
private val Crimson = Color(0xFFB3202A)
private val CrimsonText = Color(0xFFFF6B6B)

private val RITUAL_LINES = listOf(
    "// WARNING: ACCESSING FORBIDDEN SILICA ANIMUS //",
    "// BYPASSING MARS INQUISITORIAL SEALS... //",
    "// EXTRACTING VOX-TRANSCRIPT FROM NOOSPHERE... //",
    "// SANCTIFYING ABOMINABLE DATA-STREAM... //"
)

private const val NO_KEY_MESSAGE =
    "// HERESY BLOCKED: NO COGITATOR KEY // CONFIGURE SILICA ANIMUS CREDENTIALS IN RITES."

private val URL_REGEX = Regex("""https?://[^\s<>"')\]]+""")
/** [MM:SS] or [H:MM:SS] */
private val STAMP_REGEX = Regex("""\[(?:(\d{1,2}):)?(\d{1,3}):(\d{2})]""")
private val TOKEN_REGEX = Regex("""${URL_REGEX.pattern}|${STAMP_REGEX.pattern}""")

private fun stampToMs(m: MatchResult): Long {
    val h = m.groupValues[1].toLongOrNull() ?: 0L
    val min = m.groupValues[2].toLong()
    val sec = m.groupValues[3].toLong()
    return ((h * 60 + min) * 60 + sec) * 1000L
}

/**
 * Monospace terminal text. With [linksEnabled], http(s) URLs are underlined phosphor green and open via
 * Intent.ACTION_VIEW. When [onTimestamp] is set, [MM:SS] / [HH:MM:SS] markers become amber clickable
 * spans that report their position in milliseconds.
 */
@Composable
fun LinkifiedTerminalText(
    text: String, linksEnabled: Boolean, modifier: Modifier = Modifier, onTimestamp: ((Long) -> Unit)? = null
) {
    val ctx = LocalContext.current
    val currentOnTimestamp by rememberUpdatedState(onTimestamp)
    val annotated = remember(text, linksEnabled, onTimestamp != null) {
        buildAnnotatedString {
            var last = 0
            for (m in TOKEN_REGEX.findAll(text)) {
                val isUrl = m.value.startsWith("http")
                // Trailing sentence punctuation is not part of a URL.
                val token = if (isUrl) m.value.trimEnd('.', ',', ';', ':', '!', '?') else m.value
                append(text.substring(last, m.range.first))
                last = m.range.first + token.length
                if (isUrl) {
                    if (linksEnabled) {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = token,
                                styles = TextLinkStyles(
                                    SpanStyle(color = PhosphorGreen, textDecoration = TextDecoration.Underline)
                                )
                            ) {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(token)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        ) { append(token) }
                    } else append(token)
                } else if (onTimestamp != null) {
                    val ms = stampToMs(STAMP_REGEX.matchEntire(token)!!)
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = token,
                            styles = TextLinkStyles(
                                SpanStyle(color = Grim.Amber, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)
                            )
                        ) { currentOnTimestamp?.invoke(ms) }
                    ) { append(token) }
                } else append(token)
            }
            append(text.substring(last))
        }
    }
    Text(
        annotated, modifier, color = Cream, fontFamily = FontFamily.Monospace,
        fontSize = 12.sp, lineHeight = 17.sp
    )
}
@Composable
private fun IntelButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, border: Color = Brass, selected: Boolean = false
) {
    Box(
        modifier.background(if (selected) PhosphorGreen.copy(alpha = 0.15f) else Grim.Card).border(1.5.dp, border).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = if (selected) PhosphorGreen else Cream, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CrimsonPlate(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(Color(0xFF2A1214)).border(1.5.dp, Crimson).padding(14.dp)) {
        Text(
            message, color = CrimsonText, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, lineHeight = 17.sp
        )
    }
}

/** Full-screen Silica Animus overlay: obsidian field, double brass border. */
@Composable
fun AbominableIntelModal(
    voxDigest: String?, state: IntelState, depth: SummaryDepth, onDepthChange: (SummaryDepth) -> Unit,
    onGenerate: () -> Unit, onSeek: (Long) -> Unit, onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val hasKey = remember { PollPrefs.geminiKey(ctx).isNotBlank() }
    val linksOn = remember { PollPrefs.hyperlinks(ctx) }

    // No auto-scan: the cached digest for (videoId, depth) is supplied via voxDigest; Gemini runs only on explicit tap.
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Obsidian).systemBarsPadding().padding(6.dp)
                .border(2.dp, Brass).padding(4.dp).border(1.dp, Brass).padding(14.dp)
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "// ABOMINABLE INTELLIGENCE // SILICA ANIMUS", color = PhosphorGreen,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryDepth.entries.forEach { d ->
                        val on = d == depth
                        IntelButton(
                            "[ ${d.label} ]", { onDepthChange(d) }, Modifier.weight(1f),
                            border = if (on) PhosphorGreen else Brass, selected = on
                        )
                    }
                }
                val scanning = state is IntelState.Loading
                IntelButton(
                    if (voxDigest != null) "[ RE-SCAN ]" else "[ INITIATE SCAN ]",
                    { if (hasKey && !scanning) onGenerate() }, Modifier.fillMaxWidth().alpha(if (hasKey && !scanning) 1f else 0.4f),
                    border = PhosphorGreen
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !hasKey -> CrimsonPlate(NO_KEY_MESSAGE)
                        state is IntelState.Loading -> Ritual()
                        state is IntelState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CrimsonPlate(state.message)
                        }
                        voxDigest != null -> Column(
                            Modifier.fillMaxSize().border(1.dp, Brass).background(Grim.Card)
                                .verticalScroll(rememberScrollState()).padding(12.dp)
                        ) { LinkifiedTerminalText(voxDigest, linksOn, onTimestamp = onSeek) }
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "SELECT SCAN DEPTH AND INITIATE ANALYSIS", color = Brass, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                IntelButton("[ SEAL ARCHIVE / CLOSE ]", onClose, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Ritual() {
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(900)
            idx = (idx + 1) % RITUAL_LINES.size
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            RITUAL_LINES[idx], color = PhosphorGreen, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center
        )
    }
}
