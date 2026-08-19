package me.ghost.ffapi.models

import kotlinx.serialization.Serializable

/**
 * Raw `/detail` response shape. Field names mirror the printer's native JSON keys (ported from the
 * TS `FFPrinterDetail`). For the modern 5M / 5M Pro / AD5X series this is the single source of truth
 * for status — including the material station, reported inline via [matlStationInfo] (NOT a separate
 * endpoint). Transformed into the structured [FFMachineInfo] by [MachineInfo.fromDetail].
 *
 * IMPORTANT — the firmware is **inconsistent** about numeric types: some numbers arrive as decimals
 * (`platTemp:27.75`, `estimatedTime:0.0`, `platTargetTemp:60.0`) and others as ints
 * (`printDuration:0`, `printLayer:21`). kotlinx deserialization fails to parse a decimal literal into
 * an `Int`, so every field that could arrive fractional is typed `Float?`. `Float` (not `Double`) is
 * the deliberate unification target across the app and this library — printer telemetry needs nothing
 * near double precision, and the app's Compose UI is `Float`-native, so this eliminates all
 * `Float`/`Double` conversions at the boundary. Conceptually-integer fields like `printLayer` /
 * `nozzleCnt` stay `Float?` too (the firmware has been observed appending `.0` to whole values, so
 * typing them `Int?` would risk a deserialization crash). Only the genuinely-integer firmware id
 * [pid] stays `Int?`, along with the Creator 5 capability flags [camera] / [lidar] (0/1 booleans the
 * firmware reports as bare integers, matching the TS `=== 1` checks).
 */
@Serializable
data class FFPrinterDetail(
    val autoShutdown: String? = null,
    val autoShutdownTime: Float? = null,
    val camera: Int? = null,
    val cameraStreamUrl: String? = null,
    val chamberFanSpeed: Float? = null,
    val chamberTargetTemp: Float? = null,
    val chamberTemp: Float? = null,
    val coolingFanSpeed: Float? = null,
    val coolingFanLeftSpeed: Float? = null,
    val cumulativeFilament: Float? = null,
    val cumulativePrintTime: Float? = null,
    val currentPrintSpeed: Float? = null,
    val doorStatus: String? = null,
    val errorCode: String? = null,
    val estimatedLeftLen: Float? = null,
    val estimatedLeftWeight: Float? = null,
    val estimatedRightLen: Float? = null,
    val estimatedRightWeight: Float? = null,
    val estimatedTime: Float? = null,
    val externalFanStatus: String? = null,
    val fillAmount: Float? = null,
    val firmwareVersion: String? = null,
    val flashRegisterCode: String? = null,
    /**
     * AD5X-only: the Creator 5 series omits it entirely — null means "not reported", not "no
     * station". Gate on the derived [FFMachineInfo.hasMatlStation] instead.
     */
    val hasMatlStation: Boolean? = null,
    val matlStationInfo: MatlStationInfo? = null,
    val indepMatlInfo: IndepMatlInfo? = null,
    val hasLeftFilament: Boolean? = null,
    val hasRightFilament: Boolean? = null,
    val internalFanStatus: String? = null,
    val ipAddr: String? = null,
    val leftFilamentType: String? = null,
    val leftTargetTemp: Float? = null,
    val leftTemp: Float? = null,
    val lidar: Int? = null,
    val lightStatus: String? = null,
    val location: String? = null,
    val macAddr: String? = null,
    val measure: String? = null,
    val name: String? = null,
    /** Immutable factory model name (e.g. "Creator 5 Pro"); not user-editable. Creator 5 series. */
    val model: String? = null,
    val nozzleCnt: Float? = null,
    val nozzleModel: String? = null,
    val nozzleStyle: Float? = null,
    /** Per-tool target nozzle temps (one entry per nozzle). Creator 5 series multi-nozzle. */
    val nozzleTargetTemps: List<Float>? = null,
    /** Per-tool current nozzle temps (one entry per nozzle). Creator 5 series multi-nozzle. */
    val nozzleTemps: List<Float>? = null,
    val pid: Int? = null,
    val platTargetTemp: Float? = null,
    val platTemp: Float? = null,
    val polarRegisterCode: String? = null,
    val printDuration: Float? = null,
    val printFileName: String? = null,
    val printFileThumbUrl: String? = null,
    val printLayer: Float? = null,
    val printProgress: Float? = null,
    val printSpeedAdjust: Float? = null,
    val remainingDiskSpace: Float? = null,
    val rightFilamentType: String? = null,
    val rightTargetTemp: Float? = null,
    val rightTemp: Float? = null,
    val status: String? = null,
    val targetPrintLayer: Float? = null,
    val tvoc: Float? = null,
    val zAxisCompensation: Float? = null,
)
