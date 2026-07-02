package com.coreline.auraltune.audio

import android.content.ComponentName
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coreline.auraltune.AuralTuneApplication
import com.coreline.auraltune.audio.eq.BiquadSpec
import com.coreline.auraltune.audio.eq.BiquadType
import com.coreline.auraltune.data.PlaybackProcessingMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuralTuneMediaServiceProcessingInstrumentedTest {
    @Test
    fun mediaService_playsAuralTuneThenAndroidDynamicsMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as AuralTuneApplication
        val processing = app.serviceLocator.playbackProcessingState
        val settings = app.serviceLocator.settingsStore
        val wav = File(context.cacheDir, "auraltune-processing-smoke.wav")
        writeWav16Stereo(wav, durationMs = 5_000)

        runBlocking { settings.setPlaybackProcessingMode(PlaybackProcessingMode.AURAL_TUNE) }
        processing.setTargetSpecs(targetSpecs())

        val token = SessionToken(context, ComponentName(context, AuralTuneMediaService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val controller = future.get(10, TimeUnit.SECONDS)
        try {
            onMain {
                controller.setMediaItem(MediaItem.fromUri(Uri.fromFile(wav)))
                controller.prepare()
                controller.play()
            }

            waitUntil("AuralTune playback should become ready") {
                onMain { controller.playbackState == Player.STATE_READY && controller.isPlaying }
            }
            waitUntil("AuralTune playback position should advance") {
                onMain { controller.currentPosition >= 250L }
            }
            assertTrue("AuralTune mode must keep native engine enabled", processing.useNativeEngine())

            runBlocking { settings.setPlaybackProcessingMode(PlaybackProcessingMode.ANDROID_DYNAMICS) }
            processing.setTargetSpecs(targetSpecs())
            waitUntil("Android mode should disable native engine") {
                processing.mode.value == PlaybackProcessingMode.ANDROID_DYNAMICS && !processing.useNativeEngine()
            }
            waitUntil(
                label = { "DynamicsProcessing should attach to service audio session, status=${processing.dynamicsStatus.value}" },
            ) {
                processing.dynamicsStatus.value.active
            }
            val status = processing.dynamicsStatus.value
            assertEquals("DynamicsProcessing", status.backend)
            assertEquals(12, status.bandCount)
            val pos = onMain { controller.currentPosition }
            waitUntil("Android EQ playback position should continue advancing") {
                onMain { controller.currentPosition > pos + 250L }
            }
        } finally {
            onMain {
                runCatching { controller.stop() }
                runCatching { controller.clearMediaItems() }
                MediaController.releaseFuture(future)
            }
            processing.setTargetSpecs(emptyList())
            runBlocking { settings.setPlaybackProcessingMode(PlaybackProcessingMode.AURAL_TUNE) }
            wav.delete()
        }
    }

    private fun targetSpecs(): List<BiquadSpec> = listOf(
        BiquadSpec(BiquadType.LOW_SHELF, 90.0, 2.5, 0.7),
        BiquadSpec(BiquadType.PEAKING, 2500.0, -2.0, 1.1),
        BiquadSpec(BiquadType.HIGH_SHELF, 8500.0, 1.5, 0.7),
    )

    private fun waitUntil(
        label: String,
        timeoutMs: Long = 8_000L,
        condition: () -> Boolean,
    ) = waitUntil(label = { label }, timeoutMs = timeoutMs, condition = condition)

    private fun waitUntil(label: () -> String, timeoutMs: Long = 8_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        assertTrue(label(), condition())
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = runCatching(block)
        }
        return result!!.getOrThrow()
    }

    private fun writeWav16Stereo(file: File, durationMs: Int) {
        val sampleRate = 48_000
        val channels = 2
        val bytesPerSample = 2
        val frames = sampleRate * durationMs / 1000
        val dataSize = frames * channels * bytesPerSample
        val out = ByteArray(44 + dataSize)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataSize)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * channels * bytesPerSample)
        bb.putShort((channels * bytesPerSample).toShort())
        bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataSize)
        for (i in 0 until frames) {
            val sample = (sin(2.0 * PI * 440.0 * i / sampleRate) * 0.10 * Short.MAX_VALUE).toInt().toShort()
            repeat(channels) { bb.putShort(sample) }
        }
        file.writeBytes(out)
    }
}
