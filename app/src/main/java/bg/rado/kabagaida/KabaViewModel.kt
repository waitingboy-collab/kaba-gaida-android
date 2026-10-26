package bg.rado.kabagaida

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import bg.rado.kabagaida.audio.MicCapture
import bg.rado.kabagaida.core.Analyzer
import bg.rado.kabagaida.core.CalibrationState
import bg.rado.kabagaida.core.ChanterReading
import bg.rado.kabagaida.core.DroneProfile
import bg.rado.kabagaida.core.DroneReading
import bg.rado.kabagaida.core.GaidaType
import bg.rado.kabagaida.core.Music
import bg.rado.kabagaida.core.StabilityTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.pow

data class CalibrationUi(
    val running: Boolean,
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null,
)

data class UiState(
    val hasPermission: Boolean = false,
    val listening: Boolean = false,
    val micError: String? = null,
    val level: Float = 0f,
    val gaidaType: GaidaType = GaidaType.KABA,
    val showNeutral: Boolean = true,
    /** Калибрираната честота на ручилото, или null ако още не е калибрирано. */
    val droneCalibrated: Double? = null,
    val calibration: CalibrationUi? = null,
    val drone: DroneReading? = null,
    val chanter: ChanterReading? = null,
    val history: List<Pair<Float, Float>> = emptyList(),
    val sigma: Double? = null,
    val mean: Double? = null,
    val score: Int? = null,
    val inZone: Double? = null,
)

class KabaViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("kabagaida", Context.MODE_PRIVATE)

    private val _ui = MutableStateFlow(
        UiState(
            gaidaType = runCatching { GaidaType.valueOf(prefs.getString(KEY_TYPE, null) ?: "") }.getOrDefault(GaidaType.KABA),
            showNeutral = prefs.getBoolean(KEY_NEUTRAL, true),
            droneCalibrated = DroneProfile.parse(prefs.getString(KEY_PROFILE, null))?.frequency,
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val capture = MicCapture(FRAME, HOP) { frame, rate -> onFrame(frame, rate) }
    @Volatile private var analyzer: Analyzer? = null
    @Volatile private var calibrationPending = false
    @Volatile private var resetStabilityPending = false

    // достъпват се само от нишката на микрофона
    private val stability = StabilityTracker()
    private var frameIndex = 0L
    private val recent = ArrayDeque<ChanterReading?>()
    private var shown: ChanterReading? = null
    private var shownAt = 0L

    fun setPermission(granted: Boolean) = _ui.update { it.copy(hasPermission = granted) }

    fun startListening() {
        if (_ui.value.listening) return
        val err = capture.start(getApplication())
        if (err != null) { _ui.update { it.copy(micError = err, listening = false) }; return }
        val a = Analyzer(capture.sampleRate.toDouble(), HOP, FRAME).apply {
            gaidaType = _ui.value.gaidaType
            includeNeutral = _ui.value.showNeutral
            profile = DroneProfile.parse(prefs.getString(KEY_PROFILE, null))
        }
        if (calibrationPending) { calibrationPending = false; a.startCalibration() }
        analyzer = a
        _ui.update { it.copy(listening = true, micError = null) }
    }

    fun stopListening() {
        capture.stop()
        analyzer?.let { if (it.isCalibrating) _ui.update { s -> s.copy(calibration = null) } }
        analyzer = null
        _ui.update { it.copy(listening = false, level = 0f, drone = null, chanter = null) }
    }

    fun startCalibration() {
        _ui.update { it.copy(calibration = CalibrationUi(running = true, message = "Подготовка…")) }
        val a = analyzer
        if (a != null) a.startCalibration() else { calibrationPending = true; startListening() }
    }

    fun cancelCalibration() {
        calibrationPending = false
        analyzer?.cancelCalibration()
        _ui.update { it.copy(calibration = null) }
    }

    fun setGaidaType(t: GaidaType) {
        prefs.edit().putString(KEY_TYPE, t.name).apply()
        analyzer?.gaidaType = t
        _ui.update { it.copy(gaidaType = t) }
    }

    fun setShowNeutral(on: Boolean) {
        prefs.edit().putBoolean(KEY_NEUTRAL, on).apply()
        analyzer?.includeNeutral = on
        _ui.update { it.copy(showNeutral = on) }
    }

    fun resetStability() { resetStabilityPending = true }

    override fun onCleared() { capture.stop() }

    // ------------------------------------------------------------ нишка на микрофона

    private fun onFrame(frame: FloatArray, rate: Int) {
        val a = analyzer ?: return
        val res = a.process(frame)
        frameIndex++
        val t = frameIndex * HOP / rate.toDouble()

        var calibUi: CalibrationUi? = _ui.value.calibration
        var calibrated = _ui.value.droneCalibrated
        when (val c = res.calibration) {
            is CalibrationState.Running -> calibUi = CalibrationUi(true, c.progress, c.message)
            is CalibrationState.Done -> {
                prefs.edit().putString(KEY_PROFILE, c.profile.serialize()).apply()
                calibrated = c.profile.frequency
                calibUi = CalibrationUi(false, 1f, "Готово! Ручилото е запомнено.")
                resetStabilityPending = true
            }
            is CalibrationState.Failed -> calibUi = CalibrationUi(false, 0f, "", c.reason)
            null -> {}
        }

        if (resetStabilityPending) { resetStabilityPending = false; stability.reset() }
        res.drone?.let { stability.add(t, it.centsFromCalibrated) }

        val chanter = smoothChanter(res.chanter)

        _ui.update {
            it.copy(
                level = Analyzer.levelToUnit(res.levelDb),
                calibration = calibUi,
                droneCalibrated = calibrated,
                drone = res.drone,
                chanter = chanter,
                history = stability.history(),
                sigma = stability.sigma(),
                mean = stability.mean(),
                score = stability.score(),
                inZone = stability.inZone(),
            )
        }
    }

    /**
     * Медиана от последните 5 кадъра (~0.2 s): маха единични грешни кадри и пропуски,
     * без да бави смяната на нотите. Показанието се задържа ~0.4 s след като звукът спре.
     */
    private fun smoothChanter(c: ChanterReading?): ChanterReading? {
        recent.addLast(c)
        while (recent.size > 5) recent.removeFirst()
        val now = SystemClock.elapsedRealtime()
        val present = recent.filterNotNull()
        if (c != null && present.size >= 2) {
            val freqs = present.map { it.frequency }.sorted()
            val med = freqs[freqs.size / 2]
            val close = present.filter { abs(Music.cents(it.frequency, med)) < 30 }
            if (close.size >= 2) {
                val f = close.sumOf { it.frequency } / close.size
                val tonic = c.frequency / 2.0.pow(c.centsFromTonic / 1200.0)
                val cents = Music.cents(f, tonic)
                shown = ChanterReading(
                    frequency = f,
                    centsFromTonic = cents,
                    step = Music.nearestStep(cents, _ui.value.showNeutral),
                    note = Music.noteName(f),
                    onDroneHarmonic = close.count { it.onDroneHarmonic } * 2 > close.size,
                )
                shownAt = now
            }
        } else if (now - shownAt > 400) {
            shown = null
        }
        return shown
    }

    companion object {
        const val FRAME = 8192
        const val HOP = 2048
        private const val KEY_PROFILE = "drone_profile"
        private const val KEY_TYPE = "gaida_type"
        private const val KEY_NEUTRAL = "show_neutral"
    }
}
