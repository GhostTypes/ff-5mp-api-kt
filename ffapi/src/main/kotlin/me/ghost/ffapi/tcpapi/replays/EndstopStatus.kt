package me.ghost.ffapi.tcpapi.replays

/** Machine operational status parsed from an `~M119`-style report. */
enum class MachineStatus { BUILDING_FROM_SD, BUILDING_COMPLETED, PAUSED, READY, BUSY, DEFAULT }

/** Movement mode parsed from an `~M119`-style report. */
enum class MoveMode { MOVING, PAUSED, READY, WAIT_ON_TOOL, HOMING, DEFAULT }

/** Extracts a numeric value for [key] (e.g. "X-max:0"), or -1 if absent. */
private fun getValue(input: String, key: String): Int {
    val match = Regex("""${Regex.escape(key)}:\s*(\d+)""").find(input)
    return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
}

/** Endstop trigger states (0 = not triggered, 1 = triggered). */
class Endstop(data: String) {
    val xMax: Int = getValue(data, "X-max")
    val yMax: Int = getValue(data, "Y-max")
    val zMin: Int = getValue(data, "Z-min")
}

/** Additional status flags (S/L/J/F); meaning is firmware-specific. */
class Status(data: String) {
    val s: Int = getValue(data, "S")
    val l: Int = getValue(data, "L")
    val j: Int = getValue(data, "J")
    val f: Int = getValue(data, "F")
}

/**
 * Endstop + machine/move status parsed from an `~M119` reply. Ported from the TS `EndstopStatus`.
 * Tolerant of line ordering (matches by prefix) and of the legacy `LEDStatus:`/`PrintFileName:`
 * variants used by Adventurer 3 firmware.
 */
class EndstopStatus {
    var endstop: Endstop? = null
    var machineStatus: MachineStatus = MachineStatus.DEFAULT
    var moveMode: MoveMode = MoveMode.DEFAULT
    var status: Status? = null
    var ledEnabled: Boolean = false
    var filamentStatus: String? = null
    var currentFile: String? = null

    fun fromReplay(replay: String?): EndstopStatus? {
        if (replay.isNullOrEmpty()) return null

        return try {
            val lines = replay.replace("\r\n", "\n").split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val endstopLine = lines.firstOrNull { it.contains("X-max:") && it.contains("Y-max:") }
            val machineStatusLine = lines.firstOrNull { it.startsWith("MachineStatus:") }
            val moveModeLine = lines.firstOrNull { it.startsWith("MoveMode:") }
            val statusLine = lines.firstOrNull { it.startsWith("Status ") }
            val ledLine = lines.firstOrNull { it.startsWith("LED:") || it.startsWith("LEDStatus:") }
            val currentFileLine = lines.firstOrNull {
                it.startsWith("CurrentFile:") || it.startsWith("PrintFileName:")
            }
            val filamentLine = lines.firstOrNull { it.startsWith("FilamentStatus:") }

            if (endstopLine == null || machineStatusLine == null || moveModeLine == null) return null

            endstop = Endstop(endstopLine)

            val ms = machineStatusLine.removePrefix("MachineStatus:").trim()
            machineStatus = when {
                ms.contains("BUILDING_FROM_SD") -> MachineStatus.BUILDING_FROM_SD
                ms.contains("BUILDING_COMPLETED") -> MachineStatus.BUILDING_COMPLETED
                ms.contains("PRINTING") -> MachineStatus.BUILDING_FROM_SD
                ms.contains("PAUSED") -> MachineStatus.PAUSED
                ms.contains("READY") || ms.contains("IDLE") -> MachineStatus.READY
                ms.contains("BUSY") -> MachineStatus.BUSY
                else -> MachineStatus.DEFAULT
            }

            val mm = moveModeLine.removePrefix("MoveMode:").trim()
            moveMode = when {
                mm.contains("MOVING") -> MoveMode.MOVING
                mm.contains("PAUSED") -> MoveMode.PAUSED
                mm.contains("READY") || mm == "0" || mm == "0.0" -> MoveMode.READY
                mm.contains("WAIT_ON_TOOL") -> MoveMode.WAIT_ON_TOOL
                mm.contains("HOMING") -> MoveMode.HOMING
                else -> MoveMode.DEFAULT
            }

            status = statusLine?.let { Status(it) }
            filamentLine?.let { filamentStatus = it.removePrefix("FilamentStatus:").trim() }

            ledEnabled = when {
                ledLine?.startsWith("LEDStatus:") == true ->
                    ledLine.removePrefix("LEDStatus:").trim().lowercase() == "on"
                ledLine != null ->
                    ledLine.removePrefix("LED:").trim().toIntOrNull() == 1
                else -> false
            }

            val cf = currentFileLine
                ?.removePrefix("CurrentFile:")
                ?.removePrefix("PrintFileName:")
                ?.trim()
            currentFile = if (cf.isNullOrEmpty()) null else cf

            this
        } catch (_: Exception) {
            null
        }
    }

    fun isPrintComplete(): Boolean = machineStatus == MachineStatus.BUILDING_COMPLETED
    fun isPrinting(): Boolean = machineStatus == MachineStatus.BUILDING_FROM_SD
    fun isReady(): Boolean = moveMode == MoveMode.READY && machineStatus == MachineStatus.READY
    fun isPaused(): Boolean = machineStatus == MachineStatus.PAUSED || moveMode == MoveMode.PAUSED
}
