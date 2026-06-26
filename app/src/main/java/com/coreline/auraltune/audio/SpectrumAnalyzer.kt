// SpectrumAnalyzer.kt
// 재생 중인 (post-EQ) PCM에서 실시간 주파수 스펙트럼을 계산해 막대 시각화용으로 노출한다.
//   - feed(): 오디오 RT 스레드에서 모노 믹스를 링버퍼에 '복사만'(무할당). FFT는 절대 여기서 안 함.
//   - 백그라운드 코루틴: 최신 FFT_SIZE 샘플 → Hann 창 → radix-2 FFT → 로그 간격 밴드 → 평활 → StateFlow.
// 마이크 권한 불필요(앱 자체 재생 PCM 사용). 구독자가 없으면 계산을 건너뛴다(전력 절약).
package com.coreline.auraltune.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class SpectrumAnalyzer(
    private val bandCount: Int = DEFAULT_BANDS,
) : Closeable {

    // 단일 생산자(오디오 스레드) / 단일 소비자(분석 코루틴) 링버퍼. writePos(volatile)로 happens-before 확립.
    private val ring = FloatArray(RING_SIZE)
    @Volatile private var writePos = 0L
    @Volatile private var sampleRate = 48_000

    private val _spectrum = MutableStateFlow(FloatArray(bandCount))
    /** 밴드별 레벨(0..1), 저주파→고주파. 약 45fps로 갱신. */
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    fun setSampleRate(sr: Int) { if (sr > 0) sampleRate = sr }

    /** 오디오 RT 스레드: post-EQ 인터리브드 float에서 모노 믹스를 링버퍼에 복사(무할당·논블로킹). */
    fun feed(buf: ByteBuffer, frames: Int, channels: Int) {
        if (frames <= 0 || channels <= 0) return
        var w = writePos
        val inv = 1f / channels
        var f = 0
        while (f < frames) {
            val base = (f * channels) * 4 // 절대 바이트 인덱스(buf.position 불변)
            var acc = 0f
            var c = 0
            while (c < channels) { acc += buf.getFloat(base + c * 4); c++ }
            ring[(w and RING_MASK).toInt()] = acc * inv
            w++; f++
        }
        writePos = w
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val window = FloatArray(FFT_SIZE) {
                (0.5 - 0.5 * cos(2.0 * Math.PI * it / (FFT_SIZE - 1))).toFloat() // Hann
            }
            val re = FloatArray(FFT_SIZE)
            val im = FloatArray(FFT_SIZE)
            val target = FloatArray(bandCount)
            val levels = FloatArray(bandCount)
            val loBin = IntArray(bandCount)
            val hiBin = IntArray(bandCount)
            var edgesForSr = -1
            var lastWrite = -1L
            while (isActive) {
                if (_spectrum.subscriptionCount.value > 0) {
                    val w = writePos
                    if (w >= FFT_SIZE && w != lastWrite) {
                        lastWrite = w
                        if (sampleRate != edgesForSr) {
                            SpectrumAnalyzerMath.computeBandEdges(loBin, hiBin, sampleRate)
                            edgesForSr = sampleRate
                        }
                        val startIdx = w - FFT_SIZE
                        for (i in 0 until FFT_SIZE) {
                            re[i] = ring[((startIdx + i) and RING_MASK).toInt()] * window[i]
                            im[i] = 0f
                        }
                        SpectrumAnalyzerMath.fft(re, im)
                        for (b in 0 until bandCount) {
                            var k = loBin[b]
                            val hi = hiBin[b]
                            var sum = 0f
                            var cnt = 0
                            while (k <= hi) {
                                sum += sqrt(re[k] * re[k] + im[k] * im[k]); cnt++; k++
                            }
                            val mean = if (cnt > 0) sum / cnt else 0f
                            val norm = mean / FFT_SIZE * 4f
                            val db = 20f * log10(norm + 1e-7f)
                            target[b] = ((db - DB_FLOOR) / (DB_CEIL - DB_FLOOR)).coerceIn(0f, 1f)
                        }
                    } else {
                        // 새 오디오 없음(일시정지/무음) → 0으로 수렴(자연 감쇠).
                        for (b in 0 until bandCount) target[b] = 0f
                    }
                    // attack 빠름 / decay 느림 — 보기 좋게.
                    for (b in 0 until bandCount) {
                        val t = target[b]
                        levels[b] += (t - levels[b]) * (if (t > levels[b]) ATTACK else DECAY)
                    }
                    _spectrum.value = levels.copyOf() // 새 인스턴스 → Compose 갱신
                }
                delay(FRAME_MS)
            }
        }
    }

    override fun close() {
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val FFT_SIZE = 2048
        private const val RING_SIZE = 8192 // 2^n, 약 170ms@48k
        private const val RING_MASK = (RING_SIZE - 1).toLong()
        private const val DEFAULT_BANDS = 48
        private const val FRAME_MS = 22L // ~45fps
        private const val ATTACK = 0.5f
        private const val DECAY = 0.12f
        // dB 매핑 범위(디바이스 튜닝 가능): FLOOR=바닥, CEIL=꼭대기.
        private const val DB_FLOOR = -66f
        private const val DB_CEIL = -12f
    }
}

internal object SpectrumAnalyzerMath {
    const val FFT_SIZE = 2048

    /** 밴드 b의 FFT 빈 범위(lo..hi)를 로그 간격(40Hz~min(Nyquist,18k))으로 계산. */
    fun computeBandEdges(lo: IntArray, hi: IntArray, sampleRate: Int, fftSize: Int = FFT_SIZE) {
        require(lo.size == hi.size) { "lo/hi size mismatch" }
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(fftSize > 0 && (fftSize and (fftSize - 1)) == 0) { "fftSize must be a power of two" }
        val n = lo.size
        val maxBin = fftSize / 2 - 1
        val binHz = sampleRate.toDouble() / fftSize
        val fMin = 40.0
        val fMax = minOf(sampleRate / 2.0, 18_000.0)
        for (b in 0 until n) {
            val f0 = fMin * Math.pow(fMax / fMin, b.toDouble() / n)
            val f1 = fMin * Math.pow(fMax / fMin, (b + 1.0) / n)
            val b0 = (f0 / binHz).toInt().coerceIn(1, maxBin)
            var b1 = (f1 / binHz).toInt().coerceIn(1, maxBin)
            if (b1 < b0) b1 = b0
            lo[b] = b0
            hi[b] = b1
        }
    }

    /** In-place iterative radix-2 Cooley-Tukey FFT(무할당). [re]/[im] 길이는 2의 거듭제곱. */
    fun fft(re: FloatArray, im: FloatArray) {
        require(re.size == im.size) { "real/imag size mismatch" }
        val n = re.size
        require(n > 0 && (n and (n - 1)) == 0) { "FFT length must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang).toFloat(); val wIm = sin(ang).toFloat()
            val half = len shr 1
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                for (k in 0 until half) {
                    val a = i + k; val bIdx = a + half
                    val tRe = re[bIdx] * curRe - im[bIdx] * curIm
                    val tIm = re[bIdx] * curIm + im[bIdx] * curRe
                    re[bIdx] = re[a] - tRe; im[bIdx] = im[a] - tIm
                    re[a] += tRe; im[a] += tIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
