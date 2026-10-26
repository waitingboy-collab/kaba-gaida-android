package bg.rado.kabagaida.core

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Следи колко равно е надуването: височината на ручилото (в центове спрямо калибрирането)
 * трепти, когато налягането в меха „диша“.
 */
class StabilityTracker(
    private val windowSeconds: Double = 3.0,
    private val historySeconds: Double = 15.0,
    val zone: Double = 5.0,
) {
    private val times = ArrayDeque<Double>()
    private val values = ArrayDeque<Double>()

    fun reset() { times.clear(); values.clear() }

    fun add(t: Double, cents: Double) {
        times.addLast(t); values.addLast(cents)
        while (times.isNotEmpty() && t - times.first() > historySeconds) { times.removeFirst(); values.removeFirst() }
    }

    private fun recent(): List<Double> {
        if (times.isEmpty()) return emptyList()
        val now = times.last()
        val out = ArrayList<Double>()
        for (i in times.indices) if (now - times[i] <= windowSeconds) out.add(values[i])
        return out
    }

    /** Стандартно отклонение (центове) за последните [windowSeconds] секунди. */
    fun sigma(): Double? {
        val v = recent()
        if (v.size < 10) return null
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    /** Средно отклонение от калибрирания тон. */
    fun mean(): Double? = recent().takeIf { it.size >= 10 }?.average()

    /** Дял от времето в зоната ±[zone] около калибрирания тон. */
    fun inZone(): Double? {
        val v = recent()
        if (v.size < 10) return null
        return v.count { abs(it) <= zone }.toDouble() / v.size
    }

    /** 0–100: 100 = идеално равно. σ 2¢ ≈ 90, 5¢ ≈ 75, 10¢ ≈ 50. */
    fun score(): Int? = sigma()?.let { (100.0 * (1.0 - it / 20.0)).coerceIn(0.0, 100.0).roundToInt() }

    /** История за графиката: (секунди назад от сега, центове). */
    fun history(): List<Pair<Float, Float>> {
        if (times.isEmpty()) return emptyList()
        val now = times.last()
        return times.indices.map { ((times[it] - now).toFloat()) to values[it].toFloat() }
    }
}
