package com.zain.holoos.audio

import kotlin.jvm.Synchronized
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Each effect follows the sound brief. Its lowercase name is also the override file
 * name: drop `files/sfx/<name>.wav` into composeResources to replace the synthesised one.
 *  - Telemetry: rapid digital clicks, data processing, crisp, high-frequency
 *  - Tap: clean holographic blip, short, high, positive, 80s synth
 *  - Notify: smooth digital pop-up chime, clean synthetic ring
 *  - Denied: digital glitch, low distorted buzz, abrupt
 *  - Boot: rising synthetic pitch, interface power-on
 */
enum class Sfx { Tap, Hover, DiveIn, DiveOut, Key, KeyOp, Lock, Boot, Denied, Telemetry, Notify, Prefetch }

const val SAMPLE_RATE = 48_000

/**
 * Procedural sound design: every effect is synthesised once at startup from sines,
 * glides, filtered noise and a short multi-tap echo for the "holographic room" tail.
 * No audio assets, no licences, instant start.
 */
object SoundBank {
    private val cache = HashMap<Sfx, FloatArray>()

    fun get(s: Sfx): FloatArray = cache.getOrPut(s) { synth(s) }

    /**
     * Loads optional one-shot WAV overrides (files/sfx/<name>.wav), then synthesises
     * everything else. Missing files are normal and silently skipped.
     */
    suspend fun warmUp(loader: suspend (String) -> ByteArray?) {
        for (sfx in Sfx.entries) {
            val bytes = runCatching { loader("files/sfx/${sfx.name.lowercase()}.wav") }.getOrNull()
            val pcm = bytes?.let { Wav.decode(it) }
            if (pcm != null) cache[sfx] = pcm
        }
        Sfx.entries.forEach { get(it) }
    }

    private fun buf(seconds: Float) = FloatArray((seconds * SAMPLE_RATE).toInt())
    private fun t(i: Int) = i.toFloat() / SAMPLE_RATE
    private const val TAU = (2 * PI).toFloat()

    /** Adds a sine whose frequency glides exponentially from f0 to f1. */
    private fun FloatArray.tone(start: Float, dur: Float, f0: Float, f1: Float, gain: Float, decay: Float, attack: Float = 0.002f) {
        var phase = 0f
        val s0 = (start * SAMPLE_RATE).toInt(); val n = (dur * SAMPLE_RATE).toInt()
        for (k in 0 until n) {
            val i = s0 + k; if (i >= size) break
            val u = k.toFloat() / n
            val f = f0 * exp(kotlin.math.ln(f1 / f0) * u)
            phase += TAU * f / SAMPLE_RATE
            val env = (t(k) / attack).coerceAtMost(1f) * exp(-decay * t(k))
            this[i] += sin(phase) * gain * env
        }
    }

    /** Band-limited "air": noise through a one-pole low-pass whose cutoff sweeps. */
    private fun FloatArray.whoosh(start: Float, dur: Float, c0: Float, c1: Float, gain: Float, seed: Int) {
        val rnd = Random(seed)
        var lp = 0f; var lp2 = 0f
        val s0 = (start * SAMPLE_RATE).toInt(); val n = (dur * SAMPLE_RATE).toInt()
        for (k in 0 until n) {
            val i = s0 + k; if (i >= size) break
            val u = k.toFloat() / n
            val cutoff = c0 + (c1 - c0) * u
            val a = (TAU * cutoff / SAMPLE_RATE).coerceAtMost(1f)
            lp += a * (rnd.nextFloat() * 2f - 1f - lp)
            lp2 += a * (lp - lp2)
            val env = sin(PI.toFloat() * u)
            this[i] += (lp - lp2 * 0.5f) * gain * env * env
        }
    }

    /** Band-limited pulse/square-ish tone (odd harmonics) — the 80s synth colour. */
    private fun FloatArray.pulse(start: Float, dur: Float, f0: Float, f1: Float, gain: Float, decay: Float, harmonics: Int = 5, attack: Float = 0.001f) {
        var phase = 0f
        val s0 = (start * SAMPLE_RATE).toInt(); val n = (dur * SAMPLE_RATE).toInt()
        for (k in 0 until n) {
            val i = s0 + k; if (i >= size) break
            val u = k.toFloat() / n
            val f = f0 * exp(kotlin.math.ln(f1 / f0) * u)
            phase += TAU * f / SAMPLE_RATE
            var v = 0f; var h = 1
            while (h <= harmonics * 2 - 1 && f * h < 16_000f) { v += sin(phase * h) / h; h += 2 }
            val env = (t(k) / attack).coerceAtMost(1f) * exp(-decay * t(k))
            this[i] += v * 0.8f * gain * env
        }
    }

    /** Inharmonic bell partials: a clean synthetic ring. */
    private fun FloatArray.bell(start: Float, f: Float, gain: Float, decay: Float) {
        tone(start, 0.9f, f, f, gain, decay)
        tone(start, 0.6f, f * 2.76f, f * 2.76f, gain * 0.35f, decay * 1.8f)
        tone(start, 0.4f, f * 5.40f, f * 5.40f, gain * 0.12f, decay * 3f)
    }

