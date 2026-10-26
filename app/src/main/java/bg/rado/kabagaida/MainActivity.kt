package bg.rado.kabagaida

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bg.rado.kabagaida.ui.BreathScreen
import bg.rado.kabagaida.ui.DroneScreen
import bg.rado.kabagaida.ui.ErrorCard
import bg.rado.kabagaida.ui.KabaColors
import bg.rado.kabagaida.ui.KabaTheme
import bg.rado.kabagaida.ui.PermissionCard
import bg.rado.kabagaida.ui.TunerScreen

class MainActivity : ComponentActivity() {
    private val vm: KabaViewModel by viewModels()
    private var askedPermission = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setPermission(granted)
        if (granted) vm.startListening()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // екранът не гасне, докато свириш
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            KabaTheme {
                KabaApp(vm, onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        vm.setPermission(granted)
        if (granted) vm.startListening()
        else if (!askedPermission) {
            askedPermission = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        super.onStop()
        vm.stopListening()
    }
}

private data class Tab(val glyph: String, val label: String)

private val tabs = listOf(Tab("◉", "Ручило"), Tab("♪", "Тунер"), Tab("〰", "Духане"))

@Composable
fun KabaApp(vm: KabaViewModel, onRequestPermission: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(if (vm.ui.value.droneCalibrated == null) 0 else 1) }

    Scaffold(
        containerColor = KabaColors.Paper,
        bottomBar = {
            NavigationBar(containerColor = KabaColors.Card) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(t.glyph, fontSize = 20.sp) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                !ui.hasPermission -> PermissionCard(onRequestPermission)
                ui.micError != null -> ErrorCard(ui.micError ?: "") { vm.startListening() }
            }
            when (tab) {
                0 -> DroneScreen(
                    ui = ui,
                    onType = vm::setGaidaType,
                    onCalibrate = vm::startCalibration,
                    onCancel = vm::cancelCalibration,
                    onNeutral = vm::setShowNeutral,
                )
                1 -> TunerScreen(ui, onGoCalibrate = { tab = 0 })
                else -> BreathScreen(ui, onReset = vm::resetStability, onGoCalibrate = { tab = 0 })
            }
        }
    }
}
