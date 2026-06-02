package me.ghost.ffapi.models

import java.util.Locale
import kotlin.math.floor

/**
 * Transforms a raw [FFPrinterDetail] into the structured [FFMachineInfo]. Ported 1:1 from the TS
 * `MachineInfo`, including the **pid-first model detection** (firmware-stable [FFPrinterDetail.pid]
 * preferred over the user-mutable `name`) and the legacy name/capability fallback.
 */
class MachineInfo {

    /**
     * Converts [detail] to [FFMachineInfo], or null if [detail] is null. Mirrors the TS transform:
     * computes ETA/completion, formats run times, maps "open"/"close" strings to booleans, derives
     * estimated filament length/weight from progress, and maps the status string to [MachineState].
     */
    fun fromDetail(detail: FFPrinterDetail?): FFMachineInfo? {
        if (detail == null) return null

        val hasMaterialStation = detail.hasMatlStation == true ||
            (detail.matlStationInfo?.slotCnt ?: 0) > 0 ||
            (detail.matlStationInfo?.slotInfos?.size ?: 0) > 0

        val pid = detail.pid
        val isAD5X: Boolean
        val isPro: Boolean
        if (pid != null && KNOWN_HTTP_PIDS.contains(pid)) {
            isAD5X = pid == PID_AD5X
            isPro = pid == PID_5M_PRO
        } else {
            // Fallback for firmware that doesn't report pid: legacy name+capability heuristic.
            // Vulnerable to user renames, which is why pid-based detection is preferred.
            isAD5X = detail.name == "AD5X" || hasMaterialStation
            isPro = (detail.name ?: "").contains("Pro") && !isAD5X
        }

        val estimatedTime = detail.estimatedTime ?: 0.0
        val printProgress = detail.printProgress ?: 0.0
        val printEta = formatTimeFromSeconds(estimatedTime)
        val completionTimeMillis = System.currentTimeMillis() + (estimatedTime * 1000).toLong()
        val formattedRunTime = formatTimeFromSeconds(detail.printDuration ?: 0.0)

        val totalMinutes = (detail.cumulativePrintTime ?: 0.0).toLong()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val formattedTotalRunTime = "${hours}h:${minutes}m"

        val totalJobFilamentMeters = (detail.estimatedRightLen ?: 0.0) / 1000.0
        val estLength = totalJobFilamentMeters * printProgress
        val estWeight = (detail.estimatedRightWeight ?: 0.0) * printProgress

        return FFMachineInfo(
            autoShutdown = (detail.autoShutdown ?: "") == "open",
            autoShutdownTime = detail.autoShutdownTime ?: 0.0,
            cameraStreamUrl = detail.cameraStreamUrl ?: "",
            chamberFanSpeed = detail.chamberFanSpeed ?: 0.0,
            coolingFanSpeed = detail.coolingFanSpeed ?: 0.0,
            coolingFanLeftSpeed = detail.coolingFanLeftSpeed, // null when absent
            cumulativeFilament = detail.cumulativeFilament ?: 0.0,
            cumulativePrintTime = detail.cumulativePrintTime ?: 0.0,
            currentPrintSpeed = detail.currentPrintSpeed ?: 0.0,
            freeDiskSpace = String.format(Locale.US, "%.2f", detail.remainingDiskSpace ?: 0.0),
            doorOpen = (detail.doorStatus ?: "") == "open",
            errorCode = detail.errorCode ?: "",
            estLength = estLength,
            estWeight = estWeight,
            estimatedTime = estimatedTime,
            externalFanOn = (detail.externalFanStatus ?: "") == "open",
            internalFanOn = (detail.internalFanStatus ?: "") == "open",
            lightsOn = (detail.lightStatus ?: "") == "open",
            ipAddress = detail.ipAddr ?: "",
            macAddress = detail.macAddr ?: "",
            fillAmount = detail.fillAmount ?: 0.0,
            firmwareVersion = detail.firmwareVersion ?: "",
            name = detail.name ?: "",
            pid = pid,
            isPro = isPro,
            isAD5X = isAD5X,
            nozzleSize = detail.nozzleModel ?: "",
            hasMatlStation = detail.hasMatlStation,
            matlStationInfo = detail.matlStationInfo,
            indepMatlInfo = detail.indepMatlInfo,
            printBed = Temperature(
                current = detail.platTemp ?: 0.0,
                set = detail.platTargetTemp ?: 0.0,
            ),
            extruder = Temperature(
                current = detail.rightTemp ?: 0.0,
                set = detail.rightTargetTemp ?: 0.0,
            ),
            printDuration = detail.printDuration ?: 0.0,
            printFileName = detail.printFileName ?: "",
            printFileThumbUrl = detail.printFileThumbUrl ?: "",
            currentPrintLayer = (detail.printLayer ?: 0.0).toInt(),
            printProgress = printProgress,
            printProgressInt = floor(printProgress * 100).toInt(),
            printSpeedAdjust = detail.printSpeedAdjust ?: 0.0,
            filamentType = detail.rightFilamentType ?: "",
            machineState = getMachineState(detail.status ?: ""),
            status = detail.status ?: "",
            totalPrintLayers = (detail.targetPrintLayer ?: 0.0).toInt(),
            tvoc = detail.tvoc ?: 0.0,
            zAxisCompensation = detail.zAxisCompensation ?: 0.0,
            flashCloudRegisterCode = detail.flashRegisterCode ?: "",
            polarCloudRegisterCode = detail.polarRegisterCode ?: "",
            printEta = printEta,
            completionTimeMillis = completionTimeMillis,
            formattedRunTime = formattedRunTime,
            formattedTotalRunTime = formattedTotalRunTime,
        )
    }

    /** Formats a duration in seconds as "HH:MM" (zero-padded). Returns "00:00" on bad input. */
    private fun formatTimeFromSeconds(seconds: Double): String {
        val valid = if (seconds.isFinite()) seconds else 0.0
        val hours = floor(valid / 3600).toInt()
        val minutes = floor((valid % 3600) / 60).toInt()
        return "%02d:%02d".format(hours, minutes)
    }

    /** Maps a raw status string (case-insensitive) to [MachineState], defaulting to Unknown. */
    private fun getMachineState(status: String): MachineState = when (status.lowercase()) {
        "ready" -> MachineState.Ready
        "busy" -> MachineState.Busy
        "calibrate_doing" -> MachineState.Calibrating
        "error" -> MachineState.Error
        "heating" -> MachineState.Heating
        "printing" -> MachineState.Printing
        "pausing" -> MachineState.Pausing
        "paused" -> MachineState.Paused
        "cancel" -> MachineState.Cancelled
        "completed" -> MachineState.Completed
        else -> MachineState.Unknown
    }

    companion object {
        // Firmware-reported PIDs from /detail. Stable across user renames, unlike `name`.
        const val PID_5M = 35
        const val PID_5M_PRO = 36
        const val PID_AD5X = 38
        private val KNOWN_HTTP_PIDS = setOf(PID_5M, PID_5M_PRO, PID_AD5X)
    }
}
