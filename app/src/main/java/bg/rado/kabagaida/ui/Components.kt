package bg.rado.kabagaida.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SectionCard(modifier: Modifier = Modifier, color: Color = KabaColors.Card, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        border = androidx.compose.foundation.BorderStroke(1.dp, KabaColors.Line),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Цвят според отклонението: зелено ≤5¢, кехлибар ≤15¢, червено над това. */
fun deviationColor(cents: Double): Color = when {
    abs(cents) <= 5 -> KabaColors.Green
    abs(cents) <= 15 -> KabaColors.Amber
    else -> KabaColors.Red
}

fun formatCents(c: Double): String = (if (c >= 0) "+" else "−") + "%.0f¢".format(abs(c))

/** Полукръгъл тунер −50…+50 центa. */
@Composable
fun TunerGauge(cents: Double?, modifier: Modifier = Modifier) {
    val target = (cents ?: 0.0).coerceIn(-50.0, 50.0).toFloat()
    val needle by animateFloatAsState(targetValue = target, animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f), label = "needle")
    val needleColor = if (cents == null) KabaColors.Line else deviationColor(cents)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(170.dp)) {
            val stroke = 18.dp.toPx()
            val radius = minOf(size.width / 2f, size.height) - stroke
            val center = Offset(size.width / 2f, size.height - 6.dp.toPx())
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            fun angleFor(c: Float) = 180f + (c + 50f) / 100f * 180f
            // зони
            drawArc(KabaColors.RedSoft, 180f, 180f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            drawArc(Color(0xFFF7E6C4), angleFor(-15f), 30f / 100f * 180f, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(KabaColors.GreenSoft, angleFor(-5f), 10f / 100f * 180f, false, topLeft, arcSize, style = Stroke(stroke))
            // деления през 10¢
            for (c in -50..50 step 10) {
                val a = Math.toRadians(angleFor(c.toFloat()).toDouble())
                val long = c % 50 == 0 || c == 0
                val r1 = radius - stroke / 2f - (if (long) 14.dp.toPx() else 8.dp.toPx())
                val r2 = radius - stroke / 2f - 2.dp.toPx()
                drawLine(
                    KabaColors.InkSoft,
                    Offset(center.x + r1 * cos(a).toFloat(), center.y + r1 * sin(a).toFloat()),
                    Offset(center.x + r2 * cos(a).toFloat(), center.y + r2 * sin(a).toFloat()),
                    strokeWidth = if (long) 2.dp.toPx() else 1.dp.toPx(),
                )
            }
            // стрелка
            val a = Math.toRadians(angleFor(needle).toDouble())
            val tip = Offset(center.x + (radius + stroke / 2f) * cos(a).toFloat(), center.y + (radius + stroke / 2f) * sin(a).toFloat())
            drawLine(needleColor, center, tip, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(needleColor, radius = 8.dp.toPx(), center = center)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("−50¢", style = MaterialTheme.typography.labelSmall, color = KabaColors.InkSoft)
            Text("ниско · в строй · високо", style = MaterialTheme.typography.labelSmall, color = KabaColors.InkSoft)
            Text("+50¢", style = MaterialTheme.typography.labelSmall, color = KabaColors.InkSoft)
        }
    }
}

/** Хоризонтален индикатор на нивото на микрофона. */
@Composable
fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Микрофон", style = MaterialTheme.typography.labelMedium, color = KabaColors.InkSoft)
        Box(
            Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(KabaColors.CardAlt)
        ) {
            Box(
                Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(8.dp)
                    .background(if (level > 0.92f) KabaColors.Red else KabaColors.Green)
            )
        }
    }
}

@Composable
fun StatusDot(on: Boolean) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) KabaColors.Green else KabaColors.Line))
}

/** Графика на ручилото в центове за последните 15 s; зелена ивица = ±zone. */
@Composable
fun StabilityGraph(history: List<Pair<Float, Float>>, zone: Float = 5f, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(KabaColors.Card)) {
        val span = 30f // ±30¢
        val seconds = 15f
        fun y(c: Float) = size.height / 2f - (c.coerceIn(-span, span) / span) * (size.height / 2f - 8.dp.toPx())
        fun x(t: Float) = size.width * (1f + t / seconds)
        drawRect(KabaColors.GreenSoft, topLeft = Offset(0f, y(zone)), size = Size(size.width, y(-zone) - y(zone)))
        for (c in listOf(-20f, -10f, 10f, 20f)) {
            drawLine(KabaColors.Line, Offset(0f, y(c)), Offset(size.width, y(c)), strokeWidth = 1.dp.toPx())
        }
        drawLine(KabaColors.Green, Offset(0f, y(0f)), Offset(size.width, y(0f)), strokeWidth = 1.5.dp.toPx())
        if (history.size >= 2) {
            val path = Path()
            history.forEachIndexed { i, (t, c) ->
                if (i == 0) path.moveTo(x(t), y(c)) else path.lineTo(x(t), y(c))
            }
            drawPath(path, KabaColors.Red, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
