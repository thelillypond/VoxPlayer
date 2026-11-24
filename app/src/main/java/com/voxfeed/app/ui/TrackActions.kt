package com.voxfeed.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.voxfeed.app.AudioSpectrum
import com.voxfeed.app.data.MusicDao
import com.voxfeed.app.data.MusicPlaylistEntity
import com.voxfeed.app.data.MusicTrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private val CogPhosphor = Color(0xFF39FF14)
private val CogSlate = Color(0xFF141712)
private val CogBrass = Color(0xFFD4AF37)

val GENRE_PRESETS = listOf("DNB", "JUNGLE", "REGGAE", "LORE", "SYNTH")

/** CogPhosphor-green star shown beside favourited tracks. */
@Composable
internal fun FavStar(isFavorite: Boolean) {
    if (isFavorite) Text("★", color = CogPhosphor, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 13.sp, modifier = Modifier.padding(end = 4.dp))
}

/** Compact tappable genre tag; tapping filters the list to that genre. */
@Composable
internal fun GenrePill(genre: String, onClick: () -> Unit) {
    val none = genre == MusicDao.GENRE_NONE
    if (none) {
        Text("[ ? ]", color = Waaagh.Dim, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1, modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 2.dp))
        return
    }
    val c = if (none) Waaagh.Dim else CogBrass
    Text(
        genre, color = c, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 8.sp, maxLines = 1,
        modifier = Modifier.border(1.dp, c).clickable(onClick = onClick).padding(horizontal = 3.dp)
    )
}

/** Active Queue playing-row underlay: 20 flat-topped bars driven live by [AudioSpectrum]. */
@Composable
internal fun Modifier.equalizerUnderlay(): Modifier {
    val bands by AudioSpectrum.bands.collectAsState()
    return this.drawBehind {
        val n = AudioSpectrum.BANDS
        val gap = 2.dp.toPx()
        val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val b = bands
        for (i in 0 until n) {
            val h = (b.getOrElse(i) { 0f }.coerceIn(0f, 1f) * size.height)
            if (h > 0f) drawRect(CogPhosphor, Offset(i * (barW + gap), size.height - h), Size(barW, h), alpha = 0.14f)
        }
    }
}
private enum class Stage { MENU, MOVE, COPY, GENRE, PURGE }

@Composable
private fun CogDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(CogSlate).border(1.dp, CogBrass).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content
        )
    }
}

/** Long-press menu for one track row. All status feedback goes through [CogitatorBanner]. */
@Composable
internal fun TrackActionMenu(
    track: MusicTrackEntity, playlists: List<MusicPlaylistEntity>, counts: Map<Long, Int>,
    dao: MusicDao, scope: CoroutineScope, onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    var stage by remember { mutableStateOf(Stage.MENU) }
    val plName = playlists.firstOrNull { it.id == track.playlistId }?.name ?: "PLAYLIST"

    fun done(msg: String) { CogitatorBanner.show(msg); onDismiss() }

    CogDialog(onDismiss) {
        Text(track.title, color = CogPhosphor, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        when (stage) {
            Stage.MENU -> {
                IronButton(if (track.isFavorite) "[ ★ UNFAVOURITE ]" else "[ ★ FAVOURITE ]", {
                    scope.launch {
                        dao.setFavoriteBySource(track.sourceUrl, !track.isFavorite)
                        done(if (track.isFavorite) "REMOVED FROM FAVOURITES" else "★ ADDED TO FAVOURITES")
                    }
                }, Modifier.fillMaxWidth(), accent = true)
                IronButton("[ ASSIGN GENRE ]", { stage = Stage.GENRE }, Modifier.fillMaxWidth())
                IronButton("[ MOVE TO ARCHIVE... ]", { stage = Stage.MOVE }, Modifier.fillMaxWidth())
                IronButton("[ COPY TO ARCHIVE... ]", { stage = Stage.COPY }, Modifier.fillMaxWidth())
                IronButton("[ REMOVE FROM THIS PLAYLIST ]", {
                    scope.launch { dao.deleteTrack(track.id); done("REMOVED FROM ${plName.uppercase()}") }
                }, Modifier.fillMaxWidth())
                IronButton("[ PURGE PERMANENTLY ]", { stage = Stage.PURGE }, Modifier.fillMaxWidth())
                IronButton("[ CLOSE ]", onDismiss, Modifier.fillMaxWidth())
            }
            Stage.MOVE, Stage.COPY -> {
                val move = stage == Stage.MOVE
                val targets = playlists.filter { it.id != track.playlistId }
                Text("TARGET ARCHIVE (${if (move) "MOVE" else "COPY"}):", color = Waaagh.Dim, fontFamily = Mono, fontSize = 10.sp)
                if (targets.isEmpty()) Text("NO OTHER PLAYLISTS - CREATE ONE FIRST", color = Waaagh.Alert, fontFamily = Mono, fontSize = 11.sp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(targets, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().border(1.dp, Waaagh.Dim).clickable {
                                scope.launch {
                                    val added = dao.copyTrackTo(track.id, p.id, move)
                                    done((if (move) "MOVED" else "COPIED") + " TO ${p.name.uppercase()}" + if (!added) " (ALREADY THERE)" else "")
                                }
                            }.padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(p.name.uppercase(), Modifier.weight(1f), color = Color.White, fontFamily = Heavy, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("(${counts[p.id] ?: 0})", color = Waaagh.Hazard, fontFamily = Mono, fontSize = 12.sp)
                        }
                    }
                }
                IronButton("[ BACK ]", { stage = Stage.MENU }, Modifier.fillMaxWidth())
            }
            Stage.GENRE -> {
                var custom by remember { mutableStateOf("") }
                fun apply(g: String) {
                    val v = g.trim().uppercase().ifBlank { MusicDao.GENRE_NONE }.take(16)
                    scope.launch { dao.setGenreBySource(track.sourceUrl, v); done("GENRE: $v") }
                }
                Text("ASSIGN GENRE:", color = Waaagh.Dim, fontFamily = Mono, fontSize = 10.sp)
                (GENRE_PRESETS + MusicDao.GENRE_NONE).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { g -> Chip(g, track.genre == g) { apply(g) } }
                    }
                }
                NameField(custom, { custom = it }, "CUSTOM GENRE")
                IronButton("[ APPLY CUSTOM ]", { apply(custom) }, Modifier.fillMaxWidth(), enabled = custom.isNotBlank(), accent = true)
                IronButton("[ BACK ]", { stage = Stage.MENU }, Modifier.fillMaxWidth())
            }
            Stage.PURGE -> {
                Text(
                    if (track.localUri != null) "Delete the cached audio file and remove this track from ALL playlists?"
                    else "Remove this track from ALL playlists?",
                    color = Color.White, fontFamily = Mono, fontSize = 12.sp
                )
                IronButton("[ CONFIRM PURGE ]", {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { track.localUri?.let { deleteLocalFile(ctx, it) } ?: true }
                        dao.deleteEverywhere(track.sourceUrl, track.localUri.orEmpty())
                        done(if (ok) "TRACK PURGED PERMANENTLY" else "FILE FAULT: COULD NOT DELETE FILE (TRACK REMOVED)")
                    }
                }, Modifier.fillMaxWidth(), accent = true)
                IronButton("[ CANCEL ]", { stage = Stage.MENU }, Modifier.fillMaxWidth())
            }
        }
    }
}
