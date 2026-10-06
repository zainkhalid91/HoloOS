package com.zain.holoos.audio

import kotlin.jvm.Synchronized
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

enum class Sfx { Tap, Hover, DiveIn, DiveOut, Key, KeyOp, Lock, Boot, Error, Prefetch }

const val SAMPLE_RATE = 48_000

/**
 * Procedural sound design: every effect is synthesised once at startup from sines,
 * glides, filtered noise and a short multi-tap echo for the "holographic room" tail.
 * No audio assets, no licences, instant start.
 */
object SoundBank {
    private val cache = HashMap<Sfx, FloatArray>()

    fun get(s: Sfx): FloatArray = cache.getOrPut(s) { synth(s) }

    fun warmUp() = Sfx.entries.forEach { get(it) }

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

    private fun FloatArray.echo(vararg taps: Pair<Float, Float>): FloatArray {
        val out = copyOf()
        for ((delay, g) in taps) {
            val d = (delay * SAMPLE_RATE).toInt()
            for (i in d until size) out[i] += this[i - d] * g
        }
        return out
    }

    private fun synth(s: Sfx): FloatArray = when (s) {
        Sfx.Tap -> buf(0.22f).apply {
            tone(0f, 0.08f, 2600f, 2400f, 0.35f, 55f)
            tone(0f, 0.06f, 3900f, 3600f, 0.18f, 80f)
            whoosh(0f, 0.012f, 6000f, 9000f, 0.5f, 1)
        }.echo(0.041f to 0.25f, 0.083f to 0.12f)

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

        Sfx.Boot -> buf(2.2f).apply {
            tone(0f, 1.6f, 55f, 110f, 0.35f, 0.9f, 0.4f)
            tone(0f, 1.6f, 110f, 220f, 0.15f, 1.0f, 0.4f)
            whoosh(0.1f, 1.2f, 300f, 4000f, 0.6f, 21)
            for (k in 0 until 6) tone(0.9f + k * 0.07f, 0.6f, 880f * (1 + k * 0.25f), 880f * (1 + k * 0.25f), 0.07f, 5f)
        }.echo(0.071f to 0.3f, 0.149f to 0.18f)

        Sfx.Error -> buf(0.25f).apply {
            tone(0f, 0.18f, 180f, 150f, 0.3f, 12f)
            tone(0f, 0.18f, 360f, 300f, 0.15f, 14f)
        }

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

/** Starts the platform's low-latency output stream pulling from [mixer]. */
expect fun startAudio(mixer: Mixer)
