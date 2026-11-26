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


val GENRE_PRESETS = listOf("DNB", "JUNGLE", "REGGAE", "PODCAST", "SYNTH")

/** Accent-green star shown beside favourited tracks. */
@Composable
internal fun FavStar(isFavorite: Boolean) {
    if (isFavorite) Text("★", color = Palette.Accent, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 13.sp, modifier = Modifier.padding(end = 4.dp))
}

/** Compact tappable genre tag; tapping filters the list to that genre. */
@Composable
internal fun GenrePill(genre: String, onClick: () -> Unit) {
    val none = genre == MusicDao.GENRE_NONE
    if (none) {
        Text("?", color = Palette.Dim, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1, modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 2.dp))
        return
    }
    val c = if (none) Palette.Dim else Palette.Amber
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
            if (h > 0f) drawRect(Palette.Accent, Offset(i * (barW + gap), size.height - h), Size(barW, h), alpha = 0.14f)
        }
    }
}
private enum class Stage { MENU, MOVE, COPY, GENRE, PURGE }

/** Long-press menu for one track row. All status feedback goes through [StatusBanner]. */
@Composable
internal fun TrackActionMenu(
    track: MusicTrackEntity, playlists: List<MusicPlaylistEntity>, counts: Map<Long, Int>,
    dao: MusicDao, scope: CoroutineScope, onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    var stage by remember { mutableStateOf(Stage.MENU) }
    val plName = playlists.firstOrNull { it.id == track.playlistId }?.name ?: "Playlist"

    fun done(msg: String) { StatusBanner.show(msg); onDismiss() }

    AppDialog(onDismiss) {
        Text(track.title, color = Palette.Accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        when (stage) {
            Stage.MENU -> {
                ActionButton(if (track.isFavorite) "★ Unfavourite" else "★ Favourite", {
                    scope.launch {
                        dao.setFavoriteBySource(track.sourceUrl, !track.isFavorite)
                        done(if (track.isFavorite) "Removed from favourites" else "★ Added to favourites")
                    }
                }, Modifier.fillMaxWidth(), accent = true)
                ActionButton("Assign Genre", { stage = Stage.GENRE }, Modifier.fillMaxWidth())
                ActionButton("Move To Playlist...", { stage = Stage.MOVE }, Modifier.fillMaxWidth())
                ActionButton("Copy To Playlist...", { stage = Stage.COPY }, Modifier.fillMaxWidth())
                ActionButton("Remove From This Playlist", {
                    scope.launch { dao.deleteTrack(track.id); done("Removed from ${plName}") }
                }, Modifier.fillMaxWidth())
                ActionButton("Delete Permanently", { stage = Stage.PURGE }, Modifier.fillMaxWidth())
                ActionButton("Close", onDismiss, Modifier.fillMaxWidth())
            }
            Stage.MOVE, Stage.COPY -> {
                val move = stage == Stage.MOVE
                val targets = playlists.filter { it.id != track.playlistId }
                Text("Target playlist (${if (move) "move" else "copy"}):", color = Palette.Dim, fontFamily = Mono, fontSize = 10.sp)
                if (targets.isEmpty()) Text("No other playlists. Create one first.", color = Palette.Alert, fontFamily = Mono, fontSize = 11.sp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(targets, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().border(1.dp, Palette.Dim).clickable {
                                scope.launch {
                                    val added = dao.copyTrackTo(track.id, p.id, move)
                                    done((if (move) "Moved" else "Copied") + " to ${p.name}" + if (!added) " (already there)" else "")
                                }
                            }.padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(p.name, Modifier.weight(1f), color = Color.White, fontFamily = Heavy, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("(${counts[p.id] ?: 0})", color = Palette.Amber, fontFamily = Mono, fontSize = 12.sp)
                        }
                    }
                }
                ActionButton("Back", { stage = Stage.MENU }, Modifier.fillMaxWidth())
            }
            Stage.GENRE -> {
                var custom by remember { mutableStateOf("") }
                fun apply(g: String) {
                    val v = g.trim().uppercase().ifBlank { MusicDao.GENRE_NONE }.take(16)
                    scope.launch { dao.setGenreBySource(track.sourceUrl, v); done("Genre: $v") }
                }
                Text("Assign Genre:", color = Palette.Dim, fontFamily = Mono, fontSize = 10.sp)
                (GENRE_PRESETS + MusicDao.GENRE_NONE).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { g -> Chip(g, track.genre == g) { apply(g) } }
                    }
                }
                NameField(custom, { custom = it }, "Custom Genre")
                ActionButton("Apply Custom", { apply(custom) }, Modifier.fillMaxWidth(), enabled = custom.isNotBlank(), accent = true)
                ActionButton("Back", { stage = Stage.MENU }, Modifier.fillMaxWidth())
            }
            Stage.PURGE -> {
                Text(
                    if (track.localUri != null) "Delete the cached audio file and remove this track from ALL playlists?"
                    else "Remove this track from ALL playlists?",
                    color = Color.White, fontFamily = Mono, fontSize = 12.sp
                )
                ActionButton("Confirm Delete", {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { track.localUri?.let { deleteLocalFile(ctx, it) } ?: true }
                        dao.deleteEverywhere(track.sourceUrl, track.localUri.orEmpty())
                        done(if (ok) "Track deleted" else "File error: could not delete file (track removed)")
                    }
                }, Modifier.fillMaxWidth(), accent = true)
                ActionButton("Cancel", { stage = Stage.MENU }, Modifier.fillMaxWidth())
            }
        }
    }
}
