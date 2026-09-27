package home.android.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import home.android.app.vm.DeviceViewModel
import home.android.app.vm.Phase
import home.android.core.Net
import home.android.core.format
import home.android.core.isAdvanced
import home.android.core.label
import home.android.core.number
import home.android.core.value
import home.protocol.BootPartition
import home.protocol.IpMode
import home.protocol.LinkState
import home.protocol.PointDef
import home.protocol.PointKind
import home.protocol.PointType
import home.protocol.ResetMode
import home.protocol.WifiAuth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(address: String, onBack: () -> Unit, vm: DeviceViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(address) { vm.connect(address) }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            snack.showSnackbar(it)
            vm.clearMessage()
        }
    }
    val title = ui.config?.name?.takeIf { it.isNotBlank() } ?: ui.info?.name?.takeIf { it.isNotBlank() } ?: modelName(ui.info?.model)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (ui.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (ui.phase) {
                Phase.Connecting -> Centered { CircularProgressIndicator(); Text("Подключение…", Modifier.padding(16.dp)) }
                Phase.Rebooting -> Centered {
                    CircularProgressIndicator()
                    Text("Устройство перезагружается и применяет настройки…", Modifier.padding(16.dp))
                }
                Phase.Error -> Centered {
                    Text(ui.error ?: "Ошибка", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = vm::retry) { Text("Подключиться снова") }
                }
                Phase.NeedCode -> CodeEntry(onCode = vm::pair)
                Phase.Ready -> ReadyContent(vm)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally, content = content)
}

@Composable
private fun CodeEntry(onCode: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Centered {
        Text("Введите код с экрана устройства", style = MaterialTheme.typography.titleMedium)
        Text("Без экрана: нажмите кнопку на устройстве и подключитесь снова.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { v -> code = v.filter(Char::isDigit).take(4) },
            label = { Text("Код") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onCode(code) }, enabled = code.length == 4) { Text("Подключить") }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp))
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

