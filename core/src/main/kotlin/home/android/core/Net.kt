package home.android.core

/** IPv4 helpers: the protocol carries addresses as u32 whose little-endian bytes are a.b.c.d (lwIP order). */
object Net {
    fun parse(ip: String): Long {
        val parts = ip.trim().split('.')
        require(parts.size == 4) { "'$ip' — не IPv4-адрес" }
        var v = 0L
        parts.forEachIndexed { i, p ->
            val n = p.toIntOrNull() ?: throw IllegalArgumentException("'$ip' — не IPv4-адрес")
            require(n in 0..255) { "'$ip' — не IPv4-адрес" }
            v = v or (n.toLong() shl (8 * i))
        }
        return v
    }

    fun format(v: Long?): String? {
        if (v == null || v == 0L) return null
        return (0 until 4).joinToString(".") { ((v shr (8 * it)) and 0xFF).toString() }
    }

    fun maskFromPrefix(prefix: Int): Long {
        require(prefix in 0..32) { "префикс 0..32" }
        val hostOrder = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        // host order a.b.c.d → wire order (byte-reversed)
        return ((hostOrder shr 24) and 0xFF) or (((hostOrder shr 16) and 0xFF) shl 8) or
            (((hostOrder shr 8) and 0xFF) shl 16) or ((hostOrder and 0xFF) shl 24)
    }

    fun prefixFromMask(mask: Long): Int = java.lang.Long.bitCount(mask and 0xFFFFFFFFL)

    fun sameSubnet(a: Long, b: Long, mask: Long) = (a and mask) == (b and mask)
}

/** Parsed manufacturer data of the advertisement: company 0xFFFF, model u16, flags, protocol major. */
data class Advert(val model: Int, val configured: Boolean, val protoMajor: Int) {
    companion object {
        fun parse(mfg: ByteArray?): Advert? {
            // Android passes manufacturer data without the company id (it is the key of the sparse array).
            if (mfg == null || mfg.size < 4) return null
            val model = (mfg[0].toInt() and 0xFF) or ((mfg[1].toInt() and 0xFF) shl 8)
            return Advert(model, mfg[2].toInt() and 1 != 0, mfg[3].toInt() and 0xFF)
        }
    }
}
