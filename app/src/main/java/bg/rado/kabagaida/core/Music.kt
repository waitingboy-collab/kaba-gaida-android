package bg.rado.kabagaida.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

enum class GaidaType(val tonicMultiple: Int, val label: String, val droneNote: String) {
    /** Ручилото звучи две октави под тониката. */
    KABA(4, "Каба гайда", "2 октави под тониката"),
    /** Ручилото звучи една октава под тониката. */
    DZHURA(2, "Джура гайда", "1 октава под тониката");
}

data class NoteName(val name: String, val octave: Int, val cents: Double) {
    override fun toString() = "$name$octave"
}

/** Стъпка от звукоредa, броена в центове от тониката. */
data class Step(val cents: Int, val name: String, val short: String, val neutral: Boolean)

data class StepReading(val step: Step, val octave: Int, val deviation: Double) {
    /** Име с отбелязана октава: ↓ под тониката, ↑ над горната тоника. */
    val label: String
        get() = when {
            octave < 0 -> "${step.name} ↓"
            octave > 0 -> "${step.name} ↑"
            else -> step.name
        }
    val shortLabel: String
        get() = when {
            octave < 0 -> "${step.short}↓"
            octave > 0 -> "${step.short}↑"
            else -> step.short
        }
}

object Music {
    private val LN2 = ln(2.0)
    private val NAMES = arrayOf("До", "До♯", "Ре", "Ми♭", "Ми", "Фа", "Фа♯", "Сол", "Сол♯", "Ла", "Си♭", "Си")

    fun cents(f: Double, ref: Double): Double = 1200.0 * ln(f / ref) / LN2
    fun shift(f: Double, cents: Double): Double = f * Math.pow(2.0, cents / 1200.0)

    fun noteName(f: Double, a4: Double = 440.0): NoteName {
        val midi = 69.0 + 12.0 * ln(f / a4) / LN2
        val m = midi.roundToInt()
        val idx = ((m % 12) + 12) % 12
        return NoteName(NAMES[idx], m / 12 - 1, (midi - m) * 100.0)
    }

    val STEPS: List<Step> = listOf(
        Step(0, "Тоника", "I", false),
        Step(100, "Малка секунда", "м2", false),
        Step(150, "Неутрална секунда", "н2", true),
        Step(200, "Голяма секунда", "Г2", false),
        Step(300, "Малка терца", "м3", false),
        Step(350, "Неутрална терца", "н3", true),
        Step(400, "Голяма терца", "Г3", false),
        Step(500, "Кварта", "4", false),
        Step(600, "Тритон", "ув4", false),
        Step(700, "Квинта", "5", false),
        Step(800, "Малка секста", "м6", false),
        Step(850, "Неутрална секста", "н6", true),
        Step(900, "Голяма секста", "Г6", false),
        Step(1000, "Малка септима", "м7", false),
        Step(1050, "Неутрална септима", "н7", true),
        Step(1100, "Голяма септима", "Г7", false),
    )

    /** Най-близката стъпка до [centsFromTonic]; неутралните (¾ тон) по избор. */
    fun nearestStep(centsFromTonic: Double, includeNeutral: Boolean): StepReading {
        val baseOct = floor(centsFromTonic / 1200.0).toInt()
        var best: StepReading? = null
        for (o in baseOct - 1..baseOct + 1) {
            for (s in STEPS) {
                if (s.neutral && !includeNeutral) continue
                val dev = centsFromTonic - (s.cents + 1200 * o)
                if (best == null || abs(dev) < abs(best.deviation)) best = StepReading(s, o, dev)
            }
        }
        return best!!
    }
}
