package bg.rado.kabagaida.core

import org.junit.Assert.assertTrue
import org.junit.Test

/** Пуска синтетичните тестове от Android Studio: десен клик → Run 'CoreTest'. */
class CoreTest {
    @Test
    fun synthetic() {
        val failures = SelfTests.runAll()
        println(SelfTests.log)
        assertTrue("Неуспешни: $failures", failures.isEmpty())
    }
}
