package com.voxfeed.app

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Zero-permission spectrum tap. [sink] is attached to ExoPlayer's DefaultAudioSink through a TeeAudioProcessor, so the
 * decoded PCM is read directly (no RECORD_AUDIO / Visualizer). Each ~16ms the last [WINDOW] mono samples are reduced to
 * [BANDS] log-spaced Goertzel energies, then smoothed (instant attack, exponential decay) and published as 0f..1f.
 */
object AudioSpectrum {
    const val BANDS = 20
    private const val WINDOW = 1024
    private const val MIN_INTERVAL_NS = 16_000_000L // ~60 fps cap
    private const val DECAY = 0.86f

    private val _bands = MutableStateFlow(FloatArray(BANDS))
    val bands: StateFlow<FloatArray> = _bands

    private val lock = Any()
    private val ring = FloatArray(WINDOW)
    private var ringPos = 0
    private var filled = 0
    private var sampleRate = 44_100
    private var channels = 2
    private var pcm16 = false
    private var lastNs = 0L
    private var coeffs = FloatArray(BANDS)
    private var coeffRate = 0
    private val hann = FloatArray(WINDOW) { (0.5 - 0.5 * cos(2.0 * PI * it / (WINDOW - 1))).toFloat() }
    private val level = FloatArray(BANDS)
    private val scratch = FloatArray(WINDOW)
    private val main = Handler(Looper.getMainLooper())

    val sink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            synchronized(lock) {
                sampleRate = sampleRateHz; channels = channelCount.coerceAtLeast(1)
                pcm16 = encoding == C.ENCODING_PCM_16BIT
            }
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (!pcm16) return
            val bb = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            synchronized(lock) {
                val ch = channels
                while (bb.remaining() >= 2 * ch) {
                    var sum = 0
                    for (c in 0 until ch) sum += bb.short.toInt()
                    ring[ringPos] = sum / (ch * 32768f)
                    ringPos = (ringPos + 1) % WINDOW
                    if (filled < WINDOW) filled++
                }
                val now = System.nanoTime()
                if (filled < WINDOW || now - lastNs < MIN_INTERVAL_NS) return
                lastNs = now
                analyze()
            }
        }
    }

    /** Called under [lock]. */
    private fun analyze() {
        if (coeffRate != sampleRate) {
            // 20 log-spaced centres, 45 Hz .. min(16 kHz, 0.45 * fs)
            val hi = minOf(16_000.0, sampleRate * 0.45)
            for (i in 0 until BANDS) {
                val f = 45.0 * (hi / 45.0).pow(i / (BANDS - 1.0))
                coeffs[i] = (2.0 * cos(2.0 * PI * f / sampleRate)).toFloat()
            }
            coeffRate = sampleRate
        }
        for (i in 0 until WINDOW) scratch[i] = ring[(ringPos + i) % WINDOW] * hann[i]
        for (b in 0 until BANDS) {
            val k = coeffs[b]
            var s1 = 0f; var s2 = 0f
            for (i in 0 until WINDOW) { val s0 = scratch[i] + k * s1 - s2; s2 = s1; s1 = s0 }
            val power = (s1 * s1 + s2 * s2 - k * s1 * s2).coerceAtLeast(0f)
            val mag = sqrt(power) / (WINDOW / 4f)            // ~amplitude of a sine at this band
            val db = 20f * log10(mag + 1e-6f)
            val v = ((db + 54f) / 54f).coerceIn(0f, 1f)       // -54 dBFS..0 dBFS
            level[b] = if (v > level[b]) v else level[b] * DECAY
        }
        _bands.value = level.copyOf()
    }

    private val decayTick = object : Runnable {
        override fun run() {
            var peak = 0f
            synchronized(lock) {
                for (i in 0 until BANDS) { level[i] *= DECAY; if (level[i] < 0.004f) level[i] = 0f; peak = maxOf(peak, level[i]) }
                _bands.value = level.copyOf()
            }
            if (peak > 0f) main.postDelayed(this, 33)
        }
    }

    /** Playback paused/stopped: bands ease down to 0f. */
    fun startDecay() { main.removeCallbacks(decayTick); main.post(decayTick) }

    /** Playback running: buffers drive the bands again. */
    fun stopDecay() { main.removeCallbacks(decayTick) }
}
