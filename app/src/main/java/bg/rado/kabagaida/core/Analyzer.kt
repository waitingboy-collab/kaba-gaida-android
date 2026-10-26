package bg.rado.kabagaida.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Тембър на ручилото: честота и средна амплитуда на всеки хармоник (индекс h−1). */
class DroneProfile(val frequency: Double, val amps: DoubleArray) {
    fun lowRef(count: Int): Double {
        var s = 0.0
        for (i in 0 until min(count, amps.size)) s += amps[i]
        return s
    }

    fun serialize(): String = buildString {
        append(frequency)
        for (a in amps) { append(';'); append(a) }
    }

    companion object {
        fun parse(s: String?): DroneProfile? {
            if (s.isNullOrBlank()) return null
            return try {
                val parts = s.split(';').map { it.toDouble() }
                if (parts.size < 4 || parts[0] <= 0.0) null
                else DroneProfile(parts[0], parts.drop(1).toDoubleArray())
            } catch (e: NumberFormatException) { null }
        }
    }
}

data class DroneReading(
    val frequency: Double,
    /** Отклонение от калибрирания тон, в центове (показател за налягането в меха). */
    val centsFromCalibrated: Double,
    /** Сила спрямо калибрирането (1.0 = същата). */
    val relativeLevel: Double,
)

data class ChanterReading(
    val frequency: Double,
    val centsFromTonic: Double,
    val step: StepReading,
    val note: NoteName,
    /** Основният тон съвпада с хармоник на ручилото (тоника, квинта, октава…). */
    val onDroneHarmonic: Boolean,
)

sealed class CalibrationState {
    data class Running(val progress: Float, val message: String) : CalibrationState()
    data class Done(val profile: DroneProfile) : CalibrationState()
    data class Failed(val reason: String) : CalibrationState()
}

data class AnalysisResult(
    val levelDb: Double,
    val drone: DroneReading?,
    val chanter: ChanterReading?,
    val calibration: CalibrationState?,
)

/**
 * Обработва кадри от микрофона. Не зависи от Android — тества се на обикновена JVM.
 *
 * Идеята: при каба гайда тониката, квинтата и октавата на гайдуницата са точно хармоници
 * на ручилото (4f, 6f, 8f). Наивно „изрязване“ на хармониците на ручилото изтрива и тези ноти.
 * Затова при калибриране записваме тембъра на ручилото (амплитудата на всеки хармоник),
 * а после изваждаме само очакваната му част и напасваме двата тона заедно.
 */