@Composable
private fun Line(name: String, value: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(name, Modifier.weight(1f))
        Text(value ?: "—", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadyContent(vm: DeviceViewModel) {
    val ui by vm.ui.collectAsState()
    val info = ui.info
    val cfg = ui.config
    val net = ui.net
    var wifiDialog by remember { mutableStateOf<String?>(null) }
    var resetDialog by remember { mutableStateOf<Int?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> uri?.let(vm::ota) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Section("Устройство") {
            Line("Модель", modelName(info?.model))
            Line("Прошивка", "${info?.fwVersion} (${bootName(info?.bootPartition)})")
            Line("ID", info?.deviceId)
            Line("Связь", linkName(net?.state))
            net?.server?.let { Line("Сервер", it) }
            if (info?.pendingVerify == true) {
                Text("Новая прошивка ещё не подтверждена: без подтверждения через 5 минут устройство откатится.", color = MaterialTheme.colorScheme.error)
                Button(onClick = vm::confirmFirmware) { Text("Подтвердить прошивку") }
            }
        }

        val sensors = ui.points.filter { (it.kind == PointKind.SENSOR || it.kind == PointKind.ACTUATOR) && !it.isAdvanced }
        if (sensors.isNotEmpty()) Section("Показания") {
            for (p in sensors) PointRow(p, p.id?.let { ui.values[it] }, vm)
        }

        Section("Wi-Fi") {
            Line("Сеть", net?.ssid ?: cfg?.wifiSsid?.takeIf { it.isNotEmpty() } ?: "не настроена")
            net?.rssi?.let { Line("Сигнал", "$it dBm") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::scanWifi, enabled = !ui.scanningWifi) { Text(if (ui.scanningWifi) "Поиск сетей…" else "Выбрать сеть") }
                TextButton(onClick = { wifiDialog = "" }) { Text("Скрытая сеть") }
            }
            for (n in ui.networks) {
                Row(Modifier.fillMaxWidth().clickable { wifiDialog = n.ssid }.padding(vertical = 6.dp)) {
                    Text(n.ssid ?: "", Modifier.weight(1f))
                    Text("${n.rssi} dBm ${if (n.auth == WifiAuth.OPEN) "" else "🔒"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        NetworkSection(vm)
        ServerSection(vm)

        Section("Имя") {
            var name by remember(cfg?.name) { mutableStateOf(cfg?.name ?: "") }
            OutlinedTextField(name, { name = it.take(24) }, label = { Text("Имя устройства") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.setName(name) }, enabled = name.isNotBlank()) { Text("Сохранить") }
        }

        val settings = ui.points.filter { it.kind == PointKind.SETTING || it.kind == PointKind.ACTION }
        if (settings.isNotEmpty()) Section("Настройки устройства") {
            for (p in settings) PointRow(p, p.id?.let { ui.values[it] }, vm)
        }

        Section("Обслуживание") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::identify) { Text("Мигнуть") }
                OutlinedButton(onClick = vm::reboot) { Text("Перезагрузить") }
                OutlinedButton(onClick = { picker.launch("*/*") }) { Text("Прошивка из файла") }
            }
            ui.otaPercent?.let {
                Text("Загрузка прошивки: $it%")
                LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider()
            TextButton(onClick = { resetDialog = ResetMode.SETTINGS }) { Text("Сбросить настройки", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { resetDialog = ResetMode.FIRMWARE }) { Text("Откатить к заводской прошивке", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { resetDialog = ResetMode.ALL }) { Text("Полный сброс", color = MaterialTheme.colorScheme.error) }
        }
    }

    wifiDialog?.let { preset ->
        var ssid by remember { mutableStateOf(preset) }
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { wifiDialog = null },
            title = { Text("Подключить к Wi-Fi") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(ssid, { ssid = it }, label = { Text("Сеть (SSID)") }, singleLine = true)
                    OutlinedTextField(pass, { pass = it }, label = { Text("Пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Text("Устройство перезагрузится. Если к серверу подключиться не удастся за 2 минуты, вернутся прежние настройки.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { vm.setWifi(ssid, pass); wifiDialog = null }, enabled = ssid.isNotBlank()) { Text("Подключить") } },
            dismissButton = { TextButton(onClick = { wifiDialog = null }) { Text("Отмена") } },
        )
    }

    resetDialog?.let { mode ->
        val text = when (mode) {
            ResetMode.SETTINGS -> "Сбросить настройки (Wi-Fi, IP, сервер, имя)? Прошивка останется."
            ResetMode.FIRMWARE -> "Загрузить заводскую прошивку? Настройки останутся."
            else -> "Полный сброс: настройки и прошивка к заводским?"
        }
        AlertDialog(
            onDismissRequest = { resetDialog = null },
            title = { Text("Сброс") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { vm.factoryReset(mode); resetDialog = null }) { Text("Сбросить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { resetDialog = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun NetworkSection(vm: DeviceViewModel) {
    val ui by vm.ui.collectAsState()
    val cur = ui.config?.net
    var static by remember(cur?.ipMode) { mutableStateOf(cur?.ipMode == IpMode.STATIC) }
    var ip by remember(cur) { mutableStateOf(Net.format(cur?.ip) ?: Net.format(ui.net?.current?.ip) ?: "") }
    var prefix by remember(cur) { mutableStateOf((cur?.mask ?: ui.net?.current?.mask)?.let { Net.prefixFromMask(it).toString() } ?: "24") }
    var gw by remember(cur) { mutableStateOf(Net.format(cur?.gw) ?: Net.format(ui.net?.current?.gw) ?: "") }
    var dns by remember(cur) { mutableStateOf(Net.format(cur?.dns1) ?: "") }
    Section("IP-адрес") {
        ui.net?.current?.let { Line("Сейчас", "${Net.format(it.ip)} / ${Net.format(it.gw)}") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Статический IP", Modifier.weight(1f))
            Switch(static, { static = it })
        }
        if (static) {
            OutlinedTextField(ip, { ip = it }, label = { Text("IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(prefix, { prefix = it.filter(Char::isDigit).take(2) }, label = { Text("Префикс") }, singleLine = true, modifier = Modifier.width(100.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(gw, { gw = it }, label = { Text("Шлюз") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(dns, { dns = it }, label = { Text("DNS (по умолчанию — шлюз)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.setStatic(ip, prefix.toIntOrNull() ?: 24, gw, dns) }) { Text("Применить") }
        } else if (cur?.ipMode == IpMode.STATIC) {
            Button(onClick = vm::setDhcp) { Text("Включить DHCP") }
        }
    }
}

@Composable
private fun ServerSection(vm: DeviceViewModel) {
    val ui by vm.ui.collectAsState()
    val cfg = ui.config
    var host by remember(cfg?.serverHost) { mutableStateOf(cfg?.serverHost ?: "") }
    var port by remember(cfg?.serverPort) { mutableStateOf((cfg?.serverPort ?: 7700).toString()) }
    Section("Сервер") {
        Text(if (host.isBlank()) "Автоматически (устройство ищет сервер в сети)" else "Адрес задан вручную", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(host, { host = it }, label = { Text("Адрес (пусто — автоматически)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Порт") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::findServers) { Text("Найти в сети") }
            Button(onClick = { vm.setServer(host, port.toIntOrNull() ?: 7700) }) { Text("Применить") }
        }
        for (s in ui.servers) {
            Row(Modifier.fillMaxWidth().clickable { host = s.host; port = s.port.toString() }.padding(vertical = 6.dp)) {
                Text(s.name, Modifier.weight(1f))
                Text("${s.host}:${s.port}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PointRow(p: PointDef, v: home.protocol.Value?, vm: DeviceViewModel) {
    val title = p.title ?: p.key ?: ""
    when {
        p.kind == PointKind.ACTION -> {
            var arg by remember { mutableStateOf(p.argDefault?.let { if (it == Math.round(it).toFloat()) Math.round(it).toString() else it.toString() } ?: "") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f))
                if (p.hasArg == true) OutlinedTextField(arg, { arg = it }, singleLine = true, modifier = Modifier.width(100.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedButton(onClick = { vm.invoke(p, arg.toFloatOrNull()) }) { Text("Выполнить") }
            }
        }
        p.kind == PointKind.SENSOR -> Line(title, p.format(v))
        p.type == PointType.BOOL -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Switch(v?.b == true, { vm.setPoint(p, value(it)) })
        }
        p.type == PointType.ENUM -> Column {
            Text(title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.options.forEachIndexed { i, o -> FilterChip(selected = v?.i == i, onClick = { vm.setPoint(p, value(i)) }, label = { Text(label(o)) }) }
            }
        }
        (p.type == PointType.I32 || p.type == PointType.F32) && p.min != null && p.max != null -> {
            var draft by remember(v) { mutableFloatStateOf(v?.number() ?: p.min!!) }
            Column {
                Row {
                    Text(title, Modifier.weight(1f))
                    Text(p.format(if (p.type == PointType.I32) value(Math.round(draft)) else value(draft)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val steps = p.step?.takeIf { it > 0 }?.let { ((p.max!! - p.min!!) / it).toInt() - 1 }?.coerceIn(0, 200) ?: 0
                Slider(
                    value = draft,
                    onValueChange = { draft = it },
                    valueRange = p.min!!..p.max!!,
                    steps = steps,
                    onValueChangeFinished = { vm.setPoint(p, if (p.type == PointType.I32) value(Math.round(draft)) else value(draft)) },
                )
            }
        }
        else -> Line(title, p.format(v))
    }
}

private fun bootName(b: Int?) = when (b) {
    BootPartition.FACTORY -> "заводская"
    BootPartition.OTA0 -> "ota_0"
    BootPartition.OTA1 -> "ota_1"
    else -> "?"
}

private fun linkName(s: Int?) = when (s) {
    LinkState.NO_CONFIG -> "Wi-Fi не настроен"
    LinkState.WIFI_CONNECTING -> "подключается к Wi-Fi"
    LinkState.IP_OK -> "в сети, ищет сервер"
    LinkState.SERVER_CONNECTING -> "подключается к серверу"
    LinkState.ONLINE -> "подключено к серверу"
    else -> "—"
}
