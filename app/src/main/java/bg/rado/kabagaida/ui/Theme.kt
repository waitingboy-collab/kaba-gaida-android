package bg.rado.kabagaida.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Светла „хартиена“ тема с родопско червено. */
object KabaColors {
    val Paper = Color(0xFFFAF6EE)
    val Card = Color(0xFFFFFDF8)
    val CardAlt = Color(0xFFF2EBDD)
    val Ink = Color(0xFF2B2420)
    val InkSoft = Color(0xFF6B5F55)
    val Red = Color(0xFFA4161A)
    val RedSoft = Color(0xFFF6DEDC)
    val Green = Color(0xFF2E6B4F)
    val GreenSoft = Color(0xFFDCEDE3)
    val Amber = Color(0xFFC07A00)
    val Line = Color(0xFFD9CDB8)
}

private val scheme = lightColorScheme(
    primary = KabaColors.Red,
    onPrimary = Color.White,
    primaryContainer = KabaColors.RedSoft,
    onPrimaryContainer = KabaColors.Red,
    secondary = KabaColors.Green,
    onSecondary = Color.White,
    secondaryContainer = KabaColors.GreenSoft,
    onSecondaryContainer = KabaColors.Green,
    background = KabaColors.Paper,
    onBackground = KabaColors.Ink,
    surface = KabaColors.Card,
    onSurface = KabaColors.Ink,
    surfaceVariant = KabaColors.CardAlt,
    onSurfaceVariant = KabaColors.InkSoft,
    surfaceContainer = KabaColors.CardAlt,
    outline = KabaColors.Line,
    error = KabaColors.Red,
)

@Composable
fun KabaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