    /** Hard-clips through a drive stage and reduces bit depth / sample rate: digital grit. */
    private fun FloatArray.crush(drive: Float, bits: Int, hold: Int): FloatArray {
        val steps = (1 shl (bits - 1)).toFloat()
        var held = 0f
        for (i in indices) {
            if (i % hold == 0) held = tanh(this[i] * drive)
            this[i] = kotlin.math.round(held * steps) / steps
        }
        return this
    }

    private fun FloatArray.echo(vararg taps: Pair<Float, Float>): FloatArray {
        val out = copyOf()
        for ((delay, g) in taps) {
            val d = (delay * SAMPLE_RATE).toInt()
            for (i in d until size) out[i] += this[i - d] * g
        }
        return out
    }

    private fun synth(s: Sfx): FloatArray = when (s) {
        // Clean holographic blip: two rising pulse notes (A6 → E7), short, positive.
        Sfx.Tap -> buf(0.16f).apply {
            pulse(0f, 0.030f, 1760f, 1760f, 0.22f, 60f, harmonics = 4)
            pulse(0.028f, 0.05f, 2637f, 2637f, 0.24f, 55f, harmonics = 4)
            tone(0f, 0.004f, 8000f, 8000f, 0.2f, 400f)
        }.echo(0.037f to 0.18f)

        Sfx.Hover -> buf(0.05f).apply { tone(0f, 0.04f, 1750f, 1900f, 0.09f, 90f) }

        Sfx.DiveIn -> buf(0.85f).apply {
            tone(0f, 0.32f, 180f, 760f, 0.32f, 2.5f, 0.02f)
            whoosh(0f, 0.38f, 400f, 5200f, 1.1f, 7)
            tone(0.30f, 0.5f, 1320f, 1320f, 0.22f, 7f)
            tone(0.30f, 0.5f, 1980f, 1980f, 0.12f, 8f)
            tone(0.34f, 0.4f, 2640f, 2640f, 0.06f, 9f)
        }.echo(0.057f to 0.3f, 0.113f to 0.16f, 0.171f to 0.08f)

        Sfx.DiveOut -> buf(0.6f).apply {
            tone(0f, 0.3f, 900f, 200f, 0.28f, 3f, 0.01f)
            whoosh(0f, 0.32f, 4800f, 500f, 1.0f, 11)
            tone(0.22f, 0.3f, 990f, 990f, 0.12f, 10f)
        }.echo(0.057f to 0.25f, 0.113f to 0.12f)

        Sfx.Key -> buf(0.2f).apply {
            tone(0f, 0.12f, 1480f, 1480f, 0.26f, 30f)
            tone(0f, 0.08f, 2960f, 2960f, 0.08f, 45f)
            whoosh(0f, 0.008f, 7000f, 7000f, 0.35f, 3)
        }.echo(0.037f to 0.2f)

        Sfx.KeyOp -> buf(0.24f).apply {
            tone(0f, 0.16f, 990f, 1110f, 0.26f, 22f)
            tone(0.03f, 0.13f, 1480f, 1660f, 0.14f, 26f)
        }.echo(0.041f to 0.22f, 0.083f to 0.1f)

        Sfx.Lock -> buf(0.5f).apply {
            tone(0f, 0.18f, 1500f, 1500f, 0.25f, 18f)
            tone(0.09f, 0.3f, 2000f, 2000f, 0.25f, 12f)
            tone(0.09f, 0.3f, 3000f, 3000f, 0.06f, 14f)
        }.echo(0.061f to 0.25f, 0.127f to 0.12f)

        // Power-on: a synthetic engine spooling up (rising pulse), air opening up,
        // a data flutter, then an arpeggiated "systems online" confirmation.
        Sfx.Boot -> buf(2.6f).apply {
            pulse(0f, 1.5f, 55f, 440f, 0.20f, 0.4f, harmonics = 6, attack = 0.5f)
            tone(0f, 1.5f, 110f, 880f, 0.10f, 0.5f, 0.5f)
            whoosh(0.05f, 1.4f, 200f, 6000f, 0.55f, 21)
            val rnd = Random(5)
            for (k in 0 until 24) {
                val f = 2400f + rnd.nextFloat() * 3200f
                tone(0.7f + k * 0.03f, 0.012f, f, f, 0.05f, 250f)
            }
            val arp = floatArrayOf(880f, 1108.7f, 1318.5f, 1760f)
            arp.forEachIndexed { i, f -> pulse(1.45f + i * 0.075f, 0.35f, f, f, 0.11f, 7f, harmonics = 3) }
            bell(1.8f, 1760f, 0.12f, 3.5f)
        }.echo(0.071f to 0.28f, 0.149f to 0.16f, 0.223f to 0.08f)

        // Access denied: low distorted buzz, gated into three stutters, crushed into a
        // glitch, and cut off abruptly (no tail).
        Sfx.Denied -> buf(0.34f).apply {
            pulse(0f, 0.34f, 110f, 98f, 0.5f, 1.5f, harmonics = 9)
            pulse(0f, 0.34f, 116.5f, 104f, 0.3f, 1.5f, harmonics = 9)
            for (i in indices) {
                val ms = i * 1000 / SAMPLE_RATE
                val gate = if (ms < 90 || ms in 120..200 || ms in 230..330) 1f else 0f
                this[i] *= gate
            }
        }.crush(drive = 4f, bits = 5, hold = 6)

        // Telemetry: a burst of micro data clicks at random high pitches and spacing,
        // like a processor streaming bytes. Crisp, dry, high-frequency.
        Sfx.Telemetry -> buf(0.42f).apply {
            val rnd = Random(77)
            var at = 0f
            while (at < 0.38f) {
                val f = 2800f + rnd.nextInt(6) * 520f
                if (rnd.nextFloat() < 0.75f) pulse(at, 0.009f, f, f, 0.10f + rnd.nextFloat() * 0.06f, 300f, harmonics = 2)
                else tone(at, 0.004f, 9000f, 9000f, 0.12f, 900f)
                at += 0.012f + rnd.nextFloat() * 0.022f
            }
        }

        // Pop-up: smooth two-note synthetic chime (C6 → G6) with a soft ring-out.
        Sfx.Notify -> buf(1.0f).apply {
            bell(0f, 1046.5f, 0.20f, 5f)
            bell(0.11f, 1568f, 0.22f, 4.5f)
        }.echo(0.061f to 0.22f, 0.127f to 0.1f)

        Sfx.Prefetch -> buf(0.06f).apply { tone(0f, 0.05f, 3200f, 3400f, 0.035f, 70f) }
    }
}

