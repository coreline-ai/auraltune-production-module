package com.coreline.auraltune.audio

import androidx.media3.common.C
import com.coreline.auraltune.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class AuralTuneAudioProcessorTest {

    @Test
    fun pcm16ToFloat_readsLittleEndianSignedSamples() {
        val input = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(Short.MIN_VALUE)
            .putShort(Short.MAX_VALUE)
            .flip() as ByteBuffer

        assertEquals(-1f, pcmSampleToFloat(input, 0, C.ENCODING_PCM_16BIT), 0.000001f)
        assertEquals(32767f / 32768f, pcmSampleToFloat(input, 2, C.ENCODING_PCM_16BIT), 0.000001f)
    }

    @Test
    fun pcm24ToFloat_signExtendsLittleEndianSamples() {
        val input = ByteBuffer.allocate(6)
        input.put(byteArrayOf(0x00, 0x00, 0x80.toByte()))
        input.put(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F))
        input.flip()

        assertEquals(-1f, pcmSampleToFloat(input, 0, C.ENCODING_PCM_24BIT), 0.000001f)
        assertEquals(8388607f / 8388608f, pcmSampleToFloat(input, 3, C.ENCODING_PCM_24BIT), 0.000001f)
    }

    @Test
    fun pcm32ToFloat_readsLittleEndianSignedSamples() {
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(Int.MIN_VALUE)
            .putInt(Int.MAX_VALUE)
            .flip() as ByteBuffer

        assertEquals(-1f, pcmSampleToFloat(input, 0, C.ENCODING_PCM_32BIT), 0.000001f)
        assertEquals(Int.MAX_VALUE / 2147483648f, pcmSampleToFloat(input, 4, C.ENCODING_PCM_32BIT), 0.000001f)
    }

    @Test
    fun pcmFloatToFloat_readsLittleEndianFloatSamples() {
        val input = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.25f)
            .flip() as ByteBuffer

        assertEquals(0.25f, pcmSampleToFloat(input, 0, C.ENCODING_PCM_FLOAT), 0.000001f)
    }

    @Test
    fun fastPathDiagnostics_recordsDebugCounters() {
        AuralTuneAudioProcessorDiagnostics.reset()

        AuralTuneAudioProcessorDiagnostics.recordFastPathHit(512)
        AuralTuneAudioProcessorDiagnostics.recordFastPathHit(1024)
        AuralTuneAudioProcessorDiagnostics.recordFastPathMiss()
        AuralTuneAudioProcessorDiagnostics.recordFastPathNativeReject(-6)

        val snapshot = AuralTuneAudioProcessorDiagnostics.snapshot()
        if (BuildConfig.DEBUG) {
            assertEquals(2L, snapshot.fastPathHits)
            assertEquals(2L, snapshot.fastPathMisses)
            assertEquals(1L, snapshot.nativeRejects)
            assertEquals(1024L, snapshot.maxFrames)
            assertEquals(-6, snapshot.lastNativeRc)
        } else {
            assertEquals(0L, snapshot.fastPathHits)
            assertEquals(0L, snapshot.fastPathMisses)
            assertEquals(0L, snapshot.nativeRejects)
            assertEquals(0L, snapshot.maxFrames)
            assertEquals(0, snapshot.lastNativeRc)
        }
    }
}
