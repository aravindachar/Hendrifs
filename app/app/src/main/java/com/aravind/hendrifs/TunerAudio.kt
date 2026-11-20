package com.aravind.hendrifs

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A chromatic tuner fed by the amp's USB audio (the amp shows up as a USB microphone; the app only claims its
 * HID interface, so audio stays free). Pitch is found with the YIN method on a background thread.
 */
class TunerAudio(private val context: Context, private val onPitch: (note: String, cents: Int, hz: Double) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var reference = 440.0

    /** The amp's USB input, if Android sees one. */
    fun usbInput(): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
    }

    val isRunning get() = running

    /** Starts listening. Returns false if there is no USB audio input or recording could not start. */
    @SuppressLint("MissingPermission")   // the caller checks RECORD_AUDIO first
    fun start(): Boolean {
        if (running) return true
        val dev = usbInput() ?: return false
        val rate = 48000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 2)
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?: runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 2) }
                .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?: return false
        rec.setPreferredDevice(dev)
        running = true
        thread = Thread({ run(rec, rate) }, "tuner-audio").also { it.start() }
        return true
    }

    fun stop() {
        running = false
        thread?.let { it.interrupt(); it.join(300) }
        thread = null
    }

    private fun run(rec: AudioRecord, rate: Int) {
        val n = 4096                          // ~85 ms window: enough periods for a low E (82 Hz)
        val hop = 2048
        val buf = ShortArray(n)
        val frame = FloatArray(n / 2)          // analysed at half rate: guitar fundamentals are far below 12 kHz
        var filled = 0
        var smooth = 0.0
        var lastEmit = 0L
        try {
            rec.startRecording()
            while (running) {
                val got = rec.read(buf, filled, n - filled)
                if (got <= 0) { if (got < 0) break else continue }
                filled += got
                if (filled < n) continue
                for (i in frame.indices) frame[i] = (buf[2 * i] + buf[2 * i + 1]) / 65536f
                val hz = yin(frame, rate / 2)
                val now = System.currentTimeMillis()
                if (hz > 0) {
                    smooth = if (smooth == 0.0 || abs(hz - smooth) / smooth > 0.03) hz else smooth * 0.6 + hz * 0.4
                    if (now - lastEmit >= 50) { lastEmit = now; emit(smooth) }
                } else if (now - lastEmit >= 300) { lastEmit = now; smooth = 0.0; onPitch("", 0, 0.0) }
                System.arraycopy(buf, hop, buf, 0, n - hop); filled = n - hop
            }
        } catch (_: Exception) {
        } finally {
            runCatching { rec.stop() }; rec.release(); running = false
        }
    }

    private fun emit(hz: Double) {
        val midi = 69 + 12 * log2(hz / reference)
        val nearest = midi.roundToInt()
        val cents = ((midi - nearest) * 100).roundToInt().coerceIn(-50, 50)
        onPitch(NAMES[((nearest % 12) + 12) % 12], cents, hz)
    }

    /** YIN pitch detection; returns Hz, or -1 when there is no clear note (silence, noise). */
    private fun yin(x: FloatArray, rate: Int): Double {
        var rms = 0.0
        for (v in x) rms += v * v
        if (sqrt(rms / x.size) < 0.004) return -1.0          // too quiet to be a string
        val maxLag = rate / 60                                // lowest note ~60 Hz (drop tunings)
        val minLag = rate / 1400
        val half = x.size - maxLag
        val d = DoubleArray(maxLag + 1)
        for (tau in 1..maxLag) {
            var s = 0.0
            for (i in 0 until half) { val diff = x[i] - x[i + tau]; s += diff * diff }
            d[tau] = s
        }
        var run = 0.0
        val cmnd = DoubleArray(maxLag + 1).also { it[0] = 1.0 }
        for (tau in 1..maxLag) { run += d[tau]; cmnd[tau] = if (run == 0.0) 1.0 else d[tau] * tau / run }
        var tau = minLag
        while (tau < maxLag) {
            if (cmnd[tau] < 0.12) { while (tau + 1 < maxLag && cmnd[tau + 1] < cmnd[tau]) tau++; break }
            tau++
        }
        if (tau >= maxLag || cmnd[tau] >= 0.12) return -1.0
        // Parabolic interpolation around the minimum for sub-sample accuracy.
        val a = cmnd[tau - 1]; val b = cmnd[tau]; val c = cmnd[(tau + 1).coerceAtMost(maxLag)]
        val den = a + c - 2 * b
        val shift = if (den != 0.0) (a - c) / (2 * den) else 0.0
        return rate / (tau + shift)
    }

    companion object {
        val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    }
}
