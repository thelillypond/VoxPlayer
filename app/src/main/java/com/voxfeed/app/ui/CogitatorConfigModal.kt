package com.voxfeed.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.voxfeed.app.data.ChannelEntity
import com.voxfeed.app.data.SubFeedEntity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.voxfeed.app.GrimButton
import com.voxfeed.app.GrimChip
import com.voxfeed.app.GrimDialog
import com.voxfeed.app.GrimLabel
import com.voxfeed.app.data.DataSlateManager
import com.voxfeed.app.worker.PollPrefs
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CogitatorConfigModal(
    syncing: Boolean,
    channels: List<ChannelEntity>,
    subFeeds: List<SubFeedEntity>,
    hiddenChannels: Set<String>,
    videoChannelTitles: Map<String, String>,
    onToggleHidden: (String) -> Unit,
    onToggleChannelAlerts: (String, Boolean) -> Unit,
    onLookbackChange: (Int) -> Unit,
    onDeleteChannel: (String) -> Unit,
    onSync: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { DataSlateManager(ctx) }

    var intervalHours by remember { mutableStateOf(PollPrefs.pollIntervalHours(ctx)) }
    var lookback by remember { mutableStateOf(PollPrefs.lookbackWindowHours(ctx)) }
    var wifiOnly by remember { mutableStateOf(PollPrefs.wifiOnly(ctx)) }
    var alerts by remember { mutableStateOf(PollPrefs.voxAlertsEnabled(ctx)) }
    var aiKey by remember { mutableStateOf(PollPrefs.geminiKey(ctx)) }
    var showKey by remember { mutableStateOf(false) }
    var hyperlinks by remember { mutableStateOf(PollPrefs.hyperlinks(ctx)) }
    var status by remember { mutableStateOf("AWAITING COMMAND") }
    var working by remember { mutableStateOf(false) }
    var channelDeleteTarget by remember { mutableStateOf<ChannelEntity?>(null) }
    val lastSync = PollPrefs.lastSynced(ctx)

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        alerts = granted
        PollPrefs.setVoxAlertsEnabled(ctx, granted)
        PollPrefs.schedule(ctx)
        status = if (granted) "VOX-ALERTS ENABLED" else "NOTIFICATION RITE DENIED BY SYSTEM"
    }

    fun run(block: suspend () -> String) {
        if (working) return
        working = true; status = "PROCESSING..."
        scope.launch {
            status = try { block() } catch (e: Exception) { "FAULT: ${e.message ?: e.javaClass.simpleName}".uppercase() }
            working = false
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) run { manager.export(uri) } else status = "EXPORT ABORTED" }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) run { manager.import(uri) } else status = "IMPORT ABORTED" }

    GrimDialog(onDismiss) {
        GrimLabel("// COGITATOR CONFIGURATION", Grim.Phosphor, 13.sp)
        GrimButton(
            if (syncing) "[ RITES IN PROGRESS... ]" else "[ PERFORM RITES — SYNC FEEDS NOW ]",
            onSync, Modifier.fillMaxWidth(), enabled = !syncing
        )

        GrimLabel("VOX ALERTS & SCRAPE WINDOW", Grim.BrassBright, 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GrimChip("[ VOX-ALERTS: ${if (alerts) "ON" else "OFF"} ]", alerts) {
                if (alerts) {
                    alerts = false; PollPrefs.setVoxAlertsEnabled(ctx, false); PollPrefs.schedule(ctx)
                } else if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    alerts = true; PollPrefs.setVoxAlertsEnabled(ctx, true); PollPrefs.schedule(ctx)
                }
            }
            GrimChip("[ WI-FI ONLY: ${if (wifiOnly) "ON" else "OFF"} ]", wifiOnly) {
                wifiOnly = !wifiOnly
                PollPrefs.setWifiOnly(ctx, wifiOnly)
                PollPrefs.schedule(ctx)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GrimLabel("POLL EVERY", Grim.Cream, 10.sp)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PollPrefs.INTERVAL_HOURS.forEach { h ->
                        GrimChip(if (h == 0) "OFF" else "${h}H", intervalHours == h) {
                            intervalHours = h
                            PollPrefs.setPollIntervalHours(ctx, h)
                            PollPrefs.schedule(ctx)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GrimLabel("SCRAPE WINDOW", Grim.Cream, 10.sp)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PollPrefs.LOOKBACK_OPTIONS.forEach { (h, label) ->
                        GrimChip(label, lookback == h) {
                            lookback = h
                            onLookbackChange(h)
                            PollPrefs.schedule(ctx)
                        }
                    }
                }
            }
        }
        GrimLabel(
            "LAST SYNCED: " + if (lastSync <= 0) "NEVER" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastSync)),
            Grim.Cream, 10.sp
        )

        GrimLabel("// SUBSCRIBED VOX-CHANNELS", Grim.BrassBright, 11.sp)
        if (channels.isEmpty()) GrimLabel("NO CHANNELS EXTRACTED", Grim.Dim, 10.sp)
        else Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
            val chipShape = RoundedCornerShape(2.dp)
            channels.forEach { ch ->
                val isHidden = ch.channelId in hiddenChannels
                val cat = subFeeds.firstOrNull { it.id == ch.subFeedId }?.name?.uppercase() ?: "UNSORTED"
                val name = ch.title.takeIf { it.isNotBlank() && !it.equals("Videos", true) }
                    ?: videoChannelTitles[ch.channelId]?.takeIf { it.isNotBlank() && !it.equals("Videos", true) }
                    ?: ch.channelId
                Row(
                    Modifier.fillMaxWidth()
                        .drawBehind {
                            drawLine(Color(0xFF8C7832), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                        }
                        .padding(vertical = 4.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name, Modifier.weight(1f, fill = false),
                            color = if (isHidden) Grim.Dim else Color(0xFFD8D5A8), fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            " [$cat]", color = Color(0xFF8C7832), fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp, maxLines = 1
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val bellOn = ch.alertsEnabled
                        Icon(
                            Icons.Filled.Notifications,
                            contentDescription = if (bellOn) "Vox alerts on" else "Vox alerts muted",
                            tint = if (bellOn) Grim.Phosphor else Grim.Dim,
                            modifier = Modifier.size(26.dp)
                                .clickable { onToggleChannelAlerts(ch.channelId, !bellOn) }
                                .padding(4.dp)
                                .drawWithContent {
                                    drawContent()
                                    if (!bellOn) drawLine(Color(0xFFB3202A), Offset(0f, 0f), Offset(size.width, size.height), 2.dp.toPx())
                                }
                        )
                        Text(
                            if (isHidden) "[ HIDDEN ]" else "[ HIDE ]",
                            Modifier
                                .border(1.dp, if (isHidden) Grim.Amber else Grim.BrassBright, chipShape)
                                .background(if (isHidden) Grim.Amber.copy(alpha = 0.18f) else Color(0xFF2A2412), chipShape)
                                .clickable { onToggleHidden(ch.channelId) }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            color = if (isHidden) Grim.Amber else Grim.Cream,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1
                        )
                        Text(
                            "[ DEL ]",
                            Modifier.border(1.dp, Color(0xFFB3202A), chipShape).background(Color(0xFF2A1214), chipShape)
                                .clickable { channelDeleteTarget = ch }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            color = Color(0xFFFF6B6B),
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1
                        )
                    }
                }
            }
        }
        GrimLabel("ABOMINABLE INTELLIGENCE KEY", Grim.BrassBright, 11.sp)
        Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val fieldShape = RoundedCornerShape(2.dp)
            androidx.compose.foundation.text.BasicTextField(
                value = aiKey,
                onValueChange = { aiKey = it; PollPrefs.setGeminiKey(ctx, it) },
                singleLine = true,
                visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrect = false
                ),
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color(0xFFD8D5A8), fontFamily = FontFamily.Monospace, fontSize = 12.sp
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFFD8D5A8)),
                modifier = Modifier.weight(1f).background(Color(0xFF0A0D0A), fieldShape)
                    .border(1.dp, Color(0xFF8C7832), fieldShape).padding(horizontal = 10.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    if (aiKey.isEmpty()) Text(
                        "GOOGLE AI STUDIO KEY", color = Grim.Dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    )
                    inner()
                }
            )
            GrimChip(if (showKey) "[ HIDE ]" else "[ SHOW ]", showKey) { showKey = !showKey }
        }
        GrimChip("[ HYPERLINKS: ${if (hyperlinks) "ON" else "OFF"} ]", hyperlinks) {
            hyperlinks = !hyperlinks
            PollPrefs.setHyperlinks(ctx, hyperlinks)
        }

        GrimLabel("PORTABLE DATA-SLATE", Grim.BrassBright, 11.sp)
        GrimButton("[ EXPORT DATA-SLATE (.JSON) ]", { exportLauncher.launch("voxfeed-data-slate.json") },
            Modifier.fillMaxWidth(), enabled = !working)
        GrimButton("[ IMPORT DATA-SLATE / OPML ]", { importLauncher.launch(arrayOf("*/*")) },
            Modifier.fillMaxWidth(), enabled = !working)
        GrimLabel("> $status", Grim.Phosphor, 10.sp)

        GrimButton("[ CLOSE ]", onDismiss, Modifier.fillMaxWidth())
    }

    channelDeleteTarget?.let { c ->
        GrimDialog(onDismiss = { channelDeleteTarget = null }) {
            GrimLabel("// SEVER VOX-LINK //", Grim.Phosphor, 13.sp)
            Text(
                "Unsubscribe from '${c.title}' and purge its unarchived entries?",
                color = Grim.Cream, fontFamily = FontFamily.Monospace, fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GrimButton("[ ABORT ]", { channelDeleteTarget = null }, Modifier.weight(1f))
                GrimButton("[ SEVER ]", { onDeleteChannel(c.channelId); channelDeleteTarget = null },
                    Modifier.weight(1f), borderColor = Color(0xFFB3202A))
            }
        }
    }
}