package com.coreline.auraltune.audio.audiofx

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coreline.auraltune.audio.eq.BiquadSpec
import com.coreline.auraltune.audio.eq.BiquadType
import com.coreline.auraltune.data.PlaybackProcessingMode
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerDynamicsEqInstrumentedTest {
    @Test
    fun dynamicsProcessing_attachesToAudioTrackSession_andPlaysShortPcm() {
        assumeTrue("DynamicsProcessing requires API 28+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)

        val sampleRate = 48_000
        val track = buildTrack(sampleRate)
        val statuses = ArrayList<PlayerDynamicsEqStatus>()
        val controller = PlayerDynamicsEqController(
            statusSink = statuses::add,
            sampleRateProvider = { sampleRate.toDouble() },
        )
        try {
            controller.onAudioSessionIdChanged(track.audioSessionId)
            controller.setTargetSpecs(
                listOf(
                    BiquadSpec(BiquadType.LOW_SHELF, 90.0, 2.5, 0.7),
                    BiquadSpec(BiquadType.PEAKING, 2500.0, -2.0, 1.1),
                    BiquadSpec(BiquadType.HIGH_SHELF, 8500.0, 1.5, 0.7),
                ),
                headroomDb = -4f,
            )
            controller.setMode(PlaybackProcessingMode.ANDROID_DYNAMICS)

            val attached = statuses.lastOrNull { it.active }
            assertTrue(
                "DynamicsProcessing should attach, latest=${statuses.lastOrNull()}",
                attached?.backend == "DynamicsProcessing" && attached.bandCount == 12,
            )
            assertEquals(-4f, attached!!.headroomDb, 0.0001f)

            val chunk = sinePcm16(sampleRate, durationMs = 100)
            val prime = track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            assertEquals(chunk.size, prime)
            val underrunsBefore = track.underrunCount
            track.play()
            repeat(12) {
                val written = track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                assertEquals(chunk.size, written)
            }
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
            val underrunsAfter = track.underrunCount
            Log.i(
                TAG,
                "DynamicsProcessing status=$attached underrunsBefore=$underrunsBefore underrunsAfter=$underrunsAfter",
            )
            assertTrue(
                "underrun increase should stay within smoke-test tolerance: before=$underrunsBefore after=$underrunsAfter",
                underrunsAfter - underrunsBefore <= 1,
            )
        } finally {
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            }
            controller.close()
            track.release()
        }
    }

    private fun buildTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        assertTrue("AudioTrack minBuffer must be valid but was $minBuffer", minBuffer > 0)
        val bufferSize = max(minBuffer * 4, sampleRate * 2 * 2 / 2)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun sinePcm16(sampleRate: Int, durationMs: Int): ByteArray {
        val frames = sampleRate * durationMs / 1000
        val out = ByteArray(frames * 2 * 2)
        var p = 0
        for (i in 0 until frames) {
            val sample = (sin(2.0 * PI * 440.0 * i / sampleRate) * 0.10 * Short.MAX_VALUE).toInt().toShort()
            repeat(2) {
                out[p++] = (sample.toInt() and 0xFF).toByte()
                out[p++] = ((sample.toInt() ushr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    private companion object {
        const val TAG = "PlayerDynamicsEqTest"
    }
}
