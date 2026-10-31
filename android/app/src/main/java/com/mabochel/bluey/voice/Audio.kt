package com.mabochel.bluey.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Keeps the start of a held utterance while Realtime is connecting (Shared/VoiceTurnBuffer.swift).
 * Audio stays in memory and is cleared on sleep or failure.
 */
class VoiceTurnBuffer {
    var recordedBytes = 0
        private set
    var overflowed = false
        private set
    private val pending = ArrayList<ByteArray>()
    private var pendingBytes = 0

    fun begin() {
        recordedBytes = 0; overflowed = false; pending.clear(); pendingBytes = 0
    }

    /** At least 100 ms of 24 kHz PCM16 mono. */
    val canCommit get() = !overflowed && recordedBytes >= 4800

    fun append(data: ByteArray, ready: Boolean): ByteArray? {
        if (overflowed) return null
        recordedBytes += data.size
        if (ready) return data
        if (pendingBytes + data.size > 960_000) { overflowed = true; pending.clear(); return null }
        pending += data; pendingBytes += data.size
        return null
    }

    fun connected(): List<ByteArray> {
        val data = pending.toList()
        pending.clear(); pendingBytes = 0
        return if (overflowed) emptyList() else data
    }
}

/**
 * The microphone, delivered as 24 kHz mono PCM16 little-endian (what the Realtime session expects).
 * Uses the voice-communication source so the phone's echo cancelling and noise suppression apply.
 * [onAudio] runs on the capture thread.
 */
class MicCapture(private val onAudio: (pcm: ByteArray, rms: Double) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // The caller checks RECORD_AUDIO first.
    fun start(): Boolean {
        if (running) return true
        val record = openRecorder() ?: return false
        running = true
        thread = Thread({ loop(record) }, "bluey-mic").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        for (rate in intArrayOf(24_000, 48_000, 16_000, 44_100)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val record = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
            }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }
        return null
    }

    private fun loop(record: AudioRecord) {
        val rate = record.sampleRate
        val resampler = Resampler(rate, WIRE_RATE)
        val buffer = ShortArray(rate / 20) // 50 ms
        try {
            record.startRecording()
            while (running) {
                val count = record.read(buffer, 0, buffer.size)
                if (count <= 0) { if (count < 0) break else continue }
                val out = resampler.process(buffer, count)
                if (out.isEmpty()) continue
                var sum = 0.0
                val bytes = ByteArray(out.size * 2)
                for (i in out.indices) {
                    val s = out[i].toInt()
                    bytes[2 * i] = (s and 0xFF).toByte()
                    bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
                    val n = s / 32768.0
                    sum += n * n
                }
                onAudio(bytes, sqrt(sum / out.size))
            }
        } catch (e: Exception) {
            Log.w("BlueyMic", "Capture stopped", e)
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    companion object {
        const val WIRE_RATE = 24_000
    }
}

/** Streaming linear resampler with a gentle low-pass when downsampling. Keeps state across buffers. */
class Resampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var position = 0.0
    private var previous = 0.0
    private var smooth = 0.0
    private val alpha = if (inRate > outRate) min(1.0, 1.25 * outRate / inRate) else 1.0

    fun process(input: ShortArray, count: Int): ShortArray {
        if (inRate == outRate) return input.copyOf(count)
        val samples = DoubleArray(count)
        for (i in 0 until count) {
            smooth += alpha * (input[i] - smooth)
            samples[i] = smooth
        }
        val out = ArrayList<Short>((count / step).toInt() + 2)
        while (position < count - 1) {
            val i = floor(position).toInt()
            val frac = position - i
            val a = if (i < 0) previous else samples[i]
            val b = samples[i + 1]
            out += (a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
            position += step
        }
        position -= count
        previous = samples[count - 1]
        return out.toShortArray()
    }
}

/**
 * His tiny cartoon chirp: a few soft, bell-like notes from a happy pentatonic scale, each with a little
 * upward "boop", exactly like the iPhone version. Also reports how loud it is right now, for the face.
 */
class Chirper {
    private var envelope = FloatArray(0)
    private var startedAt = 0L
    var volume = 1f

    /** Loudness right now, 0…1. */
    val level: Double
        get() {
            if (envelope.isEmpty()) return 0.0
            val index = ((SystemClock.elapsedRealtime() - startedAt) * RATE / 1000 / WINDOW).toInt()
            return if (index in envelope.indices) envelope[index].toDouble() else 0.0
        }

    /** Plays it and returns how long it lasts, in milliseconds. */
    fun play(syllables: Int): Long {
        val samples = synthesize(syllables)
        envelope = FloatArray(samples.size / WINDOW + 1) { w ->
            var sum = 0f
            val from = w * WINDOW
            val to = min(samples.size, from + WINDOW)
            for (i in from until to) sum += samples[i] * samples[i]
            min(1f, sqrt(sum / maxOf(1, to - from)) * 5f)
        }
        startedAt = SystemClock.elapsedRealtime()
        val millis = samples.size * 1000L / RATE
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 4)
                .build()
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.setVolume(volume)
            track.play()
            Thread {
                Thread.sleep(millis + 300)
                runCatching { track.stop() }
                track.release()
            }.start()
        }.onFailure { Log.w("BlueyChirp", "Couldn't play chirp", it) }
        return millis
    }

    companion object {
        const val RATE = 24_000
        private const val WINDOW = 1024
        // C major pentatonic, C6…E7, so it always sounds sweet together.
        private val scale = doubleArrayOf(1046.5, 1174.7, 1318.5, 1568.0, 1760.0, 2093.0, 2349.3, 2637.0)

        fun synthesize(syllables: Int, random: Random = Random.Default): FloatArray {
            val rate = RATE.toDouble()
            var index = random.nextInt(1, 4)
            val samples = ArrayList<Float>()
            for (i in 0 until syllables) {
                if (i > 0) index = (index + intArrayOf(-1, 1, 1, 2)[random.nextInt(4)]).coerceIn(0, scale.size - 1)
                val note = scale[index] * 0.5 // an octave down: rounder, less piercing
                val duration = if (i == syllables - 1) 0.13 else random.nextDouble(0.07, 0.09)
                val count = (duration * rate).toInt()
                var phase = 0.0
                for (n in 0 until count) {
                    val time = n / rate
                    val t = n.toDouble() / count
                    // Scoops up into the note over the first 25 ms, with a gentle wobble on the last one.
                    val scoop = 1 - 0.18 * exp(-time / 0.012)
                    val wobble = if (i == syllables - 1) 1 + 0.012 * sin(time * 2 * PI * 18) else 1.0
                    phase += 2 * PI * note * scoop * wobble / rate
                    val attack = min(1.0, time / 0.006)
                    val envelope = attack * exp(-t * 3.2) * (1 - t.pow(6))
                    val wave = sin(phase) + 0.12 * sin(2 * phase)
                    samples += (wave * envelope * 0.26).toFloat()
                }
                repeat((rate * 0.028).toInt()) { samples += 0f }
            }
            return samples.toFloatArray()
        }
    }
}
