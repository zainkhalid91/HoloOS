package com.zain.holoos.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

private const val BLOCK = 256 // ~5.3 ms at 48 kHz

actual fun startAudio(mixer: Mixer) {
    Thread({
        runCatching {
            val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
            val line = AudioSystem.getSourceDataLine(format)
            line.open(format, BLOCK * 2 * 4)
            line.start()
            val f = FloatArray(BLOCK)
            val b = ByteArray(BLOCK * 2)
            while (true) {
                mixer.render(f, BLOCK)
                for (i in 0 until BLOCK) {
                    val s = (f[i] * 32767f).toInt().coerceIn(-32768, 32767)
                    b[2 * i] = s.toByte(); b[2 * i + 1] = (s shr 8).toByte()
                }
                line.write(b, 0, b.size) // blocks: paces the loop to real time
            }
        }
    }, "holo-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
}
