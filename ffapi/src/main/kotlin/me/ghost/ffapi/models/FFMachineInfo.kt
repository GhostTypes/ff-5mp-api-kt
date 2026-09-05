package me.ghost.ffapi.models

/** A current/target temperature pair (Celsius) for a component like the extruder or bed. */
data class Temperature(
    val current: Float,
    val set: Float,
)

/**
 * Structured, user-friendly view of the printer's state, produced from [FFPrinterDetail] by
 * [MachineInfo.fromDetail]. Ported from the TS `FFMachineInfo`. Nullable fields ([pid],
 * [coolingFanLeftSpeed], [matlStationInfo], [indepMatlInfo]) are left null when the source
 * `/detail` omitted them, preserving the TS "undefined" semantics. Capability flags are the
 * exception: they are derived and non-null, never a passthrough of a field the firmware may
 * simply not send — see [hasMatlStation].
 */
data class FFMachineInfo(
    val autoShutdown: Boolean,
    val autoShutdownTime: Float,
    val cameraStreamUrl: String,
    val chamberFanSpeed: Float,
    val coolingFanSpeed: Float,
    val coolingFanLeftSpeed: Float?,
    val cumulativeFilament: Float,
    val cumulativePrintTime: Float,
    val currentPrintSpeed: Float,
    val freeDiskSpace: String,
    val doorOpen: Boolean,
    val errorCode: String,
    val estLength: Float,
    val estWeight: Float,
    val estimatedTime: Float,
    val externalFanOn: Boolean,
    val internalFanOn: Boolean,
    val lightsOn: Boolean,
    val ipAddress: String,
    val macAddress: String,
    val fillAmount: Float,
    val firmwareVersion: String,
    val name: String,
    /** Immutable factory model name (e.g. "Creator 5 Pro"); falls back to a pid-derived name, then [name]. */
    val model: String,
    val pid: Int?,
    val isPro: Boolean,
    val isAD5X: Boolean,
    /** Creator 5 / Creator 5 Pro (4-head tool-changer); false on every other model. */
    val isCreator5: Boolean,
    /** Specifically a Creator 5 Pro ([MachineInfo.PID_CREATOR_5_PRO]); drives the door-sensor + filtration capabilities. */
    val isCreator5Pro: Boolean,
    /** Tool count (`detail.nozzleCnt`, or [toolTemps] size). Single-nozzle = 1, Creator 5 = 4. */
    val nozzleCount: Int,
    /** Whether the printer has a built-in camera (capability flag, not just stream availability). */
    val hasCamera: Boolean,
    /** Whether the printer has a lidar / first-layer scanner. */
    val hasLidar: Boolean,
    /** Whether the printer has a real door sensor (only Creator 5 Pro; elsewhere [doorOpen] is cosmetic). */
    val hasDoorSensor: Boolean,
    val nozzleSize: String,
    val printBed: Temperature,
    /** Heated-chamber temps (Creator 5 series; 0/0 on models without one). */
    val chamber: Temperature,
    /** Per-tool temps — one entry per nozzle; single-nozzle models mirror [extruder]. */
    val toolTemps: List<Temperature>,
    val extruder: Temperature,
    val printDuration: Float,
    val printFileName: String,
    val printFileThumbUrl: String,
    val currentPrintLayer: Int,
    val printProgress: Float,
    val printProgressInt: Int,
    val printSpeedAdjust: Float,
    val filamentType: String,
    val machineState: MachineState,
    val status: String,
    val totalPrintLayers: Int,
    val tvoc: Float,
    val zAxisCompensation: Float,
    val flashCloudRegisterCode: String,
    val polarCloudRegisterCode: String,
    val printEta: String,
    /**
     * Estimated completion time as epoch milliseconds, or null when the print is not advancing
     * (TS: `CompletionTime: Date | null`). The firmware only counts `estimatedTime` down while
     * `status == "printing"`; outside it the field freezes while the wall clock keeps moving, so
     * a timestamp derived on every poll would walk forward one minute per minute. Null therefore
     * means "no valid ETA", never "no ETA known" — [printEta], the remaining *duration*, stays
     * populated in every state.
     */
    val completionTimeMillis: Long?,
    val formattedRunTime: String,
    val formattedTotalRunTime: String,
    /**
     * Whether a Material Station is attached.
     *
     * Derived by [MachineInfo.fromDetail] from the station data, NOT copied from the raw
     * `hasMatlStation` field — that one is AD5X-only and the Creator 5 series never reports it,
     * station attached or not (verified on a Creator 5 Pro reporting four loaded slots with the
     * flag absent). Non-null on purpose: a capability has no unknown state, and offering one is
     * what let an unreported flag read as absent hardware. For the untouched firmware value, read
     * [FFPrinterDetail.hasMatlStation].
     */
    val hasMatlStation: Boolean,
    val matlStationInfo: MatlStationInfo?,
    val indepMatlInfo: IndepMatlInfo?,
)
