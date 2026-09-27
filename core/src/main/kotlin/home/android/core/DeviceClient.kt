package home.android.core

import home.protocol.BleFragments
import home.protocol.BleReassembler
import home.protocol.CfgGetReq
import home.protocol.CfgGetResp
import home.protocol.CfgSetReq
import home.protocol.Codec
import home.protocol.DescribeReq
import home.protocol.DescribeResp
import home.protocol.DeviceConfig
import home.protocol.ErrorBody
import home.protocol.ErrorCode
import home.protocol.FactoryResetReq
import home.protocol.GetReq
import home.protocol.GetResp
import home.protocol.HelloReq
import home.protocol.IdentifyReq
import home.protocol.InvokeReq
import home.protocol.InvokeResp
import home.protocol.Message
import home.protocol.NetStatus
import home.protocol.NetStatusReq
import home.protocol.NetStatusResp
import home.protocol.OtaAbortReq
import home.protocol.OtaBeginReq
import home.protocol.OtaBeginResp
import home.protocol.OtaConfirmReq
import home.protocol.OtaDataReq
import home.protocol.OtaDataResp
import home.protocol.OtaEndReq
import home.protocol.PairCodeReq
import home.protocol.PingReq
import home.protocol.PointDef
import home.protocol.Proto
import home.protocol.ProtoBody
import home.protocol.ProtoException
import home.protocol.ProtoMessage
import home.protocol.RebootReq
import home.protocol.SetReq
import home.protocol.SetResp
import home.protocol.Value
import home.protocol.WifiNet
import home.protocol.WifiScanReq
import home.protocol.WifiScanResp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The device answered with an error (codes: [ErrorCode]). */
class DeviceError(val code: Int, message: String) : Exception(message) {
    val isForbidden get() = code == ErrorCode.FORBIDDEN
}

data class OtaProgress(val sent: Int, val total: Int, val done: Boolean = false) {
    val percent get() = if (total == 0) 0 else (sent.toLong() * 100 / total).toInt()
}

/**
 * Protocol client over BLE: request/response by req_id, fragmentation to the MTU, typed operations.
 * All calls are suspend functions and safe to use from several coroutines.
 */
