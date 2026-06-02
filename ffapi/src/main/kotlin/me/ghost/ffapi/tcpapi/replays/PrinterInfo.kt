package me.ghost.ffapi.tcpapi.replays

/**
 * Printer info parsed from an `~M115` reply (model, firmware, serial, dimensions, MAC). Ported from
 * the TS `PrinterInfo`. The line-prefix matching is resilient to blank lines and minor formatting
 * differences across firmware (e.g. an Adventurer 3C Pro inserts a blank line between Machine Name
 * and Firmware).
 */
class PrinterInfo {
    var typeName: String = ""
    var name: String = ""
    var firmwareVersion: String = ""
    var serialNumber: String = ""
    var dimensions: String = ""
    var macAddress: String = ""
    var toolCount: String = ""

    /** Parses [replay]; returns this populated instance, or null if Machine Type/Firmware missing. */
    fun fromReplay(replay: String?): PrinterInfo? {
        if (replay.isNullOrEmpty()) return null

        return try {
            val lines = replay.split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "ok" }

            for (line in lines) {
                when {
                    line.startsWith("Machine Type:") ->
                        typeName = line.removePrefix("Machine Type:").trim()
                    line.startsWith("Machine Name:") ->
                        name = line.removePrefix("Machine Name:").trim()
                    line.startsWith("Firmware:") ->
                        firmwareVersion = line.removePrefix("Firmware:").trim()
                    line.startsWith("SN:") || line.startsWith("Serial Number:") ->
                        serialNumber = line.replaceFirst(Regex("^(SN|Serial Number):"), "").trim()
                    line.startsWith("Tool Count:") || line.startsWith("Tool count:") ->
                        toolCount = line.substringAfter(":").trim()
                    line.startsWith("Mac Address:") ->
                        macAddress = line.removePrefix("Mac Address:").trim()
                    else -> {
                        if (Regex("""X:\s*\d+\s+Y:\s*\d+\s+Z:\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                            dimensions = line
                        }
                    }
                }
            }

            if (typeName.isEmpty()) return null
            if (firmwareVersion.isEmpty()) return null
            this
        } catch (_: Exception) {
            null
        }
    }
}
