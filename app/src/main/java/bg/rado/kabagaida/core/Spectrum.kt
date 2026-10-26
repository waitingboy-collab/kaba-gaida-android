package bg.rado.kabagaida.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Радикс-2 FFT „на място“. Права трансформация: X[k] = Σ x[n]·e^(−i2πkn/N). */
class Fft(val size: Int) {
    private val cosT: DoubleArray
    private val sinT: DoubleArray
    private val rev: IntArray

    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "Размерът на FFT трябва да е степен на 2" }
        cosT = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
        sinT = DoubleArray(size / 2) { -sin(2.0 * PI * it / size) }
        val bits = Integer.numberOfTrailingZeros(size)
        rev = IntArray(size) { Integer.reverse(it) ushr (32 - bits) }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = sinT[k * step]
                    val a = i + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Точният комплексен спектър на периодичен прозорец на Хан за синусоида,
 * отместена на [delta] бина от даден бин: H(δ) = Σ w[n]·e^(i2πδn/N).
 * Използва се за напасване на синусоиди директно в спектъра (по-точно от параболична интерполация,
 * и позволява да се разделят два почти съвпадащи тона — гайдуница и хармоник на ручилото).
 */
object HannKernel {
    /** Записва Re/Im на H(delta) в out[0], out[1]. */
    fun eval(delta: Double, n: Int, out: DoubleArray) {
        var r = 0.0
        var i = 0.0
        for (j in -1..1) {
            val c = if (j == 0) 0.5 else -0.25
            val d = delta + j
            val den = sin(PI * d / n)
            val mag: Double = if (abs(den) < 1e-12) n.toDouble() else sin(PI * d) / den
            val ph = PI * d * (1.0 - 1.0 / n)
            r += c * mag * cos(ph)
            i += c * mag * sin(ph)
        }
        out[0] = r; out[1] = i
    }

    fun magnitude(delta: Double, n: Int, tmp: DoubleArray = DoubleArray(2)): Double {
        eval(delta, n, tmp)
        return hypot(tmp[0], tmp[1])
    }
}

/** Спектър на един кадър с прозорец на Хан, плюс помощни функции за напасване на синусоиди. */
class Spectrum(val n: Int, val sampleRate: Double) {
    val re = DoubleArray(n)
    val im = DoubleArray(n)
    val mag = DoubleArray(n / 2 + 1)
    private val fft = Fft(n)
    private val window = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / n) }
    val binHz: Double get() = sampleRate / n

    fun load(frame: FloatArray) {
        require(frame.size >= n)
        val off = frame.size - n
        for (k in 0 until n) { re[k] = frame[off + k] * window[k]; im[k] = 0.0 }
        fft.transform(re, im)
        for (k in 0..n / 2) mag[k] = hypot(re[k], im[k])
    }

    fun load(frame: DoubleArray) {
        require(frame.size >= n)
        val off = frame.size - n
        for (k in 0 until n) { re[k] = frame[off + k] * window[k]; im[k] = 0.0 }
        fft.transform(re, im)
        for (k in 0..n / 2) mag[k] = hypot(re[k], im[k])
    }

    fun hzToBin(hz: Double) = hz / binHz
    fun binToHz(bin: Double) = bin * binHz

    /** Медиана на амплитудата в диапазон от честоти — оценка на шума. */
    fun medianMagnitude(fromHz: Double, toHz: Double): Double {
        val a = hzToBin(fromHz).toInt().coerceIn(1, n / 2)
        val b = hzToBin(toHz).toInt().coerceIn(a + 1, n / 2)
        val copy = mag.copyOfRange(a, b)
        copy.sort()
        return copy[copy.size / 2]
    }
}

/** Резултат от напасване на синусоида: честота в бинове и комплексна амплитуда. */
class SineFit(val bin: Double, val ampRe: Double, val ampIm: Double, val residual: Double, val energy: Double) {
    val amplitude: Double get() = hypot(ampRe, ampIm)
}

/**
 * Напасване на една или две синусоиди към малък прозорец от бинове чрез най-малки квадрати.
 * Всички честоти са в бинове (дробни).
 */
class SineFitter(private val spec: Spectrum) {
    private val n = spec.n
    private val tmp = DoubleArray(2)
    private val maxW = 32
    private val kRe = DoubleArray(maxW); private val kIm = DoubleArray(maxW)
    private val dRe = DoubleArray(maxW); private val dIm = DoubleArray(maxW)
    private val yRe = DoubleArray(maxW); private val yIm = DoubleArray(maxW)

    private fun kernel(nu: Double, k0: Int, w: Int, outRe: DoubleArray, outIm: DoubleArray) {
        for (j in 0 until w) {
            HannKernel.eval(nu - (k0 + j), n, tmp)
            outRe[j] = tmp[0]; outIm[j] = tmp[1]
        }
    }

    private fun window(lo: Double, hi: Double): Pair<Int, Int> {
        val k0 = (Math.floor(lo) - 3).toInt().coerceAtLeast(1)
        val k1 = (Math.ceil(hi) + 3).toInt().coerceAtMost(n / 2 - 1)
        val w = (k1 - k0 + 1).coerceAtMost(maxW)
        return k0 to w
    }