/**
 * A tiny real-time mixer. The platform audio thread pulls blocks from [render]; the UI
 * thread calls [play]. Voices can be pitch-shifted (fractional playback rate), which is
 * how each calculator key gets its own note.
 */
class Mixer {
    private class Voice(val data: FloatArray, val rate: Float, val gain: Float) { var pos = 0f }

    private val voices = ArrayList<Voice>()
    var masterGain = 0.55f
    var muted = false

    @Synchronized
    fun play(s: Sfx, pitch: Float = 1f, gain: Float = 1f) {
        if (muted) return
        if (voices.size >= 24) voices.removeAt(0)
        voices += Voice(SoundBank.get(s), pitch, gain)
    }

    /** Fills [out] (mono, -1..1). Returns false when silent so the caller may idle. */
    @Synchronized
    fun render(out: FloatArray, frames: Int): Boolean {
        for (i in 0 until frames) out[i] = 0f
        if (voices.isEmpty()) return false
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            for (i in 0 until frames) {
                val p = v.pos.toInt()
                if (p >= v.data.size - 1) break
                val f = v.pos - p
                out[i] += (v.data[p] * (1 - f) + v.data[p + 1] * f) * v.gain
                v.pos += v.rate
            }
            if (v.pos.toInt() >= v.data.size - 1) it.remove()
        }
        for (i in 0 until frames) out[i] = tanh(out[i] * masterGain * 1.4f)
        return true
    }
}

/**
 * Minimal WAV reader for one-shot overrides: PCM 8/16/24-bit or 32-bit float, any channel
 * count (mixed to mono), any sample rate (linearly resampled to 48 kHz).
 */
object Wav {
    fun decode(b: ByteArray): FloatArray? {
        if (b.size < 44 || b.decodeToString(0, 4) != "RIFF" || b.decodeToString(8, 12) != "WAVE") return null
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        var fmt = 0; var ch = 1; var rate = SAMPLE_RATE; var bits = 16
        var o = 12
        while (o + 8 <= b.size) {
            val id = b.decodeToString(o, o + 4); val len = u32(o + 4); val body = o + 8
            if (id == "fmt ") { fmt = u16(body); ch = u16(body + 2); rate = u32(body + 4); bits = u16(body + 14) }
            if (id == "data") {
                val bps = bits / 8
                val frames = minOf(len, b.size - body) / (bps * ch)
                val mono = FloatArray(frames)
                for (f in 0 until frames) {
                    var acc = 0f
                    for (c in 0 until ch) {
                        val p = body + (f * ch + c) * bps
                        acc += when {
                            fmt == 3 && bits == 32 -> Float.fromBits(u32(p))
                            bits == 16 -> (u16(p).toShort()).toFloat() / 32768f
                            bits == 24 -> (((b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() shl 16))).toFloat() / 8388608f
                            bits == 8 -> ((b[p].toInt() and 0xFF) - 128) / 128f
                            else -> 0f
                        }
                    }
                    mono[f] = acc / ch
                }
                if (rate == SAMPLE_RATE) return mono
                val outN = (frames.toLong() * SAMPLE_RATE / rate).toInt()
                return FloatArray(outN) { i ->
                    val src = i.toFloat() * rate / SAMPLE_RATE
                    val j = src.toInt().coerceAtMost(frames - 1); val fr = src - j
                    mono[j] * (1 - fr) + mono[(j + 1).coerceAtMost(frames - 1)] * fr
                }
            }
            o = body + len + (len and 1)
        }
        return null
    }
}

/** Starts the platform's low-latency output stream pulling from [mixer]. */
expect fun startAudio(mixer: Mixer)
