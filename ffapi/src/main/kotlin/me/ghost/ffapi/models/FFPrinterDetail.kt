package me.ghost.ffapi.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

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
    /**
     * Firmware model id, parsed leniently by [LenientPidSerializer]: 35=5M, 36=5M Pro, 38=AD5X,
     * 40=Creator 5, 41=Creator 5 Pro. The authoritative docs describe the wire value as a
     * HEX-ENCODED STRING (`"0023"` = 0x23 = 35), but some transports send a plain JSON number
     * (`35`) — both forms deserialize to the same `Int?`.
     */
    @Serializable(with = LenientPidSerializer::class)
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

/**
 * Lenient deserializer for the `/detail` `pid` field.
 *
 * The docs disagree with themselves about the wire type: the Key-Fields table and example show a
 * JSON number (`36`), while the authoritative per-model pages (and every endpoint YAML) describe
 * a HEX-ENCODED STRING (`"0023"` = 0x23 = 35 = Adventurer 5M, `"0026"` = 38 = AD5X,
 * `"0028"`/`"0029"` = Creator 5 / 5 Pro). A plain `Int?` mis-parses the string form as decimal
 * (`"0023"` → 23 = Guider 2), so both forms are accepted here:
 *
 * - JSON number (int, or a decimal literal like `35.0`) → parsed as an int.
 * - JSON string → leading zeros stripped, then parsed as HEX (base 16).
 *
 * Neither the TS nor the py client handles the string form (pydantic coerces `"0023"` to decimal
 * 23), so this is a deliberate docs-driven hardening; anything unparseable yields `null`, which
 * sends [MachineInfo] down its name/capability fallback.
 */
object LenientPidSerializer : KSerializer<Int?> {
    private val delegate = Int.serializer().nullable

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Int?) =
        encoder.encodeNullableSerializableValue(delegate, value)

    override fun deserialize(decoder: Decoder): Int? {
        val input = decoder as? JsonDecoder
            ?: return decoder.decodeNullableSerializableValue(delegate)
        val element = input.decodeJsonElement()
        return (element as? JsonPrimitive)?.let(::parsePidPrimitive)
    }

    /** JSON number → int; string → strip leading zeros, parse as hex. `null` otherwise. */
    internal fun parsePidPrimitive(element: JsonPrimitive): Int? {
        if (!element.isString) {
            return element.intOrNull ?: element.doubleOrNull?.toInt()
        }
        val hex = element.content.trim().trimStart('0')
        if (hex.isEmpty()) return 0
        return hex.toIntOrNull(16)
    }
}
