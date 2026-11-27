package com.arin.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

/**
 * Listens for the person starting to speak *while Arin is talking*, so they can interrupt by voice
 * instead of tapping. It records from the communication mic with the phone's hardware echo canceller
 * and noise suppressor on, so Arin's own voice coming back through the speaker is largely removed, then
 * fires [onSpeech] once real, sustained voice energy is heard.
 *
 * Echo cancellation is never perfect over a loud speaker, so the threshold is deliberately high and a
 * run of loud frames is required before it triggers — better to miss an interruption than to cut Arin
 * off when it hears itself.
 */
class SpeechGate(private val onSpeech: () -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread = null
    }

    private fun loop() {
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: SecurityException) { return } // the microphone permission was taken away
        catch (e: Exception) { return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { runCatching { rec.release() }; return }

        val aec = runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
        val ns = runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null }.getOrNull()

        val buf = ShortArray(1600) // ~100ms at 16kHz
        var voiced = 0
        var baseline = 0.0
        var calib = 0
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val s = buf[i].toDouble(); sum += s * s }
                val rms = Math.sqrt(sum / n)
                // First frames: learn Arin's own echo level so the user's voice has to clearly beat it.
                if (calib < CALIB_FRAMES) {
                    baseline = maxOf(baseline, rms)
                    calib++
                    continue
                }
                val threshold = maxOf(THRESHOLD, baseline * MARGIN)
                if (rms > threshold) {
                    voiced++
                    if (voiced >= FRAMES_TO_TRIGGER) {
                        running = false
                        main.post { onSpeech() }
                        break
                    }
                } else {
                    voiced = 0
                }
            }
        } catch (e: Exception) {
            // Mic unavailable or taken by the recognizer: just stop gating.
        } finally {
            runCatching { rec.stop() }
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            runCatching { rec.release() }
        }
    }

    companion object {
        // Absolute floor for the RMS of 16-bit PCM, so a silent room never triggers.
        private const val THRESHOLD = 2200.0
        // The person's voice must beat the learned echo level by this factor.
        private const val MARGIN = 1.8
        // ~500ms spent learning Arin's own echo before we start detecting.
        private const val CALIB_FRAMES = 5
        // ~400ms of sustained voice above the bar before we treat it as a real interruption.
        private const val FRAMES_TO_TRIGGER = 4
    }
}
