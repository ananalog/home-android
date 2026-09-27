package home.android.core

import home.protocol.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.security.MessageDigest

/** An in-process Home device speaking the protocol over "BLE" fragments, like home_core does. */
class FakeDevice(private val code: Int = 1234, override val mtu: Int = 64) : BleLink {
    private val out = MutableSharedFlow<ByteArray>(extraBufferCapacity = 10_000)
    override val notifications: SharedFlow<ByteArray> = out
    private val rx = BleReassembler()
    var unlocked = false
    var config = DeviceConfig().apply { name = "Кухня"; wifiSsid = ""; net = NetConfig().apply { ipMode = IpMode.DHCP } }
    val values = mutableMapOf(1 to Value().apply { f = 650f }, 13 to Value().apply { i = 0 })
    var otaImage: java.io.ByteArrayOutputStream? = null
    var otaSha: ByteArray? = null
    var flashed: ByteArray? = null
    var failAfterChunks = -1
    private var chunks = 0
    var closed = false

    val points = listOf(
        PointDef().apply { id = 1; key = "co2"; title = "CO2"; kind = PointKind.SENSOR; type = PointType.F32; unit = "ppm" },
        PointDef().apply { id = 13; key = "display_mode"; title = "Экран"; kind = PointKind.SETTING; type = PointType.ENUM; options.addAll(listOf("co2", "night")) },
    )

    override suspend fun readInfo(): ByteArray {
        val w = TlvWriter()
        HelloReq().apply { deviceId = "a1b2c3d4e5f6"; model = Model.CO2_EGG; fwVersion = "1.0.0" }.write(w)
        return w.toByteArray()
    }

    override suspend fun close() {
        closed = true
    }

    override suspend fun write(fragment: ByteArray) {
        val msg = rx.feed(fragment) ?: return
        val m = Codec.decode(msg)
        val h = m.header
        val readOnly = h.type in setOf(MsgType.PING, MsgType.DESCRIBE, MsgType.GET, MsgType.IDENTIFY, MsgType.NET_STATUS, MsgType.PAIR_CODE)
        if (!readOnly && !unlocked) return err(h, ErrorCode.FORBIDDEN, "enter the code")
        when (val b = m.body) {
            is PairCodeReq -> if (b.code == code) { unlocked = true; ok(h, PairCodeResp()) } else err(h, ErrorCode.FORBIDDEN, "wrong code")
            is PingReq -> ok(h, PingResp().apply { timeMs = b.timeMs })
            is DescribeReq -> ok(h, DescribeResp().apply { points.addAll(this@FakeDevice.points) })
            is GetReq -> ok(h, GetResp().apply { values.forEach { (k, v) -> samples.add(Sample().apply { point = k; value = v }) } })
            is SetReq -> if (b.point == 1) err(h, ErrorCode.FORBIDDEN, "read-only") else {
                values[b.point!!] = b.value!!; ok(h, SetResp().apply { value = b.value })
            }
            is CfgGetReq -> ok(h, CfgGetResp().apply { config = this@FakeDevice.config })
            is CfgSetReq -> {
                val c = b.config!!
                c.name?.let { config.name = it }
                c.wifiSsid?.let { config.wifiSsid = it }
                c.net?.let { config.net = it }
                c.serverHost?.let { config.serverHost = it }
                ok(h, CfgSetResp())
            }
            is WifiScanReq -> ok(h, WifiScanResp().apply {
                networks.add(WifiNet().apply { ssid = "Weak"; rssi = -85; auth = WifiAuth.WPA2 })
                networks.add(WifiNet().apply { ssid = "Home"; rssi = -45; auth = WifiAuth.WPA2 })
            })
            is NetStatusReq -> ok(h, NetStatusResp().apply { status = NetStatus().apply { state = LinkState.NO_CONFIG } })
            is OtaBeginReq -> {
                if (b.model != Model.CO2_EGG) return err(h, ErrorCode.OTA_WRONG_MODEL, "wrong model")
                val resume = otaImage != null && otaSha.contentEquals(b.sha256)
                if (!resume) {
                    otaImage = java.io.ByteArrayOutputStream(); otaSha = b.sha256
                }
                ok(h, OtaBeginResp().apply { chunk = 1024; resumeFrom = otaImage!!.size().toLong() })
            }
            is OtaDataReq -> {
                val img = otaImage ?: return err(h, ErrorCode.BAD_REQUEST, "no OTA")
                if (b.offset != img.size().toLong()) return err(h, ErrorCode.BAD_REQUEST, "expected ${img.size()}")
                if (failAfterChunks >= 0 && chunks++ >= failAfterChunks) {
                    failAfterChunks = -1
                    return err(h, ErrorCode.STORAGE, "simulated flash error")
                }
                img.write(b.data!!)
                ok(h, OtaDataResp().apply { nextOffset = img.size().toLong() })
            }
            is OtaAbortReq -> ok(h, OtaAbortResp())  // keeps the partial image so that the next begin resumes
            is OtaEndReq -> {
                val img = otaImage!!.toByteArray()
                if (!MessageDigest.getInstance("SHA-256").digest(img).contentEquals(otaSha)) return err(h, ErrorCode.OTA_HASH_MISMATCH, "sha")
                flashed = img
                otaImage = null
                ok(h, OtaEndResp())
            }
            else -> err(h, ErrorCode.UNSUPPORTED, "unsupported")
        }
    }

    private suspend fun send(bytes: ByteArray) {
        for (f in BleFragments.split(bytes, mtu - 3)) out.emit(f)
    }

    private suspend fun ok(h: Header, body: ProtoMessage) = send(Codec.encode(body, h.reqId))

    private suspend fun err(h: Header, code: Int, text: String) =
        send(Codec.encode(h.type, Flags.RESP or Flags.ERR, h.reqId, ErrorBody().apply { this.code = code; this.text = text }))
}