class Analyzer(
    val sampleRate: Double,
    val hopSize: Int,
    val fftSize: Int = 8192,
) {
    @Volatile var gaidaType: GaidaType = GaidaType.KABA
    @Volatile var includeNeutral: Boolean = true
    @Volatile var profile: DroneProfile? = null
        set(value) { field = value; tracked = 0.0 }

    @Volatile private var calibrationRequested = false
    @Volatile private var cancelRequested = false

    private val spec = Spectrum(fftSize, sampleRate)
    private val fitter = SineFitter(spec)
    private val yin = Yin(sampleRate, 40.0, 260.0)
    private val residual = DoubleArray(fftSize / 2 + 1)
    private val kTmp = DoubleArray(2)
    private var tracked = 0.0
    private var calibration: Calibration? = null
    private val hopSeconds = hopSize / sampleRate

    val isCalibrating: Boolean get() = calibration != null || calibrationRequested

    fun startCalibration() { cancelRequested = false; calibrationRequested = true }
    fun cancelCalibration() { calibrationRequested = false; cancelRequested = true }

    private fun lowCount(): Int = ceil(0.8 * gaidaType.tonicMultiple).toInt() - 1

    fun process(frame: FloatArray): AnalysisResult {
        require(frame.size >= fftSize)
        var e = 0.0
        val tail = min(2048, frame.size)
        for (i in frame.size - tail until frame.size) e += frame[i].toDouble() * frame[i]
        val levelDb = 10.0 * log10(e / tail + 1e-12)

        if (cancelRequested) { cancelRequested = false; calibration = null }
        if (calibrationRequested) { calibrationRequested = false; calibration = Calibration() }

        spec.load(frame)

        calibration?.let { c ->
            val st = c.step(frame, levelDb)
            if (st !is CalibrationState.Running) calibration = null
            if (st is CalibrationState.Done) profile = st.profile
            return AnalysisResult(levelDb, null, null, st)
        }

        val p = profile ?: return AnalysisResult(levelDb, null, null, null)
        val noise = spec.medianMagnitude(40.0, 5000.0)
        val drone = trackDrone(p, noise)
        val chanter = if (levelDb > -70) detectChanter(p, drone, noise) else null
        return AnalysisResult(levelDb, drone, chanter, null)
    }

    // ---------------------------------------------------------------- ручило

    private fun centsToBins(nu: Double, cents: Double) = nu * (2.0.pow(cents / 1200.0) - 1.0)

    /** Честотата на ручилото от хармониците под обхвата на гайдуницата (претеглено по h·амплитуда). */
    private fun estimateDroneFrequency(seed: Double, harmonics: Int, rangeCents: Double): Pair<Double, DoubleArray>? {
        val amps = DoubleArray(harmonics)
        val ests = DoubleArray(harmonics)
        var wsum = 0.0; var fsum = 0.0
        for (h in 1..harmonics) {
            val nu = spec.hzToBin(h * seed)
            if (nu > fftSize / 2 - 6) break
            val fit = fitter.fitSingle(nu, max(centsToBins(nu, rangeCents), 0.5))
            val f = spec.binToHz(fit.bin) / h
            ests[h - 1] = f; amps[h - 1] = fit.amplitude
            val w = h * fit.amplitude
            wsum += w; fsum += w * f
        }
        if (wsum <= 0.0) return null
        val f = fsum / wsum
        val maxA = amps.max()
        for (h in 0 until harmonics) {
            if (amps[h] > 0.25 * maxA && abs(Music.cents(ests[h], f)) > 20) return null
        }
        return f to amps
    }

    private fun trackDrone(p: DroneProfile, noise: Double): DroneReading? {
        val low = lowCount()
        val seed = if (tracked > 0) tracked else p.frequency
        val est = estimateDroneFrequency(seed, low, 60.0)
        if (est == null) { tracked = 0.0; return null }
        val (f, amps) = est
        val level = amps.sum()
        val rel = level / p.lowRef(low)
        val peak = amps.max() * fftSize / 2.0
        val drift = Music.cents(f, p.frequency)
        if (peak < 10 * noise || rel < 0.08 || abs(drift) > 150) { tracked = 0.0; return null }
        tracked = f
        return DroneReading(f, drift, rel)
    }

    // ---------------------------------------------------------------- гайдуница

    private fun detectChanter(p: DroneProfile, drone: DroneReading?, noise: Double): ChanterReading? {
        val m = gaidaType.tonicMultiple
        val fd = drone?.frequency ?: p.frequency
        val gain = if (drone != null) drone.relativeLevel else 0.0
        val tonic = m * fd
        val half = fftSize / 2
        val binHz = spec.binHz
        val fLo = 0.8 * tonic
        val fHi = min(2.9 * tonic, 0.2 * sampleRate)
        if (fHi <= fLo) return null

        // 1) Остатъчен спектър: изваждаме очакваната (от профила) амплитуда на ручилото.
        System.arraycopy(spec.mag, 0, residual, 0, half + 1)
        val nud = fd / binHz
        val expectedDrone = DoubleArray(p.amps.size)
        if (gain > 0) {
            for (h in 1..p.amps.size) {
                val c = h * nud
                if (c > half - 5) break
                val d = gain * p.amps[h - 1]
                expectedDrone[h - 1] = d
                for (k in floor(c).toInt() - 3..ceil(c).toInt() + 3) {
                    residual[k] = max(0.0, residual[k] - d * HannKernel.magnitude(c - k, fftSize, kTmp))
                }
            }
        }

        fun peakR(hz: Double, width: Int = 0): Double {
            val nu = hz / binHz
            val k = floor(nu).toInt()
            var best = 0.0
            for (j in k - width..k + 1 + width) if (j in 1..half) best = max(best, residual[j])
            return best
        }

        fun score(f: Double): Double {
            var s = 0.0
            for (q in 1..6) {
                val hz = q * f
                if (hz > 0.45 * sampleRate) break
                s += peakR(hz) / q
            }
            return s
        }

        // 2) Груба оценка: сума по хармониците върху остатъка.
        val stepUp = 2.0.pow(10.0 / 1200.0)
        var f = fLo; var best = fLo; var bestS = -1.0
        while (f <= fHi) {
            val s = score(f)
            if (s > bestS) { bestS = s; best = f }
            f *= stepUp
        }
        var refined = best; var refinedS = bestS
        for (c in -6..6) {
            val ff = Music.shift(best, c * 2.0)
            val s = score(ff)
            if (s > refinedS) { refinedS = s; refined = ff }
        }
        var fStar = refined

        // 2б) Проверка за грешен избор 3/2 или 2× нагоре. Когато нотата е точно върху хармоник на
        // ручилото, основният ѝ тон може да се „скрие“ (противоположна фаза), и тогава сумата
        // предпочита 1.5× или 2× честотата. Ако по-ниската хипотеза има собствена силна опора
        // (хармоници, които горната не обяснява), значи тя е вярната.
        val own = max(peakR(fStar, 1), 8 * noise)
        var bestAlt = fStar; var bestSupport = own
        for (ratio in doubleArrayOf(2.0 / 3.0, 0.5, 0.6)) {
            val alt = fStar * ratio
            if (alt < fLo) continue
            var unique = 0.0
            for (q in 1..7) {
                val x = q * ratio
                if (abs(x - Math.rint(x)) < 1e-6) continue
                if (q * alt > 0.45 * sampleRate) break
                unique += peakR(q * alt, 1)
            }
            if (unique > bestSupport) { bestSupport = unique; bestAlt = alt }
        }
        fStar = bestAlt

        // 3) Има ли изобщо гайдуница? Силен остатък над шума и над остатъка от ручилото.
        val r = DoubleArray(5)
        var rmax = 0.0; var qmax = 1
        for (q in 1..4) {
            r[q] = peakR(q * fStar, 1)
            if (r[q] > rmax) { rmax = r[q]; qmax = q }
        }
        fun expectedPeakNear(hz: Double): Double {
            if (gain <= 0) return 0.0
            val h = (hz / fd).roundToInt()
            if (h < 1 || h > expectedDrone.size) return 0.0
            val sep = abs(hz / binHz - h * nud)
            return if (sep < 3.0) expectedDrone[h - 1] * HannKernel.magnitude(sep, fftSize, kTmp) else 0.0
        }
        if (rmax < 12 * noise) return null
        if (rmax < 0.5 * expectedPeakNear(qmax * fStar)) return null

        // 4) Точна оценка: напасване на синусоиди за всеки силен хармоник на гайдуницата.
        val estF = ArrayList<Double>(); val estW = ArrayList<Double>()
        var fundamentalOnDrone = false
        for (q in 1..4) {
            if (r[q] < 0.2 * rmax) continue
            val hz = q * fStar
            if (hz > 0.45 * sampleRate) break
            val nuq = hz / binHz
            val h = (hz / fd).roundToInt()
            val nuh = h * nud
            val sep = abs(nuq - nuh)
            val dAmp = if (gain > 0 && h >= 1 && h <= expectedDrone.size) expectedDrone[h - 1] else 0.0
            val range = max(centsToBins(nuq, 30.0), 0.5)
            var onDrone = false
            val fit = when {
                dAmp * fftSize / 2.0 < 3 * noise || sep > 4.0 -> fitter.fitSingle(nuq, range)
                sep > 1.2 -> fitter.fitBesideDrone(nuq, min(range, sep - 0.8), nuh)
                else -> { onDrone = true; fitter.fitOnDrone(nuq, range, nuh, dAmp) }
            }
            if (q == 1) fundamentalOnDrone = onDrone
            val fq = spec.binToHz(fit.bin) / q
            if (abs(Music.cents(fq, fStar)) > 40) continue
            estF.add(fq)
            estW.add(fit.amplitude * q * (if (onDrone) 0.5 else 1.0))
        }
        if (estF.isEmpty()) return null
        val freq = robustMean(estF, estW)
        val cents = Music.cents(freq, tonic)
        return ChanterReading(
            frequency = freq,
            centsFromTonic = cents,
            step = Music.nearestStep(cents, includeNeutral),
            note = Music.noteName(freq),
            onDroneHarmonic = fundamentalOnDrone,
        )
    }

    private fun robustMean(f: List<Double>, w: List<Double>): Double {
        val idx = f.indices.sortedBy { f[it] }
        val total = w.sum()
        var acc = 0.0; var med = f[idx[0]]
        for (i in idx) { acc += w[i]; if (acc >= total / 2) { med = f[i]; break } }
        var s = 0.0; var ws = 0.0
        for (i in f.indices) if (abs(Music.cents(f[i], med)) <= 12) { s += f[i] * w[i]; ws += w[i] }
        return if (ws > 0) s / ws else med
    }

    // ---------------------------------------------------------------- калибриране

    private inner class Calibration {
        val phase1 = ceil(1.5 / hopSeconds).toInt()
        val phase2 = ceil(2.5 / hopSeconds).toInt()
        val yinEst = ArrayList<Double>()
        var loud = 0
        var frames = 0
        var f0 = 0.0
        var sums: DoubleArray? = null
        var count = 0
        val tracks = ArrayList<Double>()

        fun progress() = frames.toFloat() / (phase1 + phase2)

        fun step(frame: FloatArray, levelDb: Double): CalibrationState {
            frames++
            if (frames <= phase1) {
                if (levelDb > -55) {
                    loud++
                    yin.detect(frame)?.let { yinEst.add(it.frequency) }
                }
                if (frames == phase1) {
                    if (loud < phase1 / 2) return CalibrationState.Failed("Не чувам звук. Приближи телефона до ручилото и опитай пак.")
                    if (yinEst.size < phase1 / 2) return CalibrationState.Failed("Тонът не е ясен. Свири само ручилото — гайдуницата да не звучи.")
                    val med = median(yinEst)
                    val mad = median(yinEst.map { abs(Music.cents(it, med)) })
                    if (mad > 12) return CalibrationState.Failed("Тонът плава твърде много. Дръж меха равно и опитай пак.")
                    f0 = med
                    // защита от октавна грешка: ако има силен тон на f0/2, ручилото е там
                    val aHalf = fitter.amplitudeAt(spec.hzToBin(f0 / 2)).amplitude
                    val aF = fitter.amplitudeAt(spec.hzToBin(f0)).amplitude
                    if (f0 / 2 >= 40 && aHalf > 0.3 * aF) f0 /= 2
                }
                return CalibrationState.Running(progress(), "Слушам ручилото…")
            }
            val est = estimateDroneFrequency(if (tracks.isEmpty()) f0 else tracks.last(), 3, 40.0)
            if (est != null && levelDb > -55) {
                val f = est.first
                tracks.add(f)
                val hMax = min(80, floor(min(6000.0, 0.45 * sampleRate) / f).toInt())
                val s = sums ?: DoubleArray(hMax).also { sums = it }
                for (h in 1..min(hMax, s.size)) s[h - 1] += fitter.amplitudeAt(spec.hzToBin(h * f)).amplitude
                count++
            }
            if (frames >= phase1 + phase2) {
                val s = sums
                if (s == null || count < phase2 / 2) return CalibrationState.Failed("Ручилото прекъсваше. Опитай пак с равно надуване.")
                for (i in s.indices) s[i] /= count
                return CalibrationState.Done(DroneProfile(median(tracks), s))
            }
            return CalibrationState.Running(progress(), "Записвам тембъра на ручилото…")
        }
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    companion object {
        /** RMS в dBFS → 0..1 за индикатора на нивото. */
        fun levelToUnit(db: Double): Float = ((db + 70.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    }
}
