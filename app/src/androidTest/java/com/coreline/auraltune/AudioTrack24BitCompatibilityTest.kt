package com.coreline.auraltune

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class AudioTrack24BitCompatibilityTest {
    @Test
    fun probePcmEncodingsForMusicStream() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val sampleRates = intArrayOf(44_100, 48_000, 96_000, 192_000)
        val encodings = intArrayOf(
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_24BIT_PACKED,
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        val lines = mutableListOf("nativeOutputRate=$nativeRate")
        var baseline16Bit48k = false
        var any24Bit = false

        sampleRates.forEach { rate ->
            encodings.forEach { encoding ->
                val result = probe(attributes, rate, encoding)
                if (rate == 48_000 && encoding == AudioFormat.ENCODING_PCM_16BIT) {
                    baseline16Bit48k = result.initialized && result.writeResult > 0
                }
                if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED &&
                    result.initialized &&
                    result.writeResult > 0
                ) {
                    any24Bit = true
                }
                lines += result.toReportLine()
            }
        }

        val report = lines.joinToString(separator = "\n")
        Log.i(TAG, report)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("AudioTrack24BitCompatibility", report)
            },
        )

        assertTrue("Baseline PCM_16BIT/48k AudioTrack should initialize and write", baseline16Bit48k)
        assertTrue("No PCM_24BIT_PACKED AudioTrack configuration initialized and wrote", any24Bit)
    }

    private fun probe(
        attributes: AudioAttributes,
        sampleRate: Int,
        encoding: Int,
    ): ProbeResult {
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(encoding)
            .build()
        val direct = if (Build.VERSION.SDK_INT >= 29) {
            runCatching { AudioTrack.isDirectPlaybackSupported(format, attributes) }.getOrDefault(false)
        } else {
            false
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            encoding,
        )
        if (minBuffer <= 0) {
            return ProbeResult(sampleRate, encoding, minBuffer, initialized = false, writeResult = minBuffer, direct)
        }

        val bytesPerFrame = bytesPerSample(encoding) * 2
        val writeBytes = bytesPerFrame * 256
        val bufferBytes = alignToFrame(max(minBuffer, writeBytes * 4), bytesPerFrame)
        var track: AudioTrack? = null
        return try {
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build()
            val initialized = track.state == AudioTrack.STATE_INITIALIZED
            val writeResult = if (initialized) {
                track.play()
                writeSilence(track, encoding, writeBytes)
            } else {
                -100
            }
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            }
            ProbeResult(sampleRate, encoding, minBuffer, initialized, writeResult, direct)
        } catch (t: Throwable) {
            ProbeResult(
                sampleRate,
                encoding,
                minBuffer,
                initialized = false,
                writeResult = -200,
                directSupported = direct,
                error = t::class.java.simpleName + ":" + (t.message ?: ""),
            )
        } finally {
            track?.release()
        }
    }

    private data class ProbeResult(
        val sampleRate: Int,
        val encoding: Int,
        val minBuffer: Int,
        val initialized: Boolean,
        val writeResult: Int,
        val directSupported: Boolean,
        val error: String? = null,
    ) {
        fun toReportLine(): String =
            "${encodingName(encoding)}@$sampleRate " +
                "min=$minBuffer init=$initialized write=$writeResult direct=$directSupported" +
                (error?.let { " error=$it" } ?: "")
    }

    private companion object {
        private const val TAG = "AudioTrack24BitProbe"

        private fun bytesPerSample(encoding: Int): Int = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> error("unsupported encoding=$encoding")
        }

        private fun alignToFrame(bytes: Int, bytesPerFrame: Int): Int =
            ((bytes + bytesPerFrame - 1) / bytesPerFrame) * bytesPerFrame

        private fun writeSilence(track: AudioTrack, encoding: Int, writeBytes: Int): Int =
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val samples = writeBytes / bytesPerSample(encoding)
                track.write(FloatArray(samples), 0, samples, AudioTrack.WRITE_BLOCKING)
            } else {
                track.write(ByteArray(writeBytes), 0, writeBytes, AudioTrack.WRITE_BLOCKING)
            }

        private fun encodingName(encoding: Int): String = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> "S16"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "S24_PACKED"
            AudioFormat.ENCODING_PCM_32BIT -> "S32"
            AudioFormat.ENCODING_PCM_FLOAT -> "F32"
            else -> "encoding_$encoding"
        }
    }
}
