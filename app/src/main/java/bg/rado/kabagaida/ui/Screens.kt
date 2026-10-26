package bg.rado.kabagaida.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bg.rado.kabagaida.UiState
import bg.rado.kabagaida.core.GaidaType
import bg.rado.kabagaida.core.Music
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
private fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Title(text: String, sub: String? = null) {
    Column {
        Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = KabaColors.Ink)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = KabaColors.InkSoft)
    }
}

@Composable
private fun NeedCalibration(onGo: () -> Unit) {
    SectionCard(color = KabaColors.RedSoft) {
        Text("Първо калибрирай ручилото", fontWeight = FontWeight.SemiBold, color = KabaColors.Red)
        Text("Приложението сравнява гайдуницата с твоето ручило, а не с абстрактни ноти. Отнема 4 секунди.")
        Button(onClick = onGo) { Text("Към калибрирането") }
    }
}

private fun droneLabel(f: Double): String {
    val n = Music.noteName(f)
    return "$n ${formatCents(n.cents)} · %.1f Hz".format(f)
}

// ---------------------------------------------------------------- Ручило

@Composable
fun DroneScreen(
    ui: UiState,
    onType: (GaidaType) -> Unit,
    onCalibrate: () -> Unit,
    onCancel: () -> Unit,
    onNeutral: (Boolean) -> Unit,
) {
    ScreenColumn {
        Title("Ручило", "Настрой приложението по твоята гайда")

        SectionCard {
            Text("Вид гайда", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GaidaType.entries.forEach { t ->
                    FilterChip(selected = ui.gaidaType == t, onClick = { onType(t) }, label = { Text(t.label) })
                }
            }
            Text("Ручилото е ${ui.gaidaType.droneNote}.", style = MaterialTheme.typography.bodySmall, color = KabaColors.InkSoft)
        }

        SectionCard {
            Text("Калибриране", style = MaterialTheme.typography.titleSmall)
            Text("1. Заглуши гайдуницата (запуши я или я извади) — да свири само ручилото.")
            Text("2. Сложи телефона на 30–50 см от гайдата.")
            Text("3. Надуй равно и натисни „Калибрирай“. Слушам 4 секунди.")
            val c = ui.calibration
            if (c != null && c.running) {
                LinearProgressIndicator(progress = { c.progress }, modifier = Modifier.fillMaxWidth())
                Text(c.message, color = KabaColors.InkSoft)
                OutlinedButton(onClick = onCancel) { Text("Отказ") }
            } else {
                Button(onClick = onCalibrate, enabled = ui.hasPermission) {
                    Text(if (ui.droneCalibrated == null) "Калибрирай" else "Калибрирай отново")
                }
                if (c?.error != null) Text(c.error, color = KabaColors.Red)
                else if (c != null && c.message.isNotEmpty()) Text(c.message, color = KabaColors.Green)
            }
        }

        ui.droneCalibrated?.let { fd ->
            SectionCard(color = KabaColors.GreenSoft) {
                Text("Запомнено", style = MaterialTheme.typography.titleSmall, color = KabaColors.Green)
                Text("Ручило: ${droneLabel(fd)}")
                Text("Тоника: ${droneLabel(fd * ui.gaidaType.tonicMultiple)}")
                val d = ui.drone
                Text(
                    if (d != null) "Сега ручилото е ${formatCents(d.centsFromCalibrated)} спрямо запомненото"
                    else "Ручилото не се чува в момента",
                    color = KabaColors.InkSoft,
                )
            }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Неутрални интервали (¾ тон)", style = MaterialTheme.typography.titleSmall)
                    Text("Показва и стъпките между малки и големи интервали, типични за родопското свирене.",
                        style = MaterialTheme.typography.bodySmall, color = KabaColors.InkSoft)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = ui.showNeutral, onCheckedChange = onNeutral)
            }
        }
        LevelMeter(ui.level)
    }
}

// ---------------------------------------------------------------- Тунер

