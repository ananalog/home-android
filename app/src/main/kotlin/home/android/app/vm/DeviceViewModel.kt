package home.android.app.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import home.android.app.ble.AndroidBleLink
import home.android.app.ble.FoundServer
import home.android.app.ble.Scanner
import home.android.app.ble.ServerFinder
import home.android.core.DeviceClient
import home.android.core.DeviceError
import home.android.core.FirmwareImage
import home.android.core.Net
import home.protocol.DeviceConfig
import home.protocol.HelloReq
import home.protocol.IpMode
import home.protocol.LinkState
import home.protocol.NetConfig
import home.protocol.NetStatus
import home.protocol.PointDef
import home.protocol.Value
import home.protocol.WifiNet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class Phase { Connecting, NeedCode, Ready, Rebooting, Error }

data class DeviceUi(
    val phase: Phase = Phase.Connecting,
    val error: String? = null,
    val info: HelloReq? = null,
    val points: List<PointDef> = emptyList(),
    val values: Map<Int, Value> = emptyMap(),
    val config: DeviceConfig? = null,
    val net: NetStatus? = null,
    val networks: List<WifiNet> = emptyList(),
    val scanningWifi: Boolean = false,
    val servers: List<FoundServer> = emptyList(),
    val busy: String? = null,
    val otaPercent: Int? = null,
    val message: String? = null,
)

/**
 * One device over BLE: connect, pairing code, settings (Wi-Fi, IP, server, name), points,
 * maintenance (identify, reboot, factory reset), OTA from a file.
 */
class DeviceViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(DeviceUi())
    val ui: StateFlow<DeviceUi> = _ui
    private var client: DeviceClient? = null
    private var link: AndroidBleLink? = null
    private var address: String? = null
    private var poll: Job? = null

    private fun update(f: (DeviceUi) -> DeviceUi) {
        _ui.value = f(_ui.value)
    }

    fun connect(address: String) {
        if (this.address == address && client != null) return
        this.address = address
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        update { it.copy(phase = Phase.Connecting, error = null) }
        try {
            val l = AndroidBleLink.connect(getApplication(), Scanner(getApplication()).device(address!!))
            link = l
            val c = DeviceClient(l, viewModelScope)
            client = c
            val info = c.info()
            val points = c.describe()
            update { it.copy(info = info, points = points) }
            viewModelScope.launch { l.connected.first { !it }; onDisconnected() }
            // A settings read tells whether the phone must enter the code from the screen.
            val cfg = try {
                c.config()
            } catch (e: DeviceError) {
                if (!e.isForbidden) throw e
                null
            }
            if (cfg == null) update { it.copy(phase = Phase.NeedCode) } else ready(cfg)
        } catch (e: Exception) {
            update { it.copy(phase = Phase.Error, error = e.message ?: e.toString()) }
        }
    }

    private suspend fun ready(cfg: DeviceConfig? = null) {
        val c = client ?: return
        val config = cfg ?: c.config()
        update { it.copy(phase = Phase.Ready, config = config) }
        refreshStatus()
        poll?.cancel()
        poll = viewModelScope.launch {
            while (true) {
                runCatching { c.values() }.onSuccess { v -> update { it.copy(values = v) } }
                delay(2000)
            }
        }
    }

    private fun onDisconnected() {
        poll?.cancel()
        if (_ui.value.phase == Phase.Ready || _ui.value.phase == Phase.NeedCode)
            update { it.copy(phase = Phase.Error, error = "Соединение потеряно") }
    }

    fun retry() {
        viewModelScope.launch {
            close()
            open()
        }
    }

    fun pair(code: String) = action("Проверка кода") {
        client!!.pair(code.trim().toInt())
        ready()
    }

    private fun action(busy: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            update { it.copy(busy = busy, message = null) }
            try {
                block()
            } catch (e: DeviceError) {
                update { it.copy(message = e.message) }
            } catch (e: Exception) {
                update { it.copy(message = e.message ?: e.toString()) }
            } finally {
                update { it.copy(busy = null) }
            }
        }
    }

    suspend fun refreshStatus() {
        runCatching { client!!.netStatus() }.onSuccess { n -> update { it.copy(net = n) } }
    }

    fun scanWifi() = viewModelScope.launch {
        update { it.copy(scanningWifi = true) }
        runCatching { client!!.scanWifi() }
            .onSuccess { n -> update { it.copy(networks = n) } }
            .onFailure { e -> update { it.copy(message = e.message) } }
        update { it.copy(scanningWifi = false) }
    }

    fun findServers() = viewModelScope.launch {
        update { it.copy(servers = emptyList()) }
        val found = mutableListOf<FoundServer>()
        runCatching {
            withTimeoutOrNull(4000) {
                ServerFinder(getApplication()).find().collect { s ->
                    if (found.none { it.host == s.host && it.port == s.port }) {
                        found.add(s)
                        update { it.copy(servers = found.toList()) }
                    }
                }
            }
        }
        if (found.isEmpty()) update { it.copy(message = "Сервер в этой сети не найден (телефон должен быть в той же Wi-Fi сети)") }
    }

    /** Wi-Fi, IP and server changes make the device reboot; then we reconnect and show its network state. */
    private fun applyNetwork(cfg: DeviceConfig) = action("Применение настроек") {
        client!!.setConfig(cfg)
        update { it.copy(phase = Phase.Rebooting) }
        close()
        delay(6000)
        open()
        // Watch the device reach Wi-Fi and the server.
        repeat(30) {
            refreshStatus()
            if (_ui.value.net?.state == LinkState.ONLINE) return@action
            delay(2000)
        }
    }

    fun setWifi(ssid: String, password: String) =
        applyNetwork(DeviceConfig().apply { wifiSsid = ssid; wifiPass = password })

    fun setDhcp() = applyNetwork(DeviceConfig().apply { net = NetConfig().apply { ipMode = IpMode.DHCP } })

    fun setStatic(ip: String, prefix: Int, gateway: String, dns: String?) {
        val cfg = try {
            val a = Net.parse(ip)
            val m = Net.maskFromPrefix(prefix)
            val g = Net.parse(gateway)
            require(Net.sameSubnet(a, g, m)) { "шлюз не в подсети устройства" }
            DeviceConfig().apply {
                net = NetConfig().apply {
                    ipMode = IpMode.STATIC; this.ip = a; mask = m; gw = g
                    dns1 = if (dns.isNullOrBlank()) g else Net.parse(dns)
                }
            }
        } catch (e: IllegalArgumentException) {
            update { it.copy(message = e.message) }
            return
        }
        applyNetwork(cfg)
    }

    fun setServer(host: String, port: Int) =
        applyNetwork(DeviceConfig().apply { serverHost = host.trim(); serverPort = port })

    fun setName(newName: String) = action("Сохранение") {
        val n = newName.trim()
        client!!.setConfig(DeviceConfig().apply { name = n })
        update { it.copy(config = it.config?.apply { name = n }, message = "Сохранено") }
    }

    fun setPoint(p: PointDef, v: Value) = action("Сохранение") {
        val applied = client!!.set(p.id!!, v)
        update { it.copy(values = it.values + (p.id!! to applied)) }
    }

    fun invoke(p: PointDef, arg: Float?) = action(p.title ?: "Действие") {
        val text = client!!.invoke(p.id!!, arg)
        update { it.copy(message = text ?: "Готово") }
    }

    fun identify() = action("Мигаю") { client!!.identify(5) }

    fun reboot() = action("Перезагрузка") {
        client!!.reboot()
        update { it.copy(phase = Phase.Rebooting) }
        close()
        delay(6000)
        open()
    }

    fun factoryReset(mode: Int) = action("Сброс") {
        client!!.factoryReset(mode)
        update { it.copy(phase = Phase.Rebooting) }
        close()
        delay(8000)
        open()
    }

    fun confirmFirmware() = action("Подтверждение") {
        client!!.confirmFirmware()
        update { it.copy(message = "Прошивка подтверждена") }
    }

    fun ota(uri: Uri) = action("Обновление прошивки") {
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("не удалось прочитать файл")
        val img = FirmwareImage.parse(bytes)
        val model = _ui.value.info?.model
        require(model == null || img.model == model) { "прошивка для другой модели устройства" }
        client!!.ota(bytes, img.model, img.version).collect { p -> update { it.copy(otaPercent = p.percent) } }
        update { it.copy(otaPercent = null, phase = Phase.Rebooting, message = "Прошивка ${img.version} загружена, устройство перезагружается") }
        close()
        delay(8000)
        open()
        // Without a server the new firmware must be confirmed from the phone.
        if (_ui.value.info?.pendingVerify == true) client?.confirmFirmware()
    }

    fun clearMessage() = update { it.copy(message = null) }

    private suspend fun close() {
        poll?.cancel()
        client?.close()
        client = null
        link = null
    }

    override fun onCleared() {
        val c = client
        client = null
        poll?.cancel()
        // viewModelScope is already cancelled here: close the GATT connection on its own scope.
        if (c != null) CoroutineScope(Dispatchers.Default).launch { runCatching { c.close() } }
    }
}