    private fun energy(k0: Int, w: Int): Double {
        var e = 0.0
        for (j in 0 until w) { val k = k0 + j; e += spec.re[k] * spec.re[k] + spec.im[k] * spec.im[k] }
        return e
    }

    /** Амплитуда на синусоида с точно известна честота (без търсене). */
    fun amplitudeAt(nu: Double): SineFit {
        val (k0, w) = window(nu, nu)
        kernel(nu, k0, w, kRe, kIm)
        var kk = 0.0; var pr = 0.0; var pi = 0.0
        for (j in 0 until w) {
            val xr = spec.re[k0 + j]; val xi = spec.im[k0 + j]
            kk += kRe[j] * kRe[j] + kIm[j] * kIm[j]
            pr += kRe[j] * xr + kIm[j] * xi
            pi += kRe[j] * xi - kIm[j] * xr
        }
        val e = energy(k0, w)
        return SineFit(nu, pr / kk, pi / kk, e - (pr * pr + pi * pi) / kk, e)
    }

    /** Една синусоида: търси честотата в [center − range, center + range] бина. */
    fun fitSingle(center: Double, range: Double): SineFit {
        val (k0, w) = window(center - range, center + range)
        val e = energy(k0, w)
        fun score(nu: Double): Double {
            kernel(nu, k0, w, kRe, kIm)
            var kk = 0.0; var pr = 0.0; var pi = 0.0
            for (j in 0 until w) {
                val xr = spec.re[k0 + j]; val xi = spec.im[k0 + j]
                kk += kRe[j] * kRe[j] + kIm[j] * kIm[j]
                pr += kRe[j] * xr + kIm[j] * xi
                pi += kRe[j] * xi - kIm[j] * xr
            }
            return (pr * pr + pi * pi) / kk
        }
        val steps = 24
        var best = center; var bestS = -1.0
        for (s in 0..steps) {
            val nu = center - range + 2 * range * s / steps
            val sc = score(nu)
            if (sc > bestS) { bestS = sc; best = nu }
        }
        // златно сечение около най-добрата точка
        var a = best - 2 * range / steps; var b = best + 2 * range / steps
        val g = 0.6180339887498949
        var c = b - g * (b - a); var d = a + g * (b - a)
        var fc = score(c); var fd = score(d)
        repeat(30) {
            if (fc > fd) { b = d; d = c; fd = fc; c = b - g * (b - a); fc = score(c) }
            else { a = c; c = d; fc = fd; d = a + g * (b - a); fd = score(d) }
        }
        val nu = (a + b) / 2
        val fit = amplitudeAtWindow(nu, k0, w)
        return SineFit(nu, fit[0], fit[1], e - score(nu), e)
    }

    private fun amplitudeAtWindow(nu: Double, k0: Int, w: Int): DoubleArray {
        kernel(nu, k0, w, kRe, kIm)
        var kk = 0.0; var pr = 0.0; var pi = 0.0
        for (j in 0 until w) {
            val xr = spec.re[k0 + j]; val xi = spec.im[k0 + j]
            kk += kRe[j] * kRe[j] + kIm[j] * kIm[j]
            pr += kRe[j] * xr + kIm[j] * xi
            pi += kRe[j] * xi - kIm[j] * xr
        }
        return doubleArrayOf(pr / kk, pi / kk)
    }

