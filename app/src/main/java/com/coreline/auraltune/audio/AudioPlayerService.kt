// AudioPlayerService.kt
// Phase 0 MVP audio pipeline: AudioTrack Float32 stereo loop driving the native AutoEQ.
// NOT a Service component — Foreground Service is out of MVP scope. Audio plays only while
// the activity is in the foreground.
package com.coreline.auraltune.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import com.coreline.audio.AudioEngine
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives an [AudioTrack] in `ENCODING_PCM_FLOAT` stereo mode and routes every block through
 * the native [AudioEngine] before [AudioTrack.write].
 *
 * Phase 0 decisions reflected here:
 *  - AudioTrack Float32 loop is the chosen MVP pipeline (NOT Oboe / NOT ExoPlayer)
 *  - Foreground Service is OUT of scope; this is a plain class held by the UI layer
 *  - Engine ownership is the caller's; we only stop and join the thread on close()
 *
 * Lifecycle invariant (post P0-3):
 *  - The engine MUST NOT be closed before this service's audio thread is provably stopped.
 *  - [stop] guarantees: AudioTrack is paused/flushed/released to unblock any pending
 *    `write(WRITE_BLOCKING)`, the audio thread is joined, and only after that does the
 *    function return. Callers may then safely call `engine.close()`.
 *  - The audio thread reads `engine.process(...)` on every loop iteration, so there must
 *    NEVER be a window where the native handle is freed while [running] is still true.
 *
 * Thread model:
 *  - The single playback thread is the only one calling [AudioEngine.process]
 *  - Configuration commands come from the control thread via the engine's RT-safe publish
 *  - [start] / [stop] / [close] must be invoked from the control (main) thread
 */
