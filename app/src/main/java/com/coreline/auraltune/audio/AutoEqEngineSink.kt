package com.coreline.auraltune.audio

import com.coreline.audio.AudioEngine

/**
 * Narrow engine surface used by [DeviceAutoEqManager].
 *
 * Keeping this boundary small makes native apply failures testable without
 * starting the full playback stack or replacing the process-wide AudioEngine.
 */
internal interface AutoEqEngineSink {
    fun updateAutoEq(
        preampDB: Float,
        enableLimiter: Boolean,
        profileOptimizedRate: Double,
        filterTypes: IntArray,
        frequencies: FloatArray,
        gainsDB: FloatArray,
        qFactors: FloatArray,
    ): Int

    fun clearAutoEq()
}

internal class AudioEngineAutoEqSink(
    private val engine: AudioEngine,
) : AutoEqEngineSink {
    override fun updateAutoEq(
        preampDB: Float,
        enableLimiter: Boolean,
        profileOptimizedRate: Double,
        filterTypes: IntArray,
        frequencies: FloatArray,
        gainsDB: FloatArray,
        qFactors: FloatArray,
    ): Int = engine.updateAutoEq(
        preampDB = preampDB,
        enableLimiter = enableLimiter,
        profileOptimizedRate = profileOptimizedRate,
        filterTypes = filterTypes,
        frequencies = frequencies,
        gainsDB = gainsDB,
        qFactors = qFactors,
    )

    override fun clearAutoEq() {
        engine.clearAutoEq()
    }
}
