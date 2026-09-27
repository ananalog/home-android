package home.android.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ESP-IDF app image with the Home descriptor (same parsing as the server and tools/manifest.py). */
data class FirmwareImage(val version: String, val project: String, val model: Int, val hwRevMask: Int, val protoMajor: Int, val size: Int) {
    companion object {
        private const val APP_DESC = 32
        private const val HOME_DESC = APP_DESC + 256

        fun parse(image: ByteArray): FirmwareImage {
            require(image.size > HOME_DESC + 16) { "файл слишком маленький для прошивки ESP32" }
            require(image[0] == 0xE9.toByte()) { "это не образ приложения ESP32" }
            val b = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN)
            require(b.getInt(APP_DESC) == 0xABCD5432.toInt()) { "в образе нет esp_app_desc_t" }
            require(b.getInt(HOME_DESC) == 0x454D4F48) { "прошивка собрана не для Home (нет дескриптора)" }
            return FirmwareImage(
                version = cstr(image, APP_DESC + 16, 32),
                project = cstr(image, APP_DESC + 48, 32),
                model = b.getShort(HOME_DESC + 4).toInt() and 0xFFFF,
                hwRevMask = b.getShort(HOME_DESC + 6).toInt() and 0xFFFF,
                protoMajor = image[HOME_DESC + 8].toInt() and 0xFF,
                size = image.size,
            )
        }

        private fun cstr(b: ByteArray, off: Int, max: Int): String {
            var n = 0
            while (n < max && b[off + n] != 0.toByte()) n++
            return String(b, off, n, Charsets.UTF_8)
        }
    }
}