class AudioPlayerService(
    private val engine: AudioEngine,
    private val sampleRate: Int = 48_000,
    private val framesPerBuffer: Int = DEFAULT_FRAMES_PER_BUFFER,
) : Closeable {

    /** A pull-style provider: fill `out` with up to `numFrames * 2` interleaved samples; return frames written. */
    fun interface SamplesProvider {
        fun read(out: FloatArray, numFrames: Int): Int
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile
    private var audioTrack: AudioTrack? = null

    /**
     * Tracks how many AudioTrack underrun frames we've already attributed to the engine's
     * xrun counter. AudioTrack reports a monotonically increasing total, so we publish only
     * the delta on each loop iteration.
     */
    private var lastUnderrunCount: Int = 0

    /**
     * Start the audio thread. Returns immediately; the loop runs until [stop] / [close].
     * If already running this is a no-op.
     */
    fun start(provider: SamplesProvider) {
        if (running.get()) return
        val track = buildAudioTrack(sampleRate, framesPerBuffer) ?: run {
            Log.w(TAG, "Failed to build AudioTrack — aborting start()")
            return
        }
        audioTrack = track
        running.set(true)
        lastUnderrunCount = 0

        thread = Thread({
            try {
                runLoop(track, provider)
            } catch (t: Throwable) {
                Log.e(TAG, "Audio loop crashed", t)
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }, "AuralTune-AudioOut").also { t ->
            t.isDaemon = true
            t.start()
        }
    }

    /**
     * Signal the audio loop to stop and join the thread. Safe to call from main thread.
     *
     * Lifecycle guarantees (P0-3 fix):
     *  1. Set `running = false` so the loop exits at its next check.
     *  2. If the loop is blocked inside `AudioTrack.write(WRITE_BLOCKING)`, calling
     *     `pause() + flush()` returns the underlying ALSA/AAudio resources and unblocks
     *     `write()` with a -1 / short-write result.
     *  3. Join with a generous timeout. If the thread is STILL alive after that we
     *     escalate by interrupting it. We only return once `thread.isAlive == false`.
     *  4. Only after the thread has fully exited do we null out the references, so any
     *     subsequent `engine.close()` call sees a stopped pipeline.
     *
     * Note: this method is idempotent and may be called from any control-thread context.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return

        // Step 2: unblock any pending write(WRITE_BLOCKING) by pausing + flushing.
        // We don't release here — the loop's `finally` block does the actual release
        // so the audio thread sees a consistent track for the duration of the loop.
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }

        val t = thread
        if (t != null) {
            t.join(STOP_JOIN_TIMEOUT_MS)

            // Escalation path: if the thread is still alive, interrupt and join longer.
            // This shouldn't happen in healthy paths, but a stuck native call (e.g. a
            // misbehaving HAL) shouldn't strand us here forever — log and report.
            if (t.isAlive) {
                Log.w(TAG, "Audio thread did not stop within ${STOP_JOIN_TIMEOUT_MS}ms — interrupting")
                runCatching { t.interrupt() }
                t.join(STOP_HARD_JOIN_TIMEOUT_MS)
                if (t.isAlive) {
                    // We are now in a bad state: the audio thread is still alive and
                    // potentially still calling engine.process(). The caller MUST NOT
                    // close the engine until this resolves. We surface this as an
                    // exception so callers don't silently UAF.
                    throw IllegalStateException(
                        "Audio thread alive after ${STOP_JOIN_TIMEOUT_MS + STOP_HARD_JOIN_TIMEOUT_MS}ms; " +
                            "do NOT close the engine until pipeline is unblocked"
                    )
                }
            }
        }

        thread = null
        audioTrack = null
    }

    /**
     * Close the player. Equivalent to [stop]. Does NOT close the engine — the engine's
     * lifetime is owned by the caller (typically the ServiceLocator or ViewModel).
     * Callers must invoke `engine.close()` AFTER this returns.
     */
    override fun close() {
        stop()
    }

    // ----------------- Loop body -----------------
    private fun runLoop(track: AudioTrack, provider: SamplesProvider) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        // Direct buffer is the canonical contract for AudioEngine.process().
        val byteBuffer: ByteBuffer = ByteBuffer
            .allocateDirect(framesPerBuffer * 2 /*channels*/ * 4 /*bytes per Float32*/)
            .order(ByteOrder.nativeOrder())
        val floatView = byteBuffer.asFloatBuffer()
        val scratch = FloatArray(framesPerBuffer * 2)

        track.play()

        while (running.get()) {
            val frames = provider.read(scratch, framesPerBuffer)
            if (frames <= 0) {
                // Nothing to play; back off briefly to avoid spinning.
                try { Thread.sleep(IDLE_BACKOFF_MS) } catch (_: InterruptedException) { break }
                continue
            }

            // Stage samples into the direct buffer then run native EQ in-place.
            floatView.position(0)
            floatView.put(scratch, 0, frames * 2)
            byteBuffer.position(0)
            byteBuffer.limit(frames * 2 * 4)

            val rc = engine.process(byteBuffer, frames)
            if (rc < 0) {
                Log.w(TAG, "engine.process returned $rc")
            }

            // Blocking write until AudioTrack accepts the bytes; back-pressure flows naturally.
            byteBuffer.position(0)
            val bytesToWrite = frames * 2 * 4
            val written = track.write(byteBuffer, bytesToWrite, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                Log.w(TAG, "AudioTrack.write returned error $written; aborting loop")
                break
            }

            // Phase 6 telemetry: propagate AudioTrack underruns into the engine's xrun
            // counter. AudioTrack.getUnderrunCount() returns a cumulative total since
            // construction, so we publish only the delta. API 24+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val total = track.underrunCount
                val delta = total - lastUnderrunCount
                if (delta > 0) {
                    engine.recordXrun(delta.toLong())
                    lastUnderrunCount = total
                }
            }
        }
    }

    // ----------------- AudioTrack construction -----------------
    private fun buildAudioTrack(sampleRate: Int, framesPerBuffer: Int): AudioTrack? = try {
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_FLOAT
        val minBufferBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            .coerceAtLeast(framesPerBuffer * 2 * 4 * MIN_BUFFER_MULTIPLIER)

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .setEncoding(encoding)
            .build()

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val builder = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        builder.build().takeIf { it.state == AudioTrack.STATE_INITIALIZED } ?: run {
            Log.w(TAG, "AudioTrack.STATE_UNINITIALIZED")
            null
        }
    } catch (t: Throwable) {
        Log.e(TAG, "buildAudioTrack failed", t)
        null
    }

    companion object {
        private const val TAG = "AudioPlayerService"
        const val DEFAULT_FRAMES_PER_BUFFER = 480 // ~10 ms @ 48 kHz, low-latency target
        private const val MIN_BUFFER_MULTIPLIER = 2
        private const val IDLE_BACKOFF_MS = 5L
        // Soft join: 1 buffer worth of latency × ~4 should be plenty in healthy paths.
        private const val STOP_JOIN_TIMEOUT_MS = 1500L
        // Hard join after interrupt: protect against a stuck HAL write.
        private const val STOP_HARD_JOIN_TIMEOUT_MS = 1500L

        @Suppress("unused")
        private val UNUSED_AUDIO_MANAGER_HINT = AudioManager.STREAM_MUSIC
    }
}
