package bg.rado.kabagaida.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

/**
 * Синтетични тестове на ядрото (без Android). Пускат се от JUnit (CoreTest) или директно с main().
 * Симулират каба гайда: ручило с богат тембър + гайдуница с преобладаващи нечетни хармоници + шум.
 */
object SelfTests {
    const val FS = 48000.0
    const val HOP = 2048
    const val N = 8192
    const val DRONE = 73.42 // Ре2

    private val failures = ArrayList<String>()
    private var passed = 0
    val log = StringBuilder()

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) passed++ else failures.add("$name $detail")
        log.appendLine((if (ok) "  ✓ " else "  ✗ ") + name + (if (detail.isNotEmpty()) " — $detail" else ""))
    }

    private fun droneAmps(f: Double, gain: Double = 1.0, brightness: Double = 0.0): DoubleArray {
        val n = (6000 / f).toInt()
        return DoubleArray(n) { i -> val h = i + 1; gain * 0.3 / h.toDouble().pow(0.7) * (1 + brightness * h / 20.0) }
    }

    private val chanterPattern = doubleArrayOf(1.0, 0.25, 0.6, 0.15, 0.4, 0.1, 0.25, 0.08, 0.15, 0.05)

    private class Partial(val f: Double, val a: Double, val ph: Double)

    private fun render(partials: List<Partial>, t0: Double, len: Int, rnd: Random, noise: Double): FloatArray {
        val out = FloatArray(len)
        for (p in partials) {
            if (p.f > 0.48 * FS) continue
            val w = 2 * PI * p.f / FS
            val base = 2 * PI * p.f * t0 + p.ph
            for (n in 0 until len) out[n] += (p.a * cos(w * n + base)).toFloat()
        }
        if (noise > 0) for (n in 0 until len) out[n] += (noise * rnd.nextGaussian()).toFloat()
        return out
    }

    private fun Random.nextGaussian(): Double {
        var u: Double; var v: Double; var s: Double
        do { u = nextDouble() * 2 - 1; v = nextDouble() * 2 - 1; s = u * u + v * v } while (s >= 1 || s == 0.0)
        return u * kotlin.math.sqrt(-2 * kotlin.math.ln(s) / s)
    }

    private fun droneParts(f: Double, amps: DoubleArray, rnd: Random) =
        amps.indices.map { Partial((it + 1) * f, amps[it], rnd.nextDouble() * 2 * PI) }

    private fun chanterParts(f: Double, level: Double, rnd: Random) =
        chanterPattern.indices.map { Partial((it + 1) * f, level * chanterPattern[it], rnd.nextDouble() * 2 * PI) }

    fun runAll(): List<String> {
        failures.clear(); passed = 0; log.clear()
        testMusic()
        testKernel()
        testYin()
        val profile = testCalibration() ?: return failures
        testDroneTracking(profile)
        testDroneOnlyNoFalseNotes(profile)
        testChanter(profile)
        testDzhura()
        testStability()
        log.appendLine("\nУспешни: $passed, неуспешни: ${failures.size}")
        return failures
    }

    private fun testMusic() {
        log.appendLine("Музика")
        val n = Music.noteName(440.0)
        check("Ла4 = 440 Hz", n.name == "Ла" && n.octave == 4 && abs(n.cents) < 0.01)
        check("Ре2 ≈ 73.42 Hz", Music.noteName(DRONE).toString() == "Ре2")
        val q = Music.nearestStep(702.0, true)
        check("квинта +2¢", q.step.cents == 700 && q.octave == 0 && abs(q.deviation - 2.0) < 1e-9)
        val sub = Music.nearestStep(-200.0, true)
        check("подтоника = малка септима ↓", sub.step.cents == 1000 && sub.octave == -1)
        check("неутрална терца (вкл.)", Music.nearestStep(345.0, true).step.cents == 350)
        check("неутрална терца (изкл.) → м3/Г3", Music.nearestStep(345.0, false).step.cents == 300)
        check("горна тоника", Music.nearestStep(1195.0, true).let { it.step.cents == 0 && it.octave == 1 })
    }

    private fun testKernel() {
        log.appendLine("Ядро на Хан")
        // FFT на синусоида трябва да съвпадне с аналитичното ядро
        val spec = Spectrum(N, FS)
        val nu = 55.37
        val x = DoubleArray(N) { 0.5 * cos(2 * PI * nu * it / N + 0.7) }
        spec.load(x)
        val fit = SineFitter(spec).fitSingle(55.2, 0.6)
        check("напасване на честота", abs(fit.bin - nu) < 1e-4, "грешка ${"%.2e".format(fit.bin - nu)} бина")
        check("напасване на амплитуда", abs(fit.amplitude - 0.25) < 1e-3, "%.4f".format(fit.amplitude))
    }

    private fun testYin() {
        log.appendLine("YIN")
        val rnd = Random(1)
        for (f in listOf(55.0, 73.42, 98.0, 146.83)) {
            val sig = render(droneParts(f, droneAmps(f), rnd), 0.1, N, rnd, 0.0005)
            val r = Yin(FS, 40.0, 260.0).detect(sig)
            val err = if (r == null) 999.0 else Music.cents(r.frequency, f)
            check("ручило %.2f Hz".format(f), abs(err) < 1.0, "грешка %.2f¢".format(err))
        }
    }

    private fun stream(partials: List<Partial>, seconds: Double, rnd: Random, noise: Double): FloatArray =
        render(partials, 0.0, (seconds * FS).toInt(), rnd, noise)

    private fun testCalibration(): DroneProfile? {
        log.appendLine("Калибриране")
        val rnd = Random(2)
        val sig = stream(droneParts(DRONE, droneAmps(DRONE), rnd), 6.0, rnd, 0.0005)
        val an = Analyzer(FS, HOP, N)
        an.startCalibration()
        var state: CalibrationState? = null
        var i = 0
        while (i * HOP + N <= sig.size) {
            val res = an.process(sig.copyOfRange(i * HOP, i * HOP + N))
            state = res.calibration
            if (state !is CalibrationState.Running) break
            i++
        }
        val done = state as? CalibrationState.Done
        check("калибрирането завършва", done != null, state.toString())
        if (done == null) return null
        val p = done.profile
        val err = Music.cents(p.frequency, DRONE)
        check("честота на ручилото", abs(err) < 0.3, "грешка %.3f¢".format(err))
        val ref = droneAmps(DRONE)
        var worst = 0.0
        for (h in 1..20) worst = max(worst, abs(p.amps[h - 1] / (ref[h - 1] / 2) - 1))
        check("тембър (хармоници 1–20)", worst < 0.05, "макс. отклонение %.1f%%".format(worst * 100))
        val again = DroneProfile.parse(p.serialize())
        check("запис/четене на профила", again != null && again.amps.size == p.amps.size && again.frequency == p.frequency)

        // неуспех: тишина
        val an2 = Analyzer(FS, HOP, N)
        an2.startCalibration()
        var st2: CalibrationState? = null
        val silence = FloatArray(N)
        for (k in 0 until 200) { st2 = an2.process(silence).calibration; if (st2 !is CalibrationState.Running) break }
        check("тишина → ясна грешка", st2 is CalibrationState.Failed)
        return p
    }

    private fun testDroneTracking(p: DroneProfile) {
        log.appendLine("Следене на ручилото (налягане)")
        val an = Analyzer(FS, HOP, N).also { it.profile = p }
        val rnd = Random(3)
        for (shift in listOf(-25.0, -7.0, 0.0, 6.0, 30.0)) {
            val f = Music.shift(DRONE, shift)
            var worst = 0.0; var miss = 0
            repeat(10) {
                val sig = render(droneParts(f, droneAmps(f, 0.8), rnd), rnd.nextDouble() * 5, N, rnd, 0.0005)
                val d = an.process(sig).drone
                if (d == null) miss++ else worst = max(worst, abs(d.centsFromCalibrated - Music.cents(f, p.frequency)))
            }
            check("ручило %+.0f¢".format(shift), miss == 0 && worst < 0.5, "макс. грешка %.2f¢, пропуснати $miss".format(worst))
        }
    }

    private fun testDroneOnlyNoFalseNotes(p: DroneProfile) {
        log.appendLine("Само ручило → без фалшиви ноти")
        val an = Analyzer(FS, HOP, N).also { it.profile = p }
        val rnd = Random(4)
        var falses = 0
        val total = 120
        repeat(total) { i ->
            val gain = 0.7 + 0.6 * rnd.nextDouble()
            val bright = (rnd.nextDouble() - 0.5) * 0.2 // ±5% промяна в тембъра на 10-тия хармоник
            val f = Music.shift(DRONE, (rnd.nextDouble() - 0.5) * 20)
            val sig = render(droneParts(f, droneAmps(f, gain, bright), rnd), rnd.nextDouble() * 5, N, rnd, 0.0005)
            if (an.process(sig).chanter != null) falses++
        }
        check("$total кадъра само ручило", falses == 0, "фалшиви: $falses")
    }

    private fun testChanter(p: DroneProfile) {
        log.appendLine("Гайдуница при звучащо ручило (тоника = 4×ручило = Ре4)")
        val an = Analyzer(FS, HOP, N).also { it.profile = p }
        val rnd = Random(5)
        val steps = listOf(-200, -150, 0, 100, 150, 200, 300, 350, 400, 500, 700, 800, 850, 900, 1000, 1050, 1200, 1400)
        val detunes = listOf(0.0, 4.0, -4.0, 10.0, -10.0, 25.0, -25.0)
        val levels = listOf(0.3 to "силна", 0.12 to "тиха")
        val table = StringBuilder()
        for ((level, levelName) in levels) {
            var worstClean = 0.0; var worstOn = 0.0; var missed = 0; var total = 0
            var sumOn = 0.0; var nOn = 0
            for (st in steps) {
                var rowWorst = 0.0
                for (dt in detunes) {
                    repeat(4) {
                        total++
                        val droneShift = (rnd.nextDouble() - 0.5) * 6
                        val fd = Music.shift(DRONE, droneShift)
                        val fc = Music.shift(4 * fd, st + dt)
                        val parts = droneParts(fd, droneAmps(fd, 0.85 + 0.3 * rnd.nextDouble(), (rnd.nextDouble() - 0.5) * 0.2), rnd) +
                            chanterParts(fc, level, rnd)
                        val res = an.process(render(parts, rnd.nextDouble() * 5, N, rnd, 0.0005))
                        val c = res.chanter
                        if (c == null) { missed++; return@repeat }
                        val err = abs(Music.cents(c.frequency, fc))
                        rowWorst = max(rowWorst, err)
                        if (c.onDroneHarmonic) { worstOn = max(worstOn, err); sumOn += err; nOn++ } else worstClean = max(worstClean, err)
                    }
                }
                table.append("%6d¢:%5.1f ".format(st, rowWorst))
                if (st == 400 || st == 1050) table.appendLine()
            }
            log.appendLine("  [$levelName гайдуница] най-голяма грешка по стъпки:\n  $table")
            table.clear()
            check("$levelName: открива нотата", missed <= total / 100, "пропуснати $missed от $total")
            check("$levelName: ноти извън хармониците на ручилото ≤ 1.5¢", worstClean <= 1.5, "%.2f¢".format(worstClean))
            val meanOn = if (nOn > 0) sumOn / nOn else 0.0
            check("$levelName: ноти върху хармоник (I, 5, 8) ≤ 6¢ (средно %.2f¢)".format(meanOn), worstOn <= 6.0, "макс. %.2f¢".format(worstOn))
        }

        // Най-трудният случай: тиха гайдуница точно в строй с хармоник на ручилото.
        // Основният тон може да се изгаси от противоположна фаза; допускаме пропуснати кадри,
        // но не и грешни ноти (интерфейсът задържа последното показание).
        var gross = 0; var missed = 0; var total = 0
        for (st in listOf(0, 700, 1200)) for (dt in listOf(0.0, 3.0)) repeat(40) {
            total++
            val fc = Music.shift(4 * DRONE, st + dt)
            val parts = droneParts(DRONE, droneAmps(DRONE), rnd) + chanterParts(fc, 0.12, rnd)
            val c = an.process(render(parts, rnd.nextDouble() * 5, N, rnd, 0.0005)).chanter
            if (c == null) missed++ else if (abs(Music.cents(c.frequency, fc)) > 50) gross++
        }
        check("тиха, в строй с ручилото: грешни ноти ≤ 2%", gross * 50 <= total, "грешни $gross, пропуснати $missed от $total")
        check("тиха, в строй с ручилото: открива ≥ 75%", missed * 4 <= total, "пропуснати $missed от $total")
    }

    private fun testDzhura() {
        log.appendLine("Джура гайда (ручило 1 октава под тониката)")
        val fd = 146.83
        val rnd = Random(6)
        val sig = stream(droneParts(fd, droneAmps(fd), rnd), 6.0, rnd, 0.0005)
        val an = Analyzer(FS, HOP, N).also { it.gaidaType = GaidaType.DZHURA }
        an.startCalibration()
        var i = 0
        while (i * HOP + N <= sig.size) {
            val st = an.process(sig.copyOfRange(i * HOP, i * HOP + N)).calibration
            if (st !is CalibrationState.Running) break
            i++
        }
        check("калибриране", an.profile != null)
        var worst = 0.0; var missed = 0
        for (st in listOf(0, 200, 350, 500, 700, 1000)) {
            val fc = Music.shift(2 * fd, st + 7.0)
            val parts = droneParts(fd, droneAmps(fd), rnd) + chanterParts(fc, 0.3, rnd)
            val c = an.process(render(parts, 1.3, N, rnd, 0.0005)).chanter
            if (c == null) missed++ else worst = max(worst, abs(Music.cents(c.frequency, fc)))
        }
        check("ноти +7¢", missed == 0 && worst < 6, "макс. %.2f¢, пропуснати $missed".format(worst))
    }

    private fun testStability() {
        log.appendLine("Равномерност")
        val steady = StabilityTracker()
        val wobbly = StabilityTracker()
        val dt = HOP / FS
        for (i in 0 until 200) {
            val t = i * dt
            steady.add(t, 1.0 * kotlin.math.sin(2 * PI * 0.3 * t))
            wobbly.add(t, 15.0 * kotlin.math.sin(2 * PI * 0.8 * t))
        }
        check("равно → висока оценка", (steady.score() ?: 0) >= 90, "${steady.score()}")
        check("вълни ±15¢ → ниска оценка", (wobbly.score() ?: 100) <= 55, "${wobbly.score()}")
        check("в зоната ±5¢", (steady.inZone() ?: 0.0) > 0.99 && (wobbly.inZone() ?: 1.0) < 0.5)
    }
}

fun main() {
    val t0 = System.currentTimeMillis()
    val f = SelfTests.runAll()
    println(SelfTests.log)
    println("Време: ${System.currentTimeMillis() - t0} ms")
    if (f.isNotEmpty()) { println("НЕУСПЕШНИ:"); f.forEach { println(" - $it") }; kotlin.system.exitProcess(1) }
}
