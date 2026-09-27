package home.android.core

import home.protocol.DeviceConfig
import home.protocol.ErrorCode
import home.protocol.IpMode
import home.protocol.Model
import home.protocol.NetConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceClientTest {
    private fun fakeImage(size: Int = 20_000, model: Int = Model.CO2_EGG, version: String = "1.2.0"): ByteArray {
        val img = ByteArray(size) { (it * 31).toByte() }
        img[0] = 0xE9.toByte()
        val b = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 32 until 32 + 256) img[i] = 0
        b.putInt(32, 0xABCD5432.toInt())
        version.toByteArray().copyInto(img, 48)
        "co2-egg".toByteArray().copyInto(img, 80)
        for (i in 288 until 320) img[i] = 0
        b.putInt(288, 0x454D4F48)
        b.putShort(292, model.toShort())
        b.putShort(294, 0xFFFF.toShort())
        img[296] = 1
        return img
    }

    @Test
    fun pairingGatesSetupCommands() = runTest {
        val dev = FakeDevice()
        val c = DeviceClient(dev, backgroundScope)
        assertEquals("a1b2c3d4e5f6", c.info().deviceId)
        assertEquals(2, c.describe().size)          // read-only works before pairing
        val locked = assertFailsWith<DeviceError> { c.setConfig(DeviceConfig().apply { name = "x" }) }
        assertTrue(locked.isForbidden)
        assertFailsWith<DeviceError> { c.pair(1111) }
        c.pair(1234)
        c.setConfig(DeviceConfig().apply { name = "Спальня" })
        assertEquals("Спальня", c.config().name)
    }

    @Test
    fun wifiAndStaticIp() = runTest {
        val dev = FakeDevice()
        val c = DeviceClient(dev, backgroundScope)
        c.pair(1234)
        val nets = c.scanWifi()
        assertEquals("Home", nets.first().ssid)     // strongest first
        c.setConfig(DeviceConfig().apply {
            wifiSsid = "Home"
            wifiPass = "secret"
            net = NetConfig().apply {
                ipMode = IpMode.STATIC; ip = Net.parse("192.168.1.50"); mask = Net.maskFromPrefix(24); gw = Net.parse("192.168.1.1")
            }
        })
        assertEquals("Home", dev.config.wifiSsid)
        assertEquals("192.168.1.50", Net.format(dev.config.net!!.ip))
        assertEquals("255.255.255.0", Net.format(dev.config.net!!.mask))
        assertEquals(24, Net.prefixFromMask(dev.config.net!!.mask!!))
    }

    @Test
    fun valuesAndErrors() = runTest {
        val dev = FakeDevice()
        val c = DeviceClient(dev, backgroundScope)
        c.pair(1234)
        val pts = c.describe()
        val mode = pts.first { it.key == "display_mode" }
        c.set(13, value(1))
        assertEquals("ночь", mode.format(c.values()[13]))
        val ro = assertFailsWith<DeviceError> { c.set(1, value(5f)) }
        assertEquals(ErrorCode.FORBIDDEN, ro.code)
    }

    @Test
    fun otaUploadsAndResumes() = runTest {
        val dev = FakeDevice(mtu = 185)
        val c = DeviceClient(dev, backgroundScope)
        c.pair(1234)
        val img = fakeImage()
        val info = FirmwareImage.parse(img)
        assertEquals("1.2.0", info.version)
        assertEquals(Model.CO2_EGG, info.model)

        dev.failAfterChunks = 5
        assertFailsWith<DeviceError> { c.ota(img, info.model, info.version).toList() }
        val progress = c.ota(img, info.model, info.version).toList()   // resumes where it stopped
        assertTrue(progress.first().sent > 0)
        assertTrue(progress.last().done)
        assertContentEquals(img, dev.flashed)
    }

    @Test
    fun wrongModelIsRefused() = runTest {
        val dev = FakeDevice()
        val c = DeviceClient(dev, backgroundScope)
        c.pair(1234)
        val e = assertFailsWith<DeviceError> { c.ota(fakeImage(model = Model.RELAY), Model.RELAY, "1").toList() }
        assertEquals(ErrorCode.OTA_WRONG_MODEL, e.code)
    }

    @Test
    fun imageParsing() {
        assertFailsWith<IllegalArgumentException> { FirmwareImage.parse(ByteArray(1000)) }
        val adv = Advert.parse(byteArrayOf(1, 0, 1, 1))!!
        assertEquals(Model.CO2_EGG, adv.model)
        assertTrue(adv.configured)
    }
}
