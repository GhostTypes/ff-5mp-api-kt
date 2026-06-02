package me.ghost.ffapi.tcpapi.replays

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Print job progress parsed from an `~M27` reply: SD-card byte progress and layer progress. Ported
 * from the TS `PrintStatus`. When no "Layer:" line is present (Adventurer 3 reports percentage-like
 * progress without layer metadata), layer fields fall back to the SD byte values.
 */
class PrintStatus {
    var sdCurrent: String = ""
    var sdTotal: String = ""
    var layerCurrent: String = ""
    var layerTotal: String = ""

    fun fromReplay(replay: String?): PrintStatus? {
        if (replay == null) return null
        return try {
            val lines = replay.replace("\r\n", "\n").split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val sdLine = lines.firstOrNull { it.startsWith("SD printing byte") } ?: return null
            val sdMatch = Regex("""SD printing byte\s+(\d+)\s*/\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(sdLine) ?: return null
            sdCurrent = sdMatch.groupValues[1].trim()
            sdTotal = sdMatch.groupValues[2].trim()

            val layerLine = lines.firstOrNull { it.startsWith("Layer:") }
            if (layerLine == null) {
                // Adventurer 3 reports percentage-like progress without layer metadata.
                layerCurrent = sdCurrent
                layerTotal = sdTotal
                return this
            }

            val layerMatch = Regex("""Layer:\s*(\d+)\s*/\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(layerLine) ?: return null
            layerCurrent = layerMatch.groupValues[1].trim()
            layerTotal = layerMatch.groupValues[2].trim()
            this
        } catch (_: Exception) {
            null
        }
    }

    /** Layer-based percent (0-100), or null if layer data is unavailable/invalid. */
    fun getPrintPercent(): Int? {
        val cur = layerCurrent.toIntOrNull() ?: return null
        val total = layerTotal.toIntOrNull() ?: return null
        if (total == 0) return null
        val perc = (cur.toDouble() / total) * 100
        return min(100.0, max(0.0, perc)).roundToInt()
    }

    fun getLayerProgress(): String = "$layerCurrent/$layerTotal"
    fun getSdProgress(): String = "$sdCurrent/$sdTotal"
}
