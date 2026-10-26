package bg.rado.kabagaida.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log

/**
 * Чете микрофона в отделна нишка и на всеки [hopSize] нови семпъла подава
 * последните [frameSize] семпъла (от −1 до 1) на [onFrame].
 */
class MicCapture(
    private val frameSize: Int,
    private val hopSize: Int,
    private val onFrame: (frame: FloatArray, sampleRate: Int) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    var sampleRate: Int = 0
        private set

    /** Връща текст на грешката или null при успех. */
    @SuppressLint("MissingPermission") // разрешението се проверява преди извикване
    fun start(context: Context): String? {
        if (running) return null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        // UNPROCESSED/VOICE_RECOGNITION пропускат шумопотискането, което „реже“ ручилото
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION

        var record: AudioRecord? = null
        var isFloat = false
        loop@ for (rate in intArrayOf(48000, 44100)) {
            for (encoding in intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, encoding)
                if (min <= 0) continue
                val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                val size = maxOf(min, hopSize * bytesPerSample * 8)
                val r = try {
                    AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, encoding, size)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord $rate/$encoding: ${e.message}")
                    null
                }
                if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                    record = r; sampleRate = rate; isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT
                    break@loop
                }
                r?.release()
            }
        }
        val rec = record ?: return "Микрофонът не може да се отвори."
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            rec.release()
            return "Микрофонът е зает от друго приложение."
        }
        running = true
        val rate = sampleRate
        thread = Thread({ loop(rec, isFloat, rate) }, "kaba-mic").also { it.start() }
        return null
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop(rec: AudioRecord, isFloat: Boolean, rate: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val ring = FloatArray(frameSize)
        var write = 0
        var filled = 0
        val hopF = FloatArray(hopSize)
        val hopS = ShortArray(hopSize)
        val frame = FloatArray(frameSize)
        try {
            while (running) {
                var got = 0
                while (got < hopSize && running) {
                    val n = if (isFloat) {
                        rec.read(hopF, got, hopSize - got, AudioRecord.READ_BLOCKING)
                    } else {
                        rec.read(hopS, got, hopSize - got)
                    }
                    if (n < 0) { Log.w(TAG, "read error $n"); running = false; break }
                    got += n
                }
                if (!running) break
                for (i in 0 until hopSize) {
                    ring[write] = if (isFloat) hopF[i] else hopS[i] / 32768f
                    write = (write + 1) % frameSize
                }
                filled = minOf(frameSize, filled + hopSize)
                if (filled < frameSize) continue
                // подреждаме кръговия буфер хронологично
                val tail = frameSize - write
                System.arraycopy(ring, write, frame, 0, tail)
                System.arraycopy(ring, 0, frame, tail, write)
                onFrame(frame, rate)
            }
        } finally {
            try { rec.stop() } catch (_: IllegalStateException) {}
            rec.release()
        }
    }

    private companion object { const val TAG = "MicCapture" }
}
