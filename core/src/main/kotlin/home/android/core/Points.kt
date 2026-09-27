package home.android.core

import home.protocol.PointDef
import home.protocol.PointFlags
import home.protocol.PointKind
import home.protocol.PointType
import home.protocol.Value

val PointDef.isAdvanced get() = (flags ?: 0) and PointFlags.ADVANCED != 0
val PointDef.isReadOnly get() = kind == PointKind.SENSOR || kind == PointKind.ACTION || (flags ?: 0) and PointFlags.READONLY != 0

private val labels = mapOf(
    "preheat" to "прогрев", "ok" to "норма", "sensor_error" to "ошибка датчика",
    "co2" to "CO2", "co2_graph" to "CO2 + график", "night" to "ночь", "off" to "выкл",
)

fun label(s: String) = labels[s] ?: s

/** Human-readable value with unit. */
fun PointDef.format(v: Value?): String {
    if (v == null) return "—"
    val s = when {
        type == PointType.ENUM && v.i != null -> options.getOrNull(v.i!!)?.let(::label) ?: v.i.toString()
        v.b != null -> if (v.b!!) "вкл" else "выкл"
        v.i != null -> v.i.toString()
        v.f != null -> v.f!!.let { if (it == Math.round(it).toFloat()) Math.round(it).toString() else "%.1f".format(it) }
        v.s != null -> label(v.s!!)
        else -> "—"
    }
    return if (unit.isNullOrEmpty()) s else "$s $unit"
}

fun value(b: Boolean) = Value().apply { this.b = b }
fun value(i: Int) = Value().apply { this.i = i }
fun value(f: Float) = Value().apply { this.f = f }
fun Value.number(): Float? = f ?: i?.toFloat() ?: b?.let { if (it) 1f else 0f }
