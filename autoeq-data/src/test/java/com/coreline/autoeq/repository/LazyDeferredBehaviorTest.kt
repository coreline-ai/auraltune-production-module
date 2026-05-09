package com.coreline.autoeq.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pre-verification for the proposed fix to Issue #5+6 in
 * [AutoEqRepository.fetchProfile]: replacing
 *
 *     val d = scope.async { work() }
 *     d.invokeOnCompletion { inflight.remove(id, d) }
 *
 * with
 *
 *     val d = scope.async(start = CoroutineStart.LAZY) { work() }
 *     d.invokeOnCompletion { inflight.remove(id, d) }
 *     d.start()
 *
 * The new pattern depends on five kotlinx-coroutines behavioral guarantees.
 * If any of them does not hold, the proposed fix has subtle bugs we need to
 * handle. These tests pin those guarantees against the kotlinx version we
 * actually link against — purely against the kotlinx public API, with NO
 * involvement of our production code, so a kotlinx upgrade that violates
 * the assumption shows up here.
 *
 * The five guarantees (one test each, plus one regression of the original
 * race on the eager `async` form for contrast):
 *
 *  G1. `scope.async(start = LAZY) { ... }` returns a Deferred whose block has
 *      not started — the body cannot complete before [start] is called.
 *  G2. [Deferred.invokeOnCompletion] registered on a LAZY deferred fires
 *      exactly once when the deferred reaches a terminal state (success,
 *      failure, or cancellation).
 *  G3. If [CoroutineScope.cancel] runs *between* `async(LAZY)` and `start`,
 *      the deferred transitions to CANCELLED and `invokeOnCompletion` fires —
 *      the block never runs.
 *  G4. Calling [Deferred.start] on a deferred whose parent scope has already
 *      been cancelled is a no-op (returns false) and does NOT dispatch the
 *      block. The deferred remains/becomes cancelled.
 *  G5. The eager `async` form (current code) has a theoretical race where
 *      the deferred could complete before `invokeOnCompletion` is registered —
 *      with `Dispatchers.Unconfined` or `CoroutineStart.UNDISPATCHED` this is
 *      observable, validating that #5+6 is a real concern (not a phantom).
 */
class LazyDeferredBehaviorTest {

    // ───────────────────────────── G1 ─────────────────────────────