@Composable
fun TunerScreen(ui: UiState, onGoCalibrate: () -> Unit) {
    ScreenColumn {
        Title("Тунер", "Гайдуницата спрямо ручилото")
        val fd = ui.droneCalibrated
        if (fd == null) { NeedCalibration(onGoCalibrate); return@ScreenColumn }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(ui.drone != null)
            Text(
                ui.drone?.let { "Ручило ${Music.noteName(it.frequency)} · ${formatCents(it.centsFromCalibrated)} от калибрирането" }
                    ?: "Ручилото не се чува",
                style = MaterialTheme.typography.bodyMedium, color = KabaColors.InkSoft,
            )
        }

        val c = ui.chanter
        SectionCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (c == null) {
                    Text("—", fontSize = 56.sp, color = KabaColors.Line)
                    Text("Свири една нота", color = KabaColors.InkSoft)
                } else {
                    val dev = c.step.deviation
                    Text(c.step.shortLabel, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = KabaColors.Ink)
                    Text(c.step.label, style = MaterialTheme.typography.titleMedium, color = KabaColors.InkSoft)
                    Spacer(Modifier.height(4.dp))
                    Text(formatCents(dev), fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = deviationColor(dev))
                }
                TunerGauge(c?.step?.deviation)
                if (c != null) {
                    val dev = c.step.deviation
                    val (msg, col) = when {
                        abs(dev) <= 5 -> "В строй ✓" to KabaColors.Green
                        dev > 0 -> "Високо" to deviationColor(dev)
                        else -> "Ниско" to deviationColor(dev)
                    }
                    Text(msg, style = MaterialTheme.typography.titleLarge, color = col, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (c != null) {
            SectionCard {
                Text("%.1f Hz · %s %s".format(c.frequency, c.note, formatCents(c.note.cents)))
                Text("${formatCents(c.centsFromTonic)} от тониката", color = KabaColors.InkSoft)
                if (c.onDroneHarmonic) {
                    Text("Нотата съвпада с хармоник на ручилото — когато е в строй, „биенето“ (вълните в звука) спира.",
                        style = MaterialTheme.typography.bodySmall, color = KabaColors.InkSoft)
                }
            }
        }
        LevelMeter(ui.level)
    }
}

// ---------------------------------------------------------------- Равно надуване

@Composable
fun BreathScreen(ui: UiState, onReset: () -> Unit, onGoCalibrate: () -> Unit) {
    ScreenColumn {
        Title("Равно надуване", "Тонът на ручилото издава налягането в меха")
        if (ui.droneCalibrated == null) { NeedCalibration(onGoCalibrate); return@ScreenColumn }

        Text("Свири и дръж меха равно. Червената линия трябва да е права и в зелената ивица (±5¢). Вълни = „дишане“ в звука.",
            style = MaterialTheme.typography.bodyMedium)

        SectionCard {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(ui.score?.toString() ?: "—", fontSize = 56.sp, fontWeight = FontWeight.Bold,
                    color = ui.score?.let { if (it >= 80) KabaColors.Green else if (it >= 60) KabaColors.Amber else KabaColors.Red } ?: KabaColors.Line)
                Text(" / 100", style = MaterialTheme.typography.titleMedium, color = KabaColors.InkSoft, modifier = Modifier.padding(bottom = 12.dp))
                Spacer(Modifier.weight(1f))
                val score = ui.score
                Text(
                    when {
                        score == null -> if (ui.drone == null) "Ручилото не се чува" else "Слушам…"
                        score >= 85 -> "Отлично"
                        score >= 70 -> "Добре"
                        score >= 50 -> "Почти"
                        else -> "Продължавай"
                    },
                    style = MaterialTheme.typography.titleMedium, color = KabaColors.InkSoft,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("Трептене", ui.sigma?.let { "±%.1f¢".format(it) } ?: "—")
                Stat("В зоната", ui.inZone?.let { "${(it * 100).roundToInt()}%" } ?: "—")
                Stat("Средно", ui.mean?.let { formatCents(it) } ?: "—")
            }
            ui.mean?.let {
                if (abs(it) > 8) Text(
                    if (it > 0) "Тонът е по-висок от калибрирания — налягането е по-силно."
                    else "Тонът е по-нисък от калибрирания — налягането е по-слабо.",
                    style = MaterialTheme.typography.bodySmall, color = KabaColors.InkSoft,
                )
            }
        }

        StabilityGraph(ui.history)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("−15 s", style = MaterialTheme.typography.labelSmall, color = KabaColors.InkSoft)
            Text("сега", style = MaterialTheme.typography.labelSmall, color = KabaColors.InkSoft)
        }
        OutlinedButton(onClick = onReset) { Text("Започни отначало") }
        LevelMeter(ui.level)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = KabaColors.InkSoft)
    }
}

// ---------------------------------------------------------------- Разрешение / грешки

@Composable
fun PermissionCard(onRequest: () -> Unit) {
    Box(Modifier.padding(16.dp)) {
        SectionCard(color = KabaColors.RedSoft) {
            Text("Нужен е микрофонът", fontWeight = FontWeight.SemiBold, color = KabaColors.Red)
            Text("Приложението слуша гайдата, за да показва строя и равномерността. Звукът не се записва и не излиза от телефона.",
                textAlign = TextAlign.Start)
            Button(onClick = onRequest) { Text("Разреши микрофона") }
        }
    }
}

@Composable
fun ErrorCard(text: String, onRetry: () -> Unit) {
    Box(Modifier.padding(16.dp)) {
        SectionCard(color = KabaColors.RedSoft) {
            Text(text, color = KabaColors.Red)
            OutlinedButton(onClick = onRetry) { Text("Опитай пак") }
        }
    }
}