class DeviceClient(private val link: BleLink, scope: CoroutineScope) {
    private val rx = BleReassembler()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Message>>()
    private val nextId = AtomicInteger(0)
    private val writeLock = Mutex()
    // UNDISPATCHED: subscribe to notifications before the first request can be answered.
    private val reader: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        link.notifications.collect { frag -> rx.feed(frag)?.let(::onMessage) }
    }

    private fun onMessage(bytes: ByteArray) {
        val m = try {
            Codec.decode(bytes)
        } catch (e: ProtoException) {
            return
        }
        if (m.header.isResponse) pending.remove(m.header.reqId)?.complete(m)
    }

    private suspend fun send(msg: ByteArray) = writeLock.withLock {
        for (f in BleFragments.split(msg, maxOf(link.mtu - 3, 20))) link.write(f)
    }

    /** Sends a request and waits for the answer; throws [DeviceError] on an error answer. */
    suspend fun request(req: ProtoMessage, timeoutMs: Long = 10_000): ProtoBody = send(req, timeoutMs).await()

    /** A request that was sent; the answer is awaited later (lets OTA keep several chunks in flight). */
    private inner class Sent(private val id: Int, private val answer: CompletableDeferred<Message>, private val timeoutMs: Long) {
        suspend fun await(): ProtoBody {
            try {
                val m = withTimeout(timeoutMs) { answer.await() }
                if (m.header.isError) {
                    val e = m.body as ErrorBody
                    val code = e.code ?: ErrorCode.INTERNAL
                    throw DeviceError(code, e.text ?: ErrorCode.name(code))
                }
                return m.body
            } finally {
                pending.remove(id)
            }
        }

        fun forget() {
            pending.remove(id)
        }
    }

    private suspend fun send(req: ProtoMessage, timeoutMs: Long): Sent {
        var id: Int
        val d = CompletableDeferred<Message>()
        do {
            id = nextId.incrementAndGet() and 0xFFFF
        } while (id == 0 || pending.putIfAbsent(id, d) != null)
        try {
            send(Codec.encode(req, id))
        } catch (e: Exception) {
            pending.remove(id)
            throw e
        }
        return Sent(id, d, timeoutMs)
    }

    private suspend inline fun <reified T : ProtoBody> call(req: ProtoMessage, timeoutMs: Long = 10_000): T =
        request(req, timeoutMs) as? T ?: throw DeviceError(ErrorCode.BAD_REQUEST, "unexpected answer")

    // ------------------------------------------------------------------ operations

    /** Model, firmware, id, name — readable without the pairing code. */
    suspend fun info(): HelloReq = HelloReq.read(link.readInfo())

    suspend fun ping() = request(PingReq().apply { timeMs = System.currentTimeMillis() })

    /** Unlocks setup commands with the 4-digit code shown on the device screen. */
    suspend fun pair(code: Int) {
        request(PairCodeReq().apply { this.code = code })
    }

    suspend fun describe(): List<PointDef> = call<DescribeResp>(DescribeReq()).points

    suspend fun values(points: List<Int> = emptyList()): Map<Int, Value> =
        call<GetResp>(GetReq().apply { this.points.addAll(points) }).samples
            .filter { it.point != null && it.value != null }
            .associate { it.point!! to it.value!! }

    suspend fun set(point: Int, value: Value): Value =
        call<SetResp>(SetReq().apply { this.point = point; this.value = value }).value ?: value

    suspend fun invoke(point: Int, arg: Float? = null): String? =
        call<InvokeResp>(InvokeReq().apply { this.point = point; this.arg = arg }, 30_000).text

    suspend fun config(): DeviceConfig = call<CfgGetResp>(CfgGetReq()).config ?: DeviceConfig()

    /** Applies settings. If Wi-Fi, IP or server settings change, the device reboots right after the answer. */
    suspend fun setConfig(config: DeviceConfig) {
        request(CfgSetReq().apply { this.config = config })
    }

    suspend fun scanWifi(): List<WifiNet> = call<WifiScanResp>(WifiScanReq(), 20_000).networks.sortedByDescending { it.rssi ?: -100 }

    suspend fun netStatus(): NetStatus = call<NetStatusResp>(NetStatusReq()).status ?: NetStatus()

    suspend fun identify(seconds: Int = 10) {
        request(IdentifyReq().apply { this.seconds = seconds })
    }

    suspend fun reboot() {
        request(RebootReq())
    }

    /** mode: ResetMode.SETTINGS / FIRMWARE / ALL. The device restarts. */
    suspend fun factoryReset(mode: Int) {
        request(FactoryResetReq().apply { this.mode = mode })
    }

    /** Marks a freshly flashed firmware as good (otherwise it rolls back after 5 minutes). */
    suspend fun confirmFirmware() {
        request(OtaConfirmReq())
    }

    /**
     * Uploads a firmware image (the device checks the model and SHA-256, then reboots into it).
     * Resumes an interrupted upload of the same image. [window] requests are kept in flight.
     */
    fun ota(image: ByteArray, model: Int?, version: String?, window: Int = 4): Flow<OtaProgress> = flow {
        val sha = MessageDigest.getInstance("SHA-256").digest(image)
        val begin = call<OtaBeginResp>(OtaBeginReq().apply {
            size = image.size.toLong()
            sha256 = sha
            this.version = version
            this.model = model
        }, 60_000)
        val chunk = (begin.chunk ?: Proto.OTA_CHUNK).coerceIn(256, 1400)
        var offset = (begin.resumeFrom ?: 0L).toInt()
        emit(OtaProgress(offset, image.size))
        try {
            val inflight = ArrayDeque<Pair<Int, Sent>>()
            while (offset < image.size || inflight.isNotEmpty()) {
                while (inflight.size < window && offset < image.size) {
                    val n = minOf(chunk, image.size - offset)
                    val req = OtaDataReq().apply { this.offset = offset.toLong(); data = image.copyOfRange(offset, offset + n) }
                    inflight.addLast(offset + n to send(req, 30_000))
                    offset += n
                }
                val (end, sent) = inflight.removeFirst()
                val resp = try {
                    sent.await() as OtaDataResp
                } catch (e: Exception) {
                    inflight.forEach { it.second.forget() }
                    throw e
                }
                if (resp.nextOffset != null && resp.nextOffset != end.toLong())
                    throw DeviceError(ErrorCode.BAD_REQUEST, "device expects offset ${resp.nextOffset}")
                emit(OtaProgress(end, image.size))
            }
            request(OtaEndReq(), 60_000)
            emit(OtaProgress(image.size, image.size, done = true))
        } catch (e: Exception) {
            runCatching { request(OtaAbortReq(), 5_000) }
            throw e
        }
    }

    suspend fun close() {
        reader.cancel()
        pending.values.forEach { it.cancel() }
        link.close()
    }
}