    @Test
    fun `G1 - LAZY async block does not run before start()`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ran = AtomicInteger(0)
        try {
            val d = scope.async(start = CoroutineStart.LAZY) {
                ran.incrementAndGet()
                "ok"
            }
            // Give any rogue dispatch a clear chance to fire — none should.
            kotlinx.coroutines.delay(50)
            assertEquals("LAZY block must not run before start()", 0, ran.get())
            assertFalse("LAZY deferred must not be completed", d.isCompleted)

            // After start, it eventually runs.
            d.start()
            assertEquals("ok", withTimeout(2_000) { d.await() })
            assertEquals(1, ran.get())
        } finally {
            scope.cancel()
        }
    }

    // ───────────────────────────── G2 ─────────────────────────────

    @Test
    fun `G2 - invokeOnCompletion on LAZY deferred fires exactly once on success`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fireCount = AtomicInteger(0)
        try {
            val d = scope.async(start = CoroutineStart.LAZY) { 42 }
            d.invokeOnCompletion { fireCount.incrementAndGet() }
            // Handler must NOT have fired yet — block hasn't run.
            assertEquals(0, fireCount.get())

            d.start()
            assertEquals(42, withTimeout(2_000) { d.await() })
            // Wait briefly for the completion handler to be invoked.
            // invokeOnCompletion fires synchronously on the completing thread,
            // which has been in this case Dispatchers.IO; await() resumed us
            // afterwards, so the handler has already run.
            assertEquals("Handler must fire exactly once on success", 1, fireCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `G2b - invokeOnCompletion fires once on cancellation too`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fireCount = AtomicInteger(0)
        try {
            val d = scope.async(start = CoroutineStart.LAZY) {
                kotlinx.coroutines.delay(5_000) // would block long enough to be cancelled
                "should not reach"
            }
            d.invokeOnCompletion { fireCount.incrementAndGet() }
            d.start()
            d.cancel()
            // Wait for cancellation to settle
            try {
                d.await()
            } catch (_: CancellationException) {
                // expected
            }
            assertEquals("Handler must fire exactly once on cancellation", 1, fireCount.get())
        } finally {
            scope.cancel()
        }
    }

    // ───────────────────────────── G3 ─────────────────────────────

    @Test
    fun `G3 - scope cancellation between async(LAZY) and start() cancels deferred and fires handler`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fireCount = AtomicInteger(0)
        val ran = AtomicInteger(0)

        val d = scope.async(start = CoroutineStart.LAZY) {
            // Block must NOT run — scope is cancelled before start().
            ran.incrementAndGet()
            "should not reach"
        }
        d.invokeOnCompletion { fireCount.incrementAndGet() }

        // Cancel the scope BEFORE we ever call start().
        scope.cancel()

        // start() now: must be a no-op or trigger immediate cancellation.
        d.start()

        // Wait for terminal state.
        try {
            withTimeout(2_000) { d.await() }
        } catch (_: CancellationException) {
            // expected
        }

        assertEquals("Block must not have run", 0, ran.get())
        assertTrue("Deferred must be in cancelled state", d.isCancelled)
        assertEquals("Handler must fire exactly once", 1, fireCount.get())
    }

    // ───────────────────────────── G4 ─────────────────────────────

    @Test
    fun `G4 - start() on a deferred whose scope is already cancelled is a no-op for the block`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ran = AtomicInteger(0)
        val d = scope.async(start = CoroutineStart.LAZY) {
            ran.incrementAndGet()
            "should not reach"
        }
        scope.cancel()
        // Documented behavior: start() returns false because the deferred is
        // already cancelled. The block must NOT execute.
        val started = d.start()
        // After cancellation the start() call cannot transition NEW → ACTIVE;
        // either returns false outright, or returns true but the dispatched
        // block immediately observes cancellation and bails. Either way, the
        // block body must not run to completion.
        kotlinx.coroutines.delay(50) // give any rogue dispatch time
        assertEquals(
            "Block body must not have executed after scope.cancel() + start(): started=$started",
            0,
            ran.get(),
        )
        assertTrue(d.isCancelled)
    }

    // ───────────────────────────── G5 ─────────────────────────────

    /**
     * Demonstrates the original race that motivates the LAZY fix. With
     * `Dispatchers.Unconfined` and `CoroutineStart.UNDISPATCHED`, the deferred
     * starts running synchronously on the calling thread. If the body
     * completes before we register `invokeOnCompletion`, the handler fires
     * synchronously WHILE we're still inside the registration call site. With
     * the eager-async pattern that means a `computeIfAbsent` lambda would see
     * its registered cleanup fire before the value is even returned to the
     * map — orphaning the entry.
     *
     * This test does NOT use a CHM (it would deadlock per CHM contract), but
     * it observes the equivalent symptom: handler fires before the line that
     * registered it has finished returning.
     */
    @Test
    fun `G5 - eager UNDISPATCHED async can complete before invokeOnCompletion registers`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // Use a CompletableDeferred to simulate "work that's already done"
        // by the time the parent body runs — this is the failure mode we're
        // protecting against.
        val workDone = CompletableDeferred<Int>().also { it.complete(7) }
        val d = scope.async(start = CoroutineStart.UNDISPATCHED) {
            workDone.await()
        }
        // d should already be completed at this point — no dispatch hop in
        // Unconfined + UNDISPATCHED, and workDone is already complete.
        assertTrue(
            "G5 precondition: with Unconfined+UNDISPATCHED, the deferred " +
                "completes synchronously before this line",
            d.isCompleted,
        )

        // Now register invokeOnCompletion — kotlinx documents that the
        // handler fires SYNCHRONOUSLY when registered on an already-
        // completed Deferred.
        val handlerThread = arrayOfNulls<String>(1)
        val testThread = Thread.currentThread().name
        d.invokeOnCompletion {
            handlerThread[0] = Thread.currentThread().name
        }
        // The handler ran synchronously on OUR thread — proving that with
        // the eager-async + Dispatchers.Unconfined combination, the fix
        // proposed in #5+6 is non-trivial and the LAZY pattern actually
        // closes a real (currently unreachable but real) race.
        assertEquals(
            "G5: handler must fire synchronously on the registering thread",
            testThread,
            handlerThread[0],
        )
        scope.cancel()
    }
}
