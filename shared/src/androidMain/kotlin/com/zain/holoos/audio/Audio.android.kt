package com.zain.holoos.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

private const val BLOCK = 256

actual fun startAudio(mixer: Mixer) {
    Thread({
        runCatching {
            val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, BLOCK * 4 * 2))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            val f = FloatArray(BLOCK)
            while (true) {
                mixer.render(f, BLOCK)
                track.write(f, 0, BLOCK, AudioTrack.WRITE_BLOCKING)
            }
        }
    }, "holo-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
}
