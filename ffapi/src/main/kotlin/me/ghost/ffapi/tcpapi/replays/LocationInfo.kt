package me.ghost.ffapi.tcpapi.replays

/**
 * Current print-head X/Y/Z coordinates parsed from an `~M114` reply. Ported from the TS
 * `LocationInfo`. Coordinates are kept as strings (matching the TS surface); they may be negative
 * or fractional.
 */
class LocationInfo {
    var x: String = ""
    var y: String = ""
    var z: String = ""

    fun fromReplay(replay: String?): LocationInfo? {
        if (replay == null) return null
        return try {
            val lines = replay.replace("\r\n", "\n").split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val positionLine = lines.firstOrNull { Regex("""\bX:\s*-?\d""").containsMatchIn(it) }
                ?: return null

            val xMatch = Regex("""X:\s*(-?\d+\.?\d*)""", RegexOption.IGNORE_CASE).find(positionLine)
            val yMatch = Regex("""Y:\s*(-?\d+\.?\d*)""", RegexOption.IGNORE_CASE).find(positionLine)
            val zMatch = Regex("""Z:\s*(-?\d+\.?\d*)""", RegexOption.IGNORE_CASE).find(positionLine)
            if (xMatch == null || yMatch == null || zMatch == null) return null

            x = xMatch.groupValues[1]
            y = yMatch.groupValues[1]
            z = zMatch.groupValues[1]
            this
        } catch (_: Exception) {
            null
        }
    }

    override fun toString(): String = "X: $x Y: $y Z: $z"
}
