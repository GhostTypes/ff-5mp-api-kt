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

        // Material Station presence is derived from the station data, not read off a single
        // field — see FFMachineInfo.hasMatlStation for the full rationale.
        val hasMaterialStation = detail.hasMatlStation == true ||
            (detail.matlStationInfo?.slotCnt ?: 0) > 0 ||
            (detail.matlStationInfo?.slotInfos?.size ?: 0) > 0

        val pid = detail.pid
        val isAD5X: Boolean
        val isPro: Boolean
        val isCreator5: Boolean
        val isCreator5Pro: Boolean
        if (pid != null && KNOWN_HTTP_PIDS.contains(pid)) {
            isAD5X = pid == PID_AD5X
            isPro = pid == PID_5M_PRO
            isCreator5 = pid == PID_CREATOR_5 || pid == PID_CREATOR_5_PRO
            isCreator5Pro = pid == PID_CREATOR_5_PRO
        } else {
            // Fallback for firmware that doesn't report pid: legacy name+capability heuristic.
            // Vulnerable to user renames, which is why pid-based detection is preferred.
            isAD5X = detail.name == "AD5X" || hasMaterialStation
            isPro = (detail.name ?: "").contains("Pro") && !isAD5X
            // `model` is the immutable factory name; prefer it over the user-mutable `name`
            // when distinguishing the Pro variant without a pid.
            val detailName = detail.name ?: ""
            val factoryName = detail.model ?: detailName
            isCreator5 = detailName.contains("Creator 5")
            isCreator5Pro = isCreator5 &&
                Regex("Creator 5 Pro", RegexOption.IGNORE_CASE).containsMatchIn(factoryName)
        }

        // Per-tool temperatures. Creator 5 series report `nozzleTemps[]` / `nozzleTargetTemps[]`;
        // single-nozzle models don't, so fall back to a 1-element array mirroring the main extruder.
        val toolTemps: List<Temperature> =
            if (!detail.nozzleTemps.isNullOrEmpty()) {
                detail.nozzleTemps!!.mapIndexed { i, t ->
                    Temperature(
                        current = if (t.isFinite() && t != 0f) t else 0f,
                        set = detail.nozzleTargetTemps?.getOrNull(i)
                            ?.let { if (it.isFinite() && it != 0f) it else 0f } ?: 0f,
                    )
                }
            } else {
                listOf(Temperature(current = detail.rightTemp ?: 0f, set = detail.rightTargetTemp ?: 0f))
            }

        // Capability flags — presence-derived, never assumed from the model family alone.
        // Only the Creator 5 Pro has a confirmed door sensor; elsewhere `doorStatus` is cosmetic.
        val hasCamera = detail.camera == 1 || !(detail.cameraStreamUrl ?: "").isEmpty()
        val hasLidar = detail.lidar == 1
        val hasDoorSensor = isCreator5Pro
        val modelName = detail.model ?: pid?.let { PID_MODEL_NAMES[it] } ?: detail.name ?: ""

        val estimatedTime = detail.estimatedTime ?: 0f
        val printProgress = detail.printProgress ?: 0f
        val printEta = formatTimeFromSeconds(estimatedTime)
        val completionTimeMillis = System.currentTimeMillis() + (estimatedTime * 1000).toLong()
        val formattedRunTime = formatTimeFromSeconds(detail.printDuration ?: 0f)

        val totalMinutes = (detail.cumulativePrintTime ?: 0f).toLong()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val formattedTotalRunTime = "${hours}h:${minutes}m"

        val totalJobFilamentMeters = (detail.estimatedRightLen ?: 0f) / 1000f
        val estLength = totalJobFilamentMeters * printProgress
        val estWeight = (detail.estimatedRightWeight ?: 0f) * printProgress

        return FFMachineInfo(
            autoShutdown = (detail.autoShutdown ?: "") == "open",
            autoShutdownTime = detail.autoShutdownTime ?: 0f,
            cameraStreamUrl = detail.cameraStreamUrl ?: "",
            chamberFanSpeed = detail.chamberFanSpeed ?: 0f,
            coolingFanSpeed = detail.coolingFanSpeed ?: 0f,
            coolingFanLeftSpeed = detail.coolingFanLeftSpeed, // null when absent
            cumulativeFilament = detail.cumulativeFilament ?: 0f,
            cumulativePrintTime = detail.cumulativePrintTime ?: 0f,
            currentPrintSpeed = detail.currentPrintSpeed ?: 0f,
            freeDiskSpace = String.format(Locale.US, "%.2f", detail.remainingDiskSpace ?: 0f),
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
            fillAmount = detail.fillAmount ?: 0f,
            firmwareVersion = detail.firmwareVersion ?: "",
            name = detail.name ?: "",
            pid = pid,
            isPro = isPro,
            isAD5X = isAD5X,
            isCreator5 = isCreator5,
            isCreator5Pro = isCreator5Pro,
            model = modelName,
            nozzleCount = detail.nozzleCnt?.takeIf { it != 0f }?.toInt() ?: toolTemps.size,
            hasCamera = hasCamera,
            hasLidar = hasLidar,
            hasDoorSensor = hasDoorSensor,
            nozzleSize = detail.nozzleModel ?: "",
            // The derived value, not the raw AD5X-only field — see FFMachineInfo.hasMatlStation.
            hasMatlStation = hasMaterialStation,
            matlStationInfo = detail.matlStationInfo,
            indepMatlInfo = detail.indepMatlInfo,
            printBed = Temperature(
                current = detail.platTemp ?: 0f,
                set = detail.platTargetTemp ?: 0f,
            ),
            chamber = Temperature(
                current = detail.chamberTemp ?: 0f,
                set = detail.chamberTargetTemp ?: 0f,
            ),
            toolTemps = toolTemps,
            extruder = Temperature(
                current = detail.rightTemp ?: 0f,
                set = detail.rightTargetTemp ?: 0f,
            ),
            printDuration = detail.printDuration ?: 0f,
            printFileName = detail.printFileName ?: "",
            printFileThumbUrl = detail.printFileThumbUrl ?: "",
            currentPrintLayer = (detail.printLayer ?: 0f).toInt(),
            printProgress = printProgress,
            printProgressInt = floor(printProgress * 100).toInt(),
            printSpeedAdjust = detail.printSpeedAdjust ?: 0f,
            filamentType = detail.rightFilamentType ?: "",
            machineState = getMachineState(detail.status ?: ""),
            status = detail.status ?: "",
            totalPrintLayers = (detail.targetPrintLayer ?: 0f).toInt(),
            tvoc = detail.tvoc ?: 0f,
            zAxisCompensation = detail.zAxisCompensation ?: 0f,
            flashCloudRegisterCode = detail.flashRegisterCode ?: "",
            polarCloudRegisterCode = detail.polarRegisterCode ?: "",
            printEta = printEta,
            completionTimeMillis = completionTimeMillis,
            formattedRunTime = formattedRunTime,
            formattedTotalRunTime = formattedTotalRunTime,
        )
    }

    /** Formats a duration in seconds as "HH:MM" (zero-padded). Returns "00:00" on bad input. */
    private fun formatTimeFromSeconds(seconds: Float): String {
        val valid = if (seconds.isFinite()) seconds else 0f
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
        const val PID_CREATOR_5 = 40
        const val PID_CREATOR_5_PRO = 41
        private val KNOWN_HTTP_PIDS =
            setOf(PID_5M, PID_5M_PRO, PID_AD5X, PID_CREATOR_5, PID_CREATOR_5_PRO)

        // Immutable model display names keyed by firmware pid. Fallback for `model` when the
        // printer doesn't report the `model` field (older firmware).
        private val PID_MODEL_NAMES = mapOf(
            PID_5M to "Adventurer 5M",
            PID_5M_PRO to "Adventurer 5M Pro",
            PID_AD5X to "AD5X",
            PID_CREATOR_5 to "Creator 5",
            PID_CREATOR_5_PRO to "Creator 5 Pro",
        )
    }
}
