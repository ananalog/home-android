package home.android.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import home.android.app.vm.ScanViewModel
import home.protocol.Model

private val permissions =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

fun modelName(model: Int?) = when (model) {
    Model.CO2_EGG -> "Датчик CO2"
    Model.RELAY -> "Реле"
    Model.BOARD_PROBE -> "Проверка платы"
    null -> "Устройство"
    else -> "Модель $model"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onOpen: (String) -> Unit, vm: ScanViewModel = viewModel()) {
    val devices by vm.devices.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val error by vm.error.collectAsState()
    var granted by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r.values.all { it }
        if (granted) vm.start()
    }
    LaunchedEffect(Unit) { request.launch(permissions) }

    Scaffold(topBar = { TopAppBar(title = { Text("Устройства рядом") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            if (!granted) {
                Text("Нужен доступ к Bluetooth, чтобы найти устройства.", modifier = Modifier.padding(vertical = 16.dp))
                Button(onClick = { request.launch(permissions) }) { Text("Разрешить") }
                return@Column
            }
            if (devices.isEmpty()) {
                Text(
                    if (scanning) "Ищу устройства Home…\nУстройство должно быть включено и рядом."
                    else "Ничего не найдено. Если на устройстве нет экрана — нажмите на нём кнопку.",
                    modifier = Modifier.padding(vertical = 24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(devices, key = { it.address }) { d ->
                    Card(Modifier.fillMaxWidth().clickable { vm.stop(); onOpen(d.address) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name, style = MaterialTheme.typography.titleMedium)
                                Text("${modelName(d.advert?.model)} · ${d.address}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (d.advert?.configured == false) AssistChip(onClick = {}, label = { Text("не настроено") })
                            Spacer(Modifier.width(8.dp))
                            Text(signal(d.rssi), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            Button(onClick = { vm.start() }, enabled = !scanning, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(if (scanning) "Поиск…" else "Искать снова")
            }
        }
    }
}

private fun signal(rssi: Int) = when {
    rssi > -60 -> "▂▄▆█"
    rssi > -70 -> "▂▄▆"
    rssi > -80 -> "▂▄"
    else -> "▂"
}
