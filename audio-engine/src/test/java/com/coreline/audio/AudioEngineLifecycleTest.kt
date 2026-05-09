package com.coreline.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Host-JVM unit tests for the R2-1 [AudioEngine.useInAudioSession] DSL.
 *
 * The DSL exists to compile-time-enforce the lifecycle ordering
 *   audio thread stop / join (joiner) → engine.close
 * against integrators who would otherwise call `engine.close()` while the
 * audio thread is still inside `process(...)` (use-after-free on the native
 * handle).
 *
 * These tests verify the three-stage pipeline (block → joiner → close) and
 * the exception-precedence contract (block > joiner > close, with losers
 * attached as suppressed). They lean on [ShadowAudioEngine] to stub out the
 * native methods — the on-device lifecycle ordering is identical, this just
 * lets us run on the host JVM where `libauraltune_audio.so` is absent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [33],
    shadows = [ShadowAudioEngine::class],
)
class AudioEngineLifecycleTest {

    private fun newEngine(): AudioEngine = AudioEngine(48_000)

    @Test
    fun `happy path returns block result, runs joiner, closes engine`() {
        val events = mutableListOf<String>()
        val engine = newEngine()
        val joinerCalls = intArrayOf(0)

        val result = engine.useInAudioSession(
            audioThreadJoiner = {
                joinerCalls[0]++
                events += "joiner"
            },
        ) { eng ->
            assertSame(engine, eng)
            events += "block"
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(1, joinerCalls[0])
        // Engine must be closed afterwards: process() short-circuits on a
        // zeroed handleRef (returns -1 without dereferencing).
        val buf = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
        assertEquals(-1, engine.process(buf, 8))
        // And a second useInAudioSession on a closed engine must reject.
        assertThrows(IllegalArgumentException::class.java) {
            engine.useInAudioSession(audioThreadJoiner = {}) { /* unreachable */ }
        }
    }

    @Test
    fun `block throws — joiner still called, close still called, block exception propagates`() {
        val events = mutableListOf<String>()
        val engine = newEngine()
        val blockEx = RuntimeException("block-boom")

        val thrown = assertThrows(RuntimeException::class.java) {
            engine.useInAudioSession(
                audioThreadJoiner = { events += "joiner" },
            ) { _ ->
                events += "block"
                throw blockEx
            }
        }

        assertSame(blockEx, thrown)
        // Joiner must have run between block and close.
        assertEquals(listOf("block", "joiner"), events)
        // Engine must be closed even though block threw.
        val buf = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
        assertEquals(-1, engine.process(buf, 8))
    }

    @Test
    fun `joiner throws — close still called, joiner exception propagates`() {
        val events = mutableListOf<String>()
        val engine = newEngine()
        val joinerEx = IllegalStateException("joiner-boom")

        val thrown = assertThrows(IllegalStateException::class.java) {
            engine.useInAudioSession(
                audioThreadJoiner = {
                    events += "joiner"
                    throw joinerEx
                },
            ) { _ ->
                events += "block"
            }
        }

        assertSame(joinerEx, thrown)
        assertEquals(listOf("block", "joiner"), events)
        // Close must still run despite joiner throwing.
        val buf = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
        assertEquals(-1, engine.process(buf, 8))
    }

    @Test
    fun `block and joiner both throw — block wins, joiner attached as suppressed`() {
        val engine = newEngine()
        val blockEx = RuntimeException("block-boom")
        val joinerEx = IllegalStateException("joiner-boom")

        val thrown = assertThrows(RuntimeException::class.java) {
            engine.useInAudioSession(
                audioThreadJoiner = { throw joinerEx },
            ) { _ -> throw blockEx }
        }

        assertSame(blockEx, thrown)
        val suppressed = thrown.suppressed.toList()
        assertTrue(
            "joiner exception must be attached as suppressed on the block exception, was=$suppressed",
            suppressed.any { it === joinerEx },
        )
        // Engine still closed.
        val buf = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
        assertEquals(-1, engine.process(buf, 8))
    }

    @Test
    fun `closed engine rejects useInAudioSession with IllegalArgumentException`() {
        val engine = newEngine()
        engine.close()

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            engine.useInAudioSession(
                audioThreadJoiner = { fail("joiner must not be called on a closed engine") },
            ) { _ -> fail("block must not be called on a closed engine") }
        }
        assertNotNull(thrown.message)
        assertTrue(
            "message should mention closed state, was '${thrown.message}'",
            thrown.message!!.contains("closed", ignoreCase = true),
        )
    }

    @Test
    fun `ordering — block runs before joiner, joiner runs before close`() {
        val events = mutableListOf<String>()
        // We can't directly observe nativeDestroy via the shadow without
        // touching it (out of scope). Instead we observe close()'s side effect
        // on the public API: handleRef is zeroed, so isOpen() flips to false.
        // We append "close" by polling that transition immediately after the
        // joiner (the only place close can run, per the DSL contract).
        val engine = newEngine()
        assertTrue("engine should start open", engine.isOpen())

        engine.useInAudioSession(
            audioThreadJoiner = {
                events += "joiner"
                // close has not happened yet at the moment the joiner is
                // invoked — DSL guarantees joiner runs *before* close.
                assertTrue("close must not have run before joiner returns", engine.isOpen())
            },
        ) { _ ->
            events += "block"
            // joiner must not have run yet either.
            assertEquals(listOf("block"), events)
        }

        // Post-DSL: close has run.
        events += "close"
        assertFalse("engine must be closed after useInAudioSession returns", engine.isOpen())
        assertEquals(listOf("block", "joiner", "close"), events)
    }
}
