package com.fakegps.mocklocation.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Lightweight, zero-decibel silent audio keep-alive anchor.
 * Plays an inaudible static PCM buffer of zeroes on a hardware DMA loop.
 * Prevents aggressive OEM task killers (such as Transsion Power Marathon, MIUI Cleaner,
 * and Samsung Smart Manager) from freezing or suspending the mock location background process
 * when Nowhere is minimized without a home screen widget.
 */
object SilentAudioKeepAlive {
    private const val TAG = "SilentAudioKeepAlive"
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    @Synchronized
    fun start() {
        if (isPlaying) return
        try {
            val sampleRate = 44100
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val silentBuffer = ShortArray(bufferSize)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build().apply {
                    write(silentBuffer, 0, silentBuffer.size)
                    setLoopPoints(0, silentBuffer.size, -1) // Infinite hardware loop
                    setVolume(0.0f) // Strictly 0 dB (completely silent)
                    play()
                }

            isPlaying = true
            Log.d(TAG, "Silent audio keep-alive anchor started.")
        } catch (e: Exception) {
            Log.w(TAG, "Could not start silent audio keep-alive: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        if (!isPlaying) return
        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    pause()
                    flush()
                    stop()
                }
                release()
            }
            audioTrack = null
            isPlaying = false
            Log.d(TAG, "Silent audio keep-alive anchor stopped.")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping silent audio keep-alive: ${e.message}")
        }
    }
}
