package home.android.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import home.android.core.Advert
import home.protocol.Proto
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val advert: Advert?,
    val device: BluetoothDevice,
    val seenAt: Long = System.currentTimeMillis(),
)

/** BLE scan filtered by the Home service UUID. */
@SuppressLint("MissingPermission")
class Scanner(private val context: Context) {
    val adapterEnabled: Boolean
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true

    fun device(address: String): BluetoothDevice =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.getRemoteDevice(address)

    fun scan(): Flow<FoundDevice> = callbackFlow {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth выключен"))
            return@callbackFlow
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val record = r.scanRecord
                trySend(
                    FoundDevice(
                        address = r.device.address,
                        name = record?.deviceName ?: r.device.name ?: "Home",
                        rssi = r.rssi,
                        advert = Advert.parse(record?.getManufacturerSpecificData(Proto.BLE_MANUFACTURER_ID)),
                        device = r.device,
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("ошибка поиска BLE: $errorCode"))
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(AndroidBleLink.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }
}
