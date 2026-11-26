package com.voxfeed.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.voxfeed.app.data.BackupManager
import com.voxfeed.app.data.ChannelEntity
import com.voxfeed.app.data.SubFeedEntity
import com.voxfeed.app.worker.PollPrefs
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
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
    val manager = remember { BackupManager(ctx) }

    var intervalHours by remember { mutableStateOf(PollPrefs.pollIntervalHours(ctx)) }
    var lookback by remember { mutableStateOf(PollPrefs.lookbackWindowHours(ctx)) }
    var wifiOnly by remember { mutableStateOf(PollPrefs.wifiOnly(ctx)) }
    var alerts by remember { mutableStateOf(PollPrefs.voxAlertsEnabled(ctx)) }
    var aiKey by remember { mutableStateOf(PollPrefs.geminiKey(ctx)) }
    var showKey by remember { mutableStateOf(false) }
    var hyperlinks by remember { mutableStateOf(PollPrefs.hyperlinks(ctx)) }
    var status by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var channelDeleteTarget by remember { mutableStateOf<ChannelEntity?>(null) }
    val lastSync = PollPrefs.lastSynced(ctx)

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        alerts = granted
        PollPrefs.setVoxAlertsEnabled(ctx, granted)
        PollPrefs.schedule(ctx)
        status = if (granted) "Notifications enabled" else "Notification permission was denied"
    }

    fun run(block: suspend () -> String) {
        if (working) return
        working = true; status = "Working..."
        scope.launch {
            status = try { block() } catch (e: Exception) { "Error: ${e.message ?: e.javaClass.simpleName}" }
            working = false
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) run { manager.export(uri) } else status = "Export cancelled" }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) run { manager.import(uri) } else status = "Import cancelled" }

    AppFormDialog(onDismiss) {
        AppLabel("Settings", Palette.Text, 18.sp)
        ActionButton(
            if (syncing) "Refreshing..." else "Refresh Feeds Now",
            onSync, Modifier.fillMaxWidth(), enabled = !syncing, accent = true
        )

        AppLabel("Notifications & Refresh", Palette.Amber, 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("New-video alerts: ${if (alerts) "On" else "Off"}", alerts) {
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
            Chip("Wi-Fi only: ${if (wifiOnly) "On" else "Off"}", wifiOnly) {
                wifiOnly = !wifiOnly
                PollPrefs.setWifiOnly(ctx, wifiOnly)
                PollPrefs.schedule(ctx)
            }
        }
        AppLabel("Check every", Palette.Dim, 11.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PollPrefs.INTERVAL_HOURS.forEach { h ->
                Chip(if (h == 0) "Off" else "${h}h", intervalHours == h) {
                    intervalHours = h
                    PollPrefs.setPollIntervalHours(ctx, h)
                    PollPrefs.schedule(ctx)
                }
            }
        }
        AppLabel("Show videos from the last", Palette.Dim, 11.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PollPrefs.LOOKBACK_OPTIONS.forEach { (h, label) ->
                Chip(label, lookback == h) {
                    lookback = h
                    onLookbackChange(h)
                    PollPrefs.schedule(ctx)
                }
            }
        }
        Text(
            "Last refreshed: " + if (lastSync <= 0) "never" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastSync)),
            color = Palette.Dim, fontSize = 12.sp
        )

        AppLabel("Subscribed Channels", Palette.Amber, 13.sp)
        if (channels.isEmpty()) Text("No channels yet.", color = Palette.Dim, fontSize = 12.sp)
        else Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            val chipShape = RoundedCornerShape(6.dp)
            channels.forEach { ch ->
                val isHidden = ch.channelId in hiddenChannels
                val group = subFeeds.firstOrNull { it.id == ch.subFeedId }?.name ?: "Ungrouped"
                val name = ch.title.takeIf { it.isNotBlank() && !it.equals("Videos", true) }
                    ?: videoChannelTitles[ch.channelId]?.takeIf { it.isNotBlank() && !it.equals("Videos", true) }
                    ?: ch.channelId
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            name, color = if (isHidden) Palette.Dim else Palette.Text, fontWeight = FontWeight.Bold,
                            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(group, color = Palette.Dim, fontSize = 11.sp, maxLines = 1)
                    }
                    val bellOn = ch.alertsEnabled
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = if (bellOn) "Alerts on" else "Alerts muted",
                        tint = if (bellOn) Palette.Accent else Palette.Dim,
                        modifier = Modifier.size(32.dp)
                            .clickable { onToggleChannelAlerts(ch.channelId, !bellOn) }
                            .padding(4.dp)
                            .drawWithContent {
                                drawContent()
                                if (!bellOn) drawLine(Palette.Alert, Offset(0f, 0f), Offset(size.width, size.height), 2.dp.toPx())
                            }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (isHidden) "Hidden" else "Hide",
                        Modifier.border(1.dp, if (isHidden) Palette.Amber else Palette.Border, chipShape)
                            .clickable { onToggleHidden(ch.channelId) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        color = if (isHidden) Palette.Amber else Palette.Text,
                        fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Remove",
                        Modifier.border(1.dp, Palette.Alert, chipShape)
                            .clickable { channelDeleteTarget = ch }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        color = Palette.Alert, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1
                    )
                }
            }
        }

        AppLabel("AI Summary (Gemini API key)", Palette.Amber, 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val fieldShape = RoundedCornerShape(8.dp)
            BasicTextField(
                value = aiKey,
                onValueChange = { aiKey = it; PollPrefs.setGeminiKey(ctx, it) },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                textStyle = TextStyle(color = Palette.Text, fontFamily = Mono, fontSize = 13.sp),
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier.weight(1f).background(Palette.Surface, fieldShape)
                    .border(1.dp, Palette.Border, fieldShape).padding(horizontal = 10.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    if (aiKey.isEmpty()) Text("Google AI Studio key", color = Palette.Dim, fontFamily = Mono, fontSize = 12.sp)
                    inner()
                }
            )
            Chip(if (showKey) "Hide" else "Show", showKey) { showKey = !showKey }
        }
        Chip("Clickable links: ${if (hyperlinks) "On" else "Off"}", hyperlinks) {
            hyperlinks = !hyperlinks
            PollPrefs.setHyperlinks(ctx, hyperlinks)
        }

        AppLabel("Backup & Restore", Palette.Amber, 13.sp)
        ActionButton("Export Backup (.json)", { exportLauncher.launch("voxplayer-backup.json") }, Modifier.fillMaxWidth(), enabled = !working)
        ActionButton("Import Backup (.json)", { importLauncher.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = !working)
        if (status.isNotEmpty()) Text(status, color = Palette.Accent, fontSize = 12.sp)

        ActionButton("Close", onDismiss, Modifier.fillMaxWidth())
    }

    channelDeleteTarget?.let { c ->
        AppDialog(onDismiss = { channelDeleteTarget = null }) {
            AppLabel("Remove channel?", Palette.Alert, 16.sp)
            Text(
                "Unsubscribe from '${c.title}' and remove its videos that are not downloaded?",
                color = Palette.Text, fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Cancel", { channelDeleteTarget = null }, Modifier.weight(1f))
                ActionButton("Remove", { onDeleteChannel(c.channelId); channelDeleteTarget = null }, Modifier.weight(1f), accent = true)
            }
        }
    }
}
