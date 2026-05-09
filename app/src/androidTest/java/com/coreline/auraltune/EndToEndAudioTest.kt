// EndToEndAudioTest.kt
// Real-device instrumented tests covering the full DSP + data layer stack.
// Verifies what host JVM tests cannot: actual native lib load, JNI handle lifetime,
// real network round-trip, real DSP cascade, real diagnostic counters from native land.
package com.coreline.auraltune

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coreline.audio.AudioEngine
import com.coreline.audio.EqFilterType
import com.coreline.autoeq.AutoEqApi
import com.coreline.autoeq.model.CatalogState
import com.coreline.autoeq.repository.AutoEqRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class EndToEndAudioTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Phase 1+2 verification — native library loads, handle lifecycle works, and the
     * 4 diagnostic counters are addressable.
     */
    @Test
    fun audioEngine_lifecycle_and_diagnostics() {
        AudioEngine(48000).use { engine ->
            val d = engine.readDiagnostics()
            // Fresh engine: every counter is 0.
            assertEquals(0L, d.xrunCount)
            assertEquals(0L, d.nonFiniteResetCount)
            assertEquals(0L, d.configSwapCount)
            assertEquals(0L, d.sampleRateChangeCount)
        }
    }

    /**
     * Phase 2 verification — applying an AutoEQ profile increments configSwapCount,
     * proving the atomic publish path is wired through JNI to native counters.
     */
    @Test
    fun autoEq_apply_incrementsConfigSwap() {
        AudioEngine(48000).use { engine ->
            val before = engine.readDiagnostics().configSwapCount
            // Apply a small Sennheiser HD 600-style correction.
            val rc = engine.updateAutoEq(
                preampDB = -3f,
                enableLimiter = true,
                profileOptimizedRate = 48000.0,
                filterTypes = intArrayOf(
                    EqFilterType.LOW_SHELF.nativeId,
                    EqFilterType.PEAKING.nativeId,
                    EqFilterType.HIGH_SHELF.nativeId,
                ),
                frequencies = floatArrayOf(105f, 3000f, 8000f),
                gainsDB = floatArrayOf(2.5f, -2.0f, 1.5f),
                qFactors = floatArrayOf(0.7f, 1.0f, 0.7f),
            )
            assertEquals("updateAutoEq should succeed (rc=0)", 0, rc)
            val after = engine.readDiagnostics().configSwapCount
            assertTrue("configSwapCount must advance after updateAutoEq", after > before)
        }
    }

    /**
     * Phase 2 verification — sample rate change recomputes coefficients and increments
     * its dedicated counter.
     */
    @Test
    fun sampleRateChange_increments_counter() {
        AudioEngine(48000).use { engine ->
            // Seed an active config so updateSampleRate has filters to recompute.
            engine.updateAutoEq(
                preampDB = 0f,
                enableLimiter = false,
                profileOptimizedRate = 48000.0,
                filterTypes = intArrayOf(EqFilterType.PEAKING.nativeId),
                frequencies = floatArrayOf(1000f),
                gainsDB = floatArrayOf(3f),
                qFactors = floatArrayOf(1f),
            )
            val before = engine.readDiagnostics().sampleRateChangeCount
            engine.updateSampleRate(44100)
            val after = engine.readDiagnostics().sampleRateChangeCount
            assertTrue(
                "sampleRateChangeCount must advance after updateSampleRate (was $before, now $after)",
                after > before,
            )
            assertEquals(44100, engine.sampleRate)
        }
    }

    /**
     * Phase 2 verification — process() consumes a Float32 stereo direct buffer and
     * produces finite output. No assertions on amplitude; just that we can run an
     * audio cascade without crash and that the NaN guard does NOT trip on clean input.
     */
    @Test
    fun process_finiteOutput_withRealCascade() {
        AudioEngine(48000).use { engine ->
            engine.updateAutoEq(
                preampDB = -2f,
                enableLimiter = true,
                profileOptimizedRate = 48000.0,
                filterTypes = intArrayOf(
                    EqFilterType.LOW_SHELF.nativeId, EqFilterType.PEAKING.nativeId,
                ),
                frequencies = floatArrayOf(60f, 3000f),
                gainsDB = floatArrayOf(4f, -2f),
                qFactors = floatArrayOf(0.7f, 1.0f),
            )
            engine.setAutoEqEnabled(true)

            val frames = 1024
            val buffer = ByteBuffer
                .allocateDirect(frames * 2 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            val floats = buffer.asFloatBuffer()

            // Generate 1 kHz sine at -12 dBFS for 1 buffer.
            val amp = 0.25f
            for (i in 0 until frames) {
                val s = (amp * sin(2.0 * PI * 1000.0 * i / 48000.0)).toFloat()
                floats.put(s).put(s)
            }
            floats.position(0)

            val rc = engine.process(buffer, frames)
            assertEquals("process() should return 0", 0, rc)

            // Verify output is finite. Read back from the same buffer (in-place).
            val out = FloatArray(frames * 2)
            buffer.asFloatBuffer().get(out)
            val nonFinite = out.count { !it.isFinite() }
            assertEquals("Cascade output must be finite", 0, nonFinite)

            val d = engine.readDiagnostics()
            assertEquals(
                "NaN guard should not have tripped on clean sine input",
                0L, d.nonFiniteResetCount,
            )
            assertTrue("totalProcessedFrames advances", d.totalProcessedFrames >= frames)
        }
    }

    /**
     * Phase 2 stress — rapid profile switching at 50 Hz. No xrun should occur because
     * we're not on the audio callback thread; this purely tests the atomic config
     * swap path can sustain high-frequency updates without crash or counter drift.
     */
    @Test
    fun rapidProfileSwitch_noCrash_counterMatches() {
        AudioEngine(48000).use { engine ->
            val before = engine.readDiagnostics().configSwapCount
            val attempts = 100
            repeat(attempts) { i ->
                val gain = if (i % 2 == 0) 3f else -3f
                val rc = engine.updateAutoEq(
                    preampDB = 0f,
                    enableLimiter = false,
                    profileOptimizedRate = 48000.0,
                    filterTypes = intArrayOf(EqFilterType.PEAKING.nativeId),
                    frequencies = floatArrayOf(1000f + i),
                    gainsDB = floatArrayOf(gain),
                    qFactors = floatArrayOf(1f),
                )
                assertEquals("update $i must succeed", 0, rc)
            }
            val after = engine.readDiagnostics().configSwapCount
            assertEquals(
                "configSwapCount must increase by exactly the number of updates",
                before + attempts, after,
            )
        }
    }

    /**
     * Phase 1 verification — invalid inputs rejected with negative return codes,
     * never crash the JVM via JNI.
     */
    @Test
    fun invalidInput_isRejectedNotCrashed() {
        AudioEngine(48000).use { engine ->
            // Frequency <= 0
            var rc = engine.updateAutoEq(
                0f, false, 48000.0,
                intArrayOf(EqFilterType.PEAKING.nativeId),
                floatArrayOf(-100f), floatArrayOf(0f), floatArrayOf(1f),
            )
            assertTrue("negative frequency rejected: rc=$rc", rc < 0)

            // |gain| > 30
            rc = engine.updateAutoEq(
                0f, false, 48000.0,
                intArrayOf(EqFilterType.PEAKING.nativeId),
                floatArrayOf(1000f), floatArrayOf(50f), floatArrayOf(1f),
            )
            assertTrue("gain > 30 rejected: rc=$rc", rc < 0)

            // q <= 0
            rc = engine.updateAutoEq(
                0f, false, 48000.0,
                intArrayOf(EqFilterType.PEAKING.nativeId),
                floatArrayOf(1000f), floatArrayOf(0f), floatArrayOf(-1f),
            )
            assertTrue("negative Q rejected: rc=$rc", rc < 0)

            // After all rejections, no config swap should have occurred.
            val d = engine.readDiagnostics()
            assertEquals(0L, d.configSwapCount)
        }
    }

    /**
     * Phase 4 verification — real GitHub catalog round-trip (or cache hit).
     * Either succeeds with at least 1000 entries or the device is offline AND
     * a previous run populated the cache.
     */
    @Test
    fun catalog_loadsFromGitHub_orFromCache() = runBlocking {
        val repo = AutoEqRepository(ctx)
        val api = AutoEqApi(repo)
        val state = withTimeout(60_000) {
            api.observe(this).first { it !is CatalogState.Loading && it !is CatalogState.Idle }
        }
        when (state) {
            is CatalogState.Loaded -> {
                assertTrue(
                    "Catalog must contain >1000 entries (got ${state.entries.size})",
                    state.entries.size > 1000,
                )
                // Spot-check: oratory1990 entries exist
                val oratoryCount = state.entries.count { it.measuredBy == "oratory1990" }
                assertTrue("oratory1990 should be represented", oratoryCount > 100)
            }
            is CatalogState.Error -> {
                // Acceptable only if we have NO cache. Otherwise something is wrong.
                throw AssertionError("Catalog load failed and no cache available: ${state.message}")
            }
            else -> throw AssertionError("Unexpected state: $state")
        }
    }

    /**
     * Phase 4 verification — search engine returns relevant results from the real
     * 6k+ entry catalog within a reasonable time.
     */
    @Test
    fun catalog_search_returnsRelevant() = runBlocking {
        val repo = AutoEqRepository(ctx)
        val api = AutoEqApi(repo)
        // Drain catalog state to populate the search engine.
        withTimeout(60_000) {
            api.observe(this).first { it is CatalogState.Loaded }
        }

        // Warm-up call: first invocation pays JIT + cache cost on mid-tier devices.
        api.search("warmup")

        val ms = measureTimeMillis {
            val result = api.search("hd 600")
            assertTrue("Should find HD 600", result.entries.isNotEmpty())
            val name = result.entries.first().name.lowercase()
            assertTrue(
                "Top hit should match HD 600 (got '$name')",
                name.contains("hd 600") || name.contains("hd600"),
            )
        }
        // Steady-state budget on a mid-tier device (Phase 4: P95 < 200ms target,
        // P95 < 500ms acceptable on low-tier). Allow generous headroom for Tensor G1
        // big.LITTLE scheduling on the PD20 / MediaTek-class SoC.
        assertTrue("Search latency $ms ms exceeds 1000ms budget", ms < 1000)
    }

    /**
     * Phase 4 + Phase 6 — full pipeline: search → resolve profile → apply to engine.
     * This proves that downloading a real ParametricEQ.txt, parsing, and pushing to
     * native all works end to end on the device.
     */
    @Test
    fun fullPipeline_search_resolve_apply() = runBlocking {
        val repo = AutoEqRepository(ctx)
        val api = AutoEqApi(repo)
        withTimeout(60_000) {
            api.observe(this).first { it is CatalogState.Loaded }
        }

        val results = api.search("hd 600")
        assertTrue("Need at least one result", results.entries.isNotEmpty())
        val entry = results.entries.first()

        val profile = withTimeout(30_000) { api.resolve(entry) }
        assertNotNull("Profile must resolve from network or cache", profile)
        val validated = profile!!.validated()
        assertTrue("Profile must have at least one filter", validated.filters.isNotEmpty())

        AudioEngine(48000).use { engine ->
            val n = validated.filters.size
            val types = IntArray(n)
            val freqs = FloatArray(n)
            val gains = FloatArray(n)
            val qs = FloatArray(n)
            for (i in 0 until n) {
                val f = validated.filters[i]
                types[i] = f.type.nativeId
                freqs[i] = f.frequency.toFloat()
                gains[i] = f.gainDB
                qs[i] = f.q.toFloat()
            }
            val rc = engine.updateAutoEq(
                preampDB = validated.preampDB,
                enableLimiter = true,
                profileOptimizedRate = validated.optimizedSampleRate,
                filterTypes = types,
                frequencies = freqs,
                gainsDB = gains,
                qFactors = qs,
            )
            assertEquals(
                "Real ${validated.name} profile must apply cleanly (rc=$rc)",
                0, rc,
            )
            assertTrue(engine.readDiagnostics().configSwapCount > 0)
        }
    }

    /**
     * Phase 4 — second cold instance hits cache (no network round trip needed).
     */
    @Test
    fun catalog_cacheHit_isFast() = runBlocking {
        // First load — may hit network. After this, catalog.json is on disk.
        AutoEqRepository(ctx).also { r1 ->
            withTimeout(60_000) {
                AutoEqApi(r1).observe(this).first { it is CatalogState.Loaded }
            }
        }
        // Second instance with a fresh repository — should hit the disk cache fast.
        val ms = measureTimeMillis {
            val r2 = AutoEqRepository(ctx)
            withTimeout(5_000) {
                AutoEqApi(r2).observe(this).first { it is CatalogState.Loaded }
            }
        }
        assertTrue("Cache hit must complete <2000ms (took $ms ms)", ms < 2000)
    }
}
