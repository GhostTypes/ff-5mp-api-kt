package me.ghost.ffapi.models

import kotlinx.serialization.Serializable

/**
 * Raw `/detail` response shape. Field names mirror the printer's native JSON keys (ported from the
 * TS `FFPrinterDetail`). For the modern 5M / 5M Pro / AD5X series this is the single source of truth
 * for status — including the material station, reported inline via [matlStationInfo] (NOT a separate
 * endpoint). Transformed into the structured [FFMachineInfo] by [MachineInfo.fromDetail].
 *
 * IMPORTANT — the firmware is **inconsistent** about numeric types: some numbers arrive as decimals
 * (`platTemp:27.75`, `estimatedTime:0.0`) and others as ints (`printDuration:0`). kotlinx
 * deserialization fails to parse a decimal literal into an `Int`, so every field that could arrive
 * fractional is typed `Double?` (`Double` rather than `Float` to preserve decimals like `210.3`
 * exactly). Only the genuinely-integer firmware id [pid] stays `Int?`.
 */
@Serializable
data class FFPrinterDetail(
    val autoShutdown: String? = null,
    val autoShutdownTime: Double? = null,
    val cameraStreamUrl: String? = null,
    val chamberFanSpeed: Double? = null,
    val chamberTargetTemp: Double? = null,
    val chamberTemp: Double? = null,
    val coolingFanSpeed: Double? = null,
    val coolingFanLeftSpeed: Double? = null,
    val cumulativeFilament: Double? = null,
    val cumulativePrintTime: Double? = null,
    val currentPrintSpeed: Double? = null,
    val doorStatus: String? = null,
    val errorCode: String? = null,
    val estimatedLeftLen: Double? = null,
    val estimatedLeftWeight: Double? = null,
    val estimatedRightLen: Double? = null,
    val estimatedRightWeight: Double? = null,
    val estimatedTime: Double? = null,
    val externalFanStatus: String? = null,
    val fillAmount: Double? = null,
    val firmwareVersion: String? = null,
    val flashRegisterCode: String? = null,
    val hasMatlStation: Boolean? = null,
    val matlStationInfo: MatlStationInfo? = null,
    val indepMatlInfo: IndepMatlInfo? = null,
    val hasLeftFilament: Boolean? = null,
    val hasRightFilament: Boolean? = null,
    val internalFanStatus: String? = null,
    val ipAddr: String? = null,
    val leftFilamentType: String? = null,
    val leftTargetTemp: Double? = null,
    val leftTemp: Double? = null,
    val lightStatus: String? = null,
    val location: String? = null,
    val macAddr: String? = null,
    val measure: String? = null,
    val name: String? = null,
    val nozzleCnt: Double? = null,
    val nozzleModel: String? = null,
    val nozzleStyle: Double? = null,
    val pid: Int? = null,
    val platTargetTemp: Double? = null,
    val platTemp: Double? = null,
    val polarRegisterCode: String? = null,
    val printDuration: Double? = null,
    val printFileName: String? = null,
    val printFileThumbUrl: String? = null,
    val printLayer: Double? = null,
    val printProgress: Double? = null,
    val printSpeedAdjust: Double? = null,
    val remainingDiskSpace: Double? = null,
    val rightFilamentType: String? = null,
    val rightTargetTemp: Double? = null,
    val rightTemp: Double? = null,
    val status: String? = null,
    val targetPrintLayer: Double? = null,
    val tvoc: Double? = null,
    val zAxisCompensation: Double? = null,
)
