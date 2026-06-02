package me.ghost.ffapi.models

/** A current/target temperature pair (Celsius) for a component like the extruder or bed. */
data class Temperature(
    val current: Double,
    val set: Double,
)

/**
 * Structured, user-friendly view of the printer's state, produced from [FFPrinterDetail] by
 * [MachineInfo.fromDetail]. Ported from the TS `FFMachineInfo`. Nullable fields ([pid],
 * [hasMatlStation], [coolingFanLeftSpeed], [matlStationInfo], [indepMatlInfo]) are left null when
 * the source `/detail` omitted them, preserving the TS "undefined" semantics.
 */
data class FFMachineInfo(
    val autoShutdown: Boolean,
    val autoShutdownTime: Double,
    val cameraStreamUrl: String,
    val chamberFanSpeed: Double,
    val coolingFanSpeed: Double,
    val coolingFanLeftSpeed: Double?,
    val cumulativeFilament: Double,
    val cumulativePrintTime: Double,
    val currentPrintSpeed: Double,
    val freeDiskSpace: String,
    val doorOpen: Boolean,
    val errorCode: String,
    val estLength: Double,
    val estWeight: Double,
    val estimatedTime: Double,
    val externalFanOn: Boolean,
    val internalFanOn: Boolean,
    val lightsOn: Boolean,
    val ipAddress: String,
    val macAddress: String,
    val fillAmount: Double,
    val firmwareVersion: String,
    val name: String,
    val pid: Int?,
    val isPro: Boolean,
    val isAD5X: Boolean,
    val nozzleSize: String,
    val printBed: Temperature,
    val extruder: Temperature,
    val printDuration: Double,
    val printFileName: String,
    val printFileThumbUrl: String,
    val currentPrintLayer: Int,
    val printProgress: Double,
    val printProgressInt: Int,
    val printSpeedAdjust: Double,
    val filamentType: String,
    val machineState: MachineState,
    val status: String,
    val totalPrintLayers: Int,
    val tvoc: Double,
    val zAxisCompensation: Double,
    val flashCloudRegisterCode: String,
    val polarCloudRegisterCode: String,
    val printEta: String,
    /** Estimated completion time as epoch milliseconds (TS used a `Date`). */
    val completionTimeMillis: Long,
    val formattedRunTime: String,
    val formattedTotalRunTime: String,
    val hasMatlStation: Boolean?,
    val matlStationInfo: MatlStationInfo?,
    val indepMatlInfo: IndepMatlInfo?,
)
