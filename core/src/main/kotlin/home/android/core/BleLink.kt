package home.android.core

import kotlinx.coroutines.flow.Flow

/**
 * A GATT connection to a Home device: write fragments to RX, receive TX notifications, read INFO.
 * Implemented on Android by AndroidBleLink; tests use an in-process fake device.
 */
interface BleLink {
    /** Negotiated ATT MTU (payload per write = mtu - 3). */
    val mtu: Int

    /** Notifications from the TX characteristic (message fragments). */
    val notifications: Flow<ByteArray>

    /** Writes one fragment to the RX characteristic. */
    suspend fun write(fragment: ByteArray)

    /** Reads the INFO characteristic (HELLO body). */
    suspend fun readInfo(): ByteArray

    suspend fun close()
}