    /**
     * Две синусоиди: позната честота [droneNu] (хармоник на ручилото, амплитудата е свободна)
     * и търсена честота около [center]. Подходящо, когато двата тона са на ≥ ~1.2 бина един от друг.
     */
    fun fitBesideDrone(center: Double, range: Double, droneNu: Double): SineFit {
        val (k0, w) = window(minOf(center - range, droneNu), maxOf(center + range, droneNu))
        kernel(droneNu, k0, w, dRe, dIm)
        val e = energy(k0, w)
        // предварително: <D,D>, <D,X>
        var dd = 0.0; var dxR = 0.0; var dxI = 0.0
        for (j in 0 until w) {
            val xr = spec.re[k0 + j]; val xi = spec.im[k0 + j]
            dd += dRe[j] * dRe[j] + dIm[j] * dIm[j]
            dxR += dRe[j] * xr + dIm[j] * xi
            dxI += dRe[j] * xi - dIm[j] * xr
        }
        val out = DoubleArray(3)
        fun solve(nu: Double): Double {
            kernel(nu, k0, w, kRe, kIm)
            var cc = 0.0; var cdR = 0.0; var cdI = 0.0; var cxR = 0.0; var cxI = 0.0
            for (j in 0 until w) {
                val xr = spec.re[k0 + j]; val xi = spec.im[k0 + j]
                cc += kRe[j] * kRe[j] + kIm[j] * kIm[j]
                // <C,D> = Σ conj(C)·D
                cdR += kRe[j] * dRe[j] + kIm[j] * dIm[j]
                cdI += kRe[j] * dIm[j] - kIm[j] * dRe[j]
                cxR += kRe[j] * xr + kIm[j] * xi
                cxI += kRe[j] * xi - kIm[j] * xr
            }
            // Система: [cc  cd; dc  dd][ac; ad] = [cx; dx], dc = conj(cd)
            val det = cc * dd - (cdR * cdR + cdI * cdI)
            if (det <= 1e-12 * cc * dd) { out[0] = 0.0; out[1] = 0.0; out[2] = e; return e }
            // ac = (dd·cx − cd·dx)/det
            val nR = dd * cxR - (cdR * dxR - cdI * dxI)
            val nI = dd * cxI - (cdR * dxI + cdI * dxR)
            val acR = nR / det; val acI = nI / det
            // ad = (cc·dx − dc·cx)/det, dc = conj(cd)
            val mR = cc * dxR - (cdR * cxR + cdI * cxI)
            val mI = cc * dxI - (cdR * cxI - cdI * cxR)
            val adR = mR / det; val adI = mI / det
            // остатък = e − Re(conj(ac)·cx + conj(ad)·dx)
            val proj = acR * cxR + acI * cxI + adR * dxR + adI * dxI
            out[0] = acR; out[1] = acI; out[2] = e - proj
            return e - proj
        }
        val steps = 40
        var best = center; var bestR = Double.MAX_VALUE
        for (s in 0..steps) {
            val nu = center - range + 2 * range * s / steps
            val r = solve(nu)
            if (r < bestR) { bestR = r; best = nu }
        }
        var a = best - 2 * range / steps; var b = best + 2 * range / steps
        val g = 0.6180339887498949
        var c = b - g * (b - a); var d = a + g * (b - a)
        var fc = solve(c); var fd = solve(d)
        repeat(30) {
            if (fc < fd) { b = d; d = c; fd = fc; c = b - g * (b - a); fc = solve(c) }
            else { a = c; c = d; fc = fd; d = a + g * (b - a); fd = solve(d) }
        }
        val nu = (a + b) / 2
        val r = solve(nu)
        return SineFit(nu, out[0], out[1], r, e)
    }

    /**
     * Почти съвпадение с хармоник на ручилото (< ~1.2 бина): двата тона не могат да се разделят
     * свободно, затова амплитудата на ручилото се фиксира на [droneAmp] (от профила при калибриране,
     * мащабиран по текущото налягане), а търсим фазата му и честотата/амплитудата на гайдуницата.
     */
    fun fitOnDrone(center: Double, range: Double, droneNu: Double, droneAmp: Double): SineFit {
        val (k0, w) = window(minOf(center - range, droneNu), maxOf(center + range, droneNu))
        kernel(droneNu, k0, w, dRe, dIm)
        val e = energy(k0, w)
        val result = DoubleArray(3)
        fun evalAt(nu: Double, phi: Double, store: Boolean): Double {
            val cr = droneAmp * cos(phi); val ci = droneAmp * sin(phi)
            var yy = 0.0
            for (j in 0 until w) {
                val xr = spec.re[k0 + j]; val xi = spec.im[k0 + j]
                yRe[j] = xr - (cr * dRe[j] - ci * dIm[j])
                yIm[j] = xi - (cr * dIm[j] + ci * dRe[j])
                yy += yRe[j] * yRe[j] + yIm[j] * yIm[j]
            }
            var cc = 0.0; var pr = 0.0; var pi = 0.0
            for (j in 0 until w) {
                cc += kRe[j] * kRe[j] + kIm[j] * kIm[j]
                pr += kRe[j] * yRe[j] + kIm[j] * yIm[j]
                pi += kRe[j] * yIm[j] - kIm[j] * yRe[j]
            }
            val res = yy - (pr * pr + pi * pi) / cc
            if (store) { result[0] = pr / cc; result[1] = pi / cc; result[2] = res }
            return res
        }
        var bestNu = center; var bestPhi = 0.0; var bestR = Double.MAX_VALUE
        val fSteps = 60; val pSteps = 48
        for (s in 0..fSteps) {
            val nu = center - range + 2 * range * s / fSteps
            kernel(nu, k0, w, kRe, kIm)
            for (p in 0 until pSteps) {
                val phi = 2 * PI * p / pSteps
                val r = evalAt(nu, phi, false)
                if (r < bestR) { bestR = r; bestNu = nu; bestPhi = phi }
            }
        }
        // фино уточняване: координатно спускане по честота и фаза
        var dNu = 2 * range / fSteps
        var dPhi = 2 * PI / pSteps
        repeat(12) {
            for (s in -4..4) {
                val nu = bestNu + dNu * s / 4
                kernel(nu, k0, w, kRe, kIm)
                for (p in -4..4) {
                    val phi = bestPhi + dPhi * p / 4
                    val r = evalAt(nu, phi, false)
                    if (r < bestR) { bestR = r; bestNu = nu; bestPhi = phi }
                }
            }
            dNu *= 0.5; dPhi *= 0.5
        }
        kernel(bestNu, k0, w, kRe, kIm)
        evalAt(bestNu, bestPhi, true)
        return SineFit(bestNu, result[0], result[1], result[2], e)
    }
}
