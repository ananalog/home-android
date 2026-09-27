package home.android.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import home.android.app.ble.FoundDevice
import home.android.app.ble.Scanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val scanner = Scanner(app)
    private val _devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val devices: StateFlow<List<FoundDevice>> = _devices
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        if (!scanner.adapterEnabled) {
            _error.value = "Включите Bluetooth"
            return
        }
        _error.value = null
        _scanning.value = true
        val found = mutableMapOf<String, FoundDevice>()
        job = viewModelScope.launch {
            launch {
                // Drop devices not heard for 15 s.
                while (true) {
                    delay(3000)
                    val now = System.currentTimeMillis()
                    found.values.removeAll { now - it.seenAt > 15_000 }
                    _devices.value = found.values.sortedByDescending { it.rssi }
                }
            }
            scanner.scan()
                .catch { _error.value = it.message }
                .collect {
                    found[it.address] = it
                    _devices.value = found.values.sortedByDescending { d -> d.rssi }
                }
        }
        // Scanning drains the battery: stop after a minute.
        viewModelScope.launch {
            delay(60_000)
            stop()
        }
    }

    fun stop() {
        job?.cancel()
        _scanning.value = false
    }

    override fun onCleared() = stop()
}
