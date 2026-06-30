package com.coreline.auraltune

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pre-test probe (NOT a feature test): logs whether AudioTrack supports hi-res PCM encodings
 * (float / 24-bit / 32-bit) at high sample rates on THIS device — both the standard (mixed) path
 * (getMinBufferSize) and the DIRECT passthrough path (isDirectPlaybackSupported).
 *
 * Run on a single device via `adb -s <serial> shell am instrument` and read logcat tag HiResProbe.
 */
@RunWith(AndroidJUnit4::class)
class AudioTrackHiResProbeTest {

    @Test
    fun probeAudioTrackHiResSupport() {
        Log.i(TAG, "=== AudioTrack hi-res probe :: ${Build.MANUFACTURER} ${Build.MODEL} / SDK ${Build.VERSION.SDK_INT} ===")
        val encodings = listOf(
            "PCM_16BIT" to AudioFormat.ENCODING_PCM_16BIT,
            "PCM_FLOAT" to AudioFormat.ENCODING_PCM_FLOAT,
            "PCM_24BIT_PACKED" to AudioFormat.ENCODING_PCM_24BIT_PACKED,
            "PCM_32BIT" to AudioFormat.ENCODING_PCM_32BIT,
        )
        val rates = intArrayOf(44100, 48000, 88200, 96000, 176400, 192000, 384000)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        for ((name, enc) in encodings) {
            for (r in rates) {
                val minBuf = runCatching {
                    AudioTrack.getMinBufferSize(r, AudioFormat.CHANNEL_OUT_STEREO, enc)
                }.getOrDefault(-999)
                val mix = if (minBuf > 0) "MIX_OK($minBuf)" else "MIX_NO($minBuf)"
                val direct = if (Build.VERSION.SDK_INT >= 29) {
                    runCatching {
                        AudioTrack.isDirectPlaybackSupported(
                            AudioFormat.Builder()
                                .setEncoding(enc)
                                .setSampleRate(r)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build(),
                            attrs,
                        )
                    }.getOrDefault(false)
                } else null
                Log.i(TAG, "%-18s @ %6dHz : %-14s DIRECT=%s".format(name, r, mix, direct))
            }
        }
        Log.i(TAG, "=== probe done ===")
    }

    private companion object { const val TAG = "HiResProbe" }
}
