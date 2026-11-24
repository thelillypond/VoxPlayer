package com.voxfeed.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest

/** App-wide bus for cogitator status banners; safe to call from any thread / non-UI code. */
object CogitatorBanner {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events = _events.asSharedFlow()
    fun show(message: String) { _events.tryEmit(message) }
}

/** Overlay host: place last inside a full-screen Box. Shows each banner for 2 seconds. */
@Composable
fun CogitatorBannerHost(modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        CogitatorBanner.events.collectLatest { msg ->
            current = msg
            delay(2000)
            current = null
        }
    }
    Box(modifier.fillMaxSize().navigationBarsPadding().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
        current?.let {
            val accent = if (it.contains("FAULT")) Color(0xFFFF3B30) else Color(0xFF39FF14)
            Text(
                it,
                Modifier.fillMaxWidth().background(Color(0xFF141712)).border(1.dp, if (accent == Color(0xFF39FF14)) Color(0xFFD4AF37) else accent)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                fontSize = 11.sp, maxLines = 3
            )
        }
    }
}
