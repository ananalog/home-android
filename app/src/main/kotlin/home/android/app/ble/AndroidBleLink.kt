package home.android.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import home.android.core.BleLink
import home.protocol.Proto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/**
 * GATT connection to a Home device. Android allows one GATT operation at a time, so every operation
 * takes [op] and waits for its callback.
 */
@SuppressLint("MissingPermission")
class AndroidBleLink private constructor(private val context: Context, private val device: BluetoothDevice) : BleLink {
    companion object {
        val SERVICE: UUID = UUID.fromString(Proto.BLE_SERVICE_UUID)
        val RX: UUID = UUID.fromString(Proto.BLE_RX_UUID)
        val TX: UUID = UUID.fromString(Proto.BLE_TX_UUID)
        val INFO: UUID = UUID.fromString(Proto.BLE_INFO_UUID)
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        suspend fun connect(context: Context, device: BluetoothDevice, timeoutMs: Long = 20_000): AndroidBleLink {
            val link = AndroidBleLink(context.applicationContext, device)
            try {
                withTimeout(timeoutMs) { link.open() }
            } catch (e: Exception) {
                link.close()
                throw IOException("не удалось подключиться: ${e.message}", e)
            }
            return link
        }
    }

    private var gatt: BluetoothGatt? = null
    private lateinit var rx: BluetoothGattCharacteristic
    private lateinit var tx: BluetoothGattCharacteristic
    private lateinit var info: BluetoothGattCharacteristic
    private val op = Mutex()
    @Volatile private var pending: CompletableDeferred<Any?>? = null
    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    private val _connected = MutableStateFlow(false)
    private var _mtu = 23

    override val mtu get() = _mtu
    override val notifications: SharedFlow<ByteArray> = _notifications
    val connected: StateFlow<Boolean> = _connected

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                pending?.complete(Unit)
            } else {
                _connected.value = false
                pending?.completeExceptionally(IOException("соединение потеряно (status $status)"))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) _mtu = mtu
            pending?.complete(mtu)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            complete(status, Unit)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            complete(status, Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            complete(status, Unit)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            complete(status, value)
        }

        @Deprecated("Android < 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) complete(status, c.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == TX) _notifications.tryEmit(value)
        }

        @Deprecated("Android < 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && c.uuid == TX) _notifications.tryEmit(c.value.copyOf())
        }
    }

    private fun complete(status: Int, value: Any?) {
        val p = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) p.complete(value) else p.completeExceptionally(IOException("GATT error $status"))
    }

    /** Runs one GATT operation: [start] must return true if the request was accepted by the stack. */
    private suspend fun <T> gattOp(timeoutMs: Long = 10_000, start: (BluetoothGatt) -> Boolean): T = op.withLock {
        val g = gatt ?: throw IOException("нет соединения")
        val d = CompletableDeferred<Any?>()
        pending = d
        try {
            if (!start(g)) throw IOException("устройство занято")
            @Suppress("UNCHECKED_CAST")
            withTimeout(timeoutMs) { d.await() } as T
        } finally {
            pending = null
        }
    }

    private suspend fun open() {
        op.withLock {
            val d = CompletableDeferred<Any?>()
            pending = d
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            d.await()
            pending = null
        }
        gattOp<Any?> { it.requestMtu(247) }
        gattOp<Any?>(15_000) { it.discoverServices() }
        val g = gatt ?: throw IOException("нет соединения")
        val service = g.getService(SERVICE) ?: throw IOException("это не устройство Home")
        rx = service.getCharacteristic(RX) ?: throw IOException("нет характеристики RX")
        tx = service.getCharacteristic(TX) ?: throw IOException("нет характеристики TX")
        info = service.getCharacteristic(INFO) ?: throw IOException("нет характеристики INFO")
        g.setCharacteristicNotification(tx, true)
        val cccd = tx.getDescriptor(CCCD) ?: throw IOException("нет CCCD")
        gattOp<Any?> { gg ->
            if (Build.VERSION.SDK_INT >= 33) gg.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gg.writeDescriptor(cccd)
            }
        }
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    override suspend fun write(fragment: ByteArray) {
        // Write without response: Android still reports each queued write, which gives flow control.
        gattOp<Any?> { g ->
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(rx, fragment, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                rx.value = fragment
                @Suppress("DEPRECATION")
                g.writeCharacteristic(rx)
            }
        }
    }

    override suspend fun readInfo(): ByteArray = gattOp { it.readCharacteristic(info) }

    override suspend fun close() {
        _connected.value = false
        pending?.cancel()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }
}
