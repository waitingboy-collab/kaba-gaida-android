package bg.rado.kabagaida.core

import kotlin.math.ceil
import kotlin.math.floor

data class YinResult(val frequency: Double, val aperiodicity: Double)

/**
 * Алгоритъм YIN (de Cheveigné & Kawahara, 2002) — точен за ниските тонове на ручилото.
 * Използва се при калибриране, когато още не знаем къде е ручилото.
 */
class Yin(
    private val sampleRate: Double,
    minFreq: Double,
    maxFreq: Double,
    private val threshold: Double = 0.12,
) {
    private val tauMin = floor(sampleRate / maxFreq).toInt().coerceAtLeast(2)
    private val tauMax = ceil(sampleRate / minFreq).toInt()
    private val d = DoubleArray(tauMax + 2)

    /** Нужната дължина на кадъра. */
    val requiredLength: Int get() = tauMax + 2 + 2048

    fun detect(x: FloatArray): YinResult? {
        if (x.size < requiredLength) return null
        val off = x.size - requiredLength
        val w = requiredLength - tauMax - 2
        for (tau in 1..tauMax + 1) {
            var s = 0.0
            for (j in 0 until w) {
                val diff = (x[off + j] - x[off + j + tau]).toDouble()
                s += diff * diff
            }
            d[tau] = s
        }
        // кумулативно нормализирана разлика
        d[0] = 1.0
        var running = 0.0
        for (tau in 1..tauMax + 1) {
            running += d[tau]
            d[tau] = if (running == 0.0) 1.0 else d[tau] * tau / running
        }
        var tau = -1
        var t = tauMin
        while (t <= tauMax) {
            if (d[t] < threshold) {
                while (t + 1 <= tauMax && d[t + 1] < d[t]) t++
                tau = t
                break
            }
            t++
        }
        if (tau < 0) {
            var best = tauMin
            for (k in tauMin..tauMax) if (d[k] < d[best]) best = k
            if (d[best] > 0.35) return null
            tau = best
        }
        val a = d[tau - 1]; val b = d[tau]; val c = d[tau + 1]
        val den = a - 2 * b + c
        val shift = if (den > 1e-12) (0.5 * (a - c) / den).coerceIn(-1.0, 1.0) else 0.0
        return YinResult(sampleRate / (tau + shift), b)
    }
}
