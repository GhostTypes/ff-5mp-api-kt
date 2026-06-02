package me.ghost.ffapi.tcpapi.replays

import kotlin.math.roundToInt

/**
 * Temperature data for one component (extruder or bed): current and optional target. Ported from
 * the TS `TempData`. Accepts "current/set" (e.g. "210/210") or just "current" (idle), strips a
 * trailing "/0.0" emitted by some firmware, and rounds to the nearest integer.
 */
class TempData(data: String) {
    private val currentStr: String
    private val setStr: String?

    init {
        val cleaned = data.replace("/0.0", "") // some firmware appends a spurious "/0.0"
        if (cleaned.contains("/")) {
            val parts = cleaned.split("/")
            currentStr = parseTData(parts[0].trim())
            setStr = parseTData(parts[1].trim())
        } else {
            currentStr = parseTData(cleaned)
            setStr = null
        }
    }

    private fun parseTData(data: String): String {
        val v = if (data.contains(".")) data.substringBefore(".").trim() else data
        return (v.toDoubleOrNull()?.roundToInt() ?: 0).toString()
    }

    /** "current/set", or just "current" when no target is set. */
    fun getFull(): String = if (setStr == null) currentStr else "$currentStr/$setStr"

    fun getCurrent(): Int = currentStr.toIntOrNull() ?: 0

    fun getSet(): Int = setStr?.toIntOrNull() ?: 0
}

/**
 * Extruder + bed temperatures parsed from an `~M105` reply. Ported from the TS `TempInfo`. Handles
 * the "T0:" / "T:" / "T):" extruder-segment variants and defaults the bed to "0/0" when absent.
 */
class TempInfo {
    private var extruderTemp: TempData? = null
    private var bedTemp: TempData? = null

    fun fromReplay(replay: String?): TempInfo? {
        if (replay.isNullOrEmpty()) return null

        return try {
            val lines = replay.replace("\r\n", "\n").split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val temperatureLine = lines.firstOrNull {
                it.contains("T0:") || it.contains("T:") || it.contains("T):")
            } ?: return null

            var extruderDataStr: String? = null
            var bedDataStr: String? = null

            for (segment in temperatureLine.split(Regex("""\s+"""))) {
                when {
                    segment.startsWith("T0:") -> extruderDataStr = segment.removePrefix("T0:")
                    segment.startsWith("T):") -> extruderDataStr = segment.removePrefix("T):")
                    segment.startsWith("T:") -> extruderDataStr = segment.removePrefix("T:")
                    segment.startsWith("B:") -> bedDataStr = segment.removePrefix("B:")
                }
            }

            extruderTemp = extruderDataStr?.let { TempData(it) } ?: return null // extruder is critical
            bedTemp = if (bedDataStr != null) TempData(bedDataStr) else TempData("0/0")
            this
        } catch (_: Exception) {
            null
        }
    }

    fun getExtruderTemp(): TempData? = extruderTemp
    fun getBedTemp(): TempData? = bedTemp

    /** Bed <= 40C and extruder <= 200C (relative; 200C is still hot). */
    fun isCooled(): Boolean {
        val bed = bedTemp?.getCurrent() ?: 0
        val ext = extruderTemp?.getCurrent() ?: 0
        return bed <= 40 && ext <= 200
    }

    /** Within a generally safe range (extruder < 250C, bed < 100C). */
    fun areTempsSafe(): Boolean {
        val bed = bedTemp?.getCurrent() ?: 0
        val ext = extruderTemp?.getCurrent() ?: 0
        return ext < 250 && bed < 100
    }
}
