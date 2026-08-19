package me.ghost.ffapi.api.controls

/**
 * Constants and helpers for the FlashForge `temperatureCtl_cmd` HTTP temperature transport.
 *
 * The 5M / 5M Pro / AD5X set temperatures over TCP G-code (M104/M140). The HTTP-only Creator 5 /
 * 5 Pro have no usable TCP control channel, so temperature control goes through the HTTP
 * `temperatureCtl_cmd` instead — the [PrinterBackend][me.ghost.ffapi.backend.PrinterBackend] routes
 * between the two based on its [me.ghost.ffapi.backend.PrinterBackend.httpOnly] flag.
 *
 * Ported from the TS `TempControl` module's constants and `buildNozzleArray` helper.
 */
object TempControl {
    /**
     * Sentinel value for `temperatureCtl_cmd` meaning "leave this heater unchanged" (partial
     * update). Sending a real 0 / -100 would turn the heater off.
     */
    const val TEMP_NO_CHANGE = -200

    /**
     * `temperatureCtl_cmd` value that turns a SCALAR heater (platform / chamber / rightNozzle) off.
     */
    const val TEMP_OFF = -100

    /**
     * Value that turns a tool/nozzle OFF inside the `nozzles` array. Unlike the scalar heater
     * fields (which accept [TEMP_OFF] = -100), the Creator 5 firmware's per-nozzle parser only
     * treats a literal 0 as "off" — it ignores -100 in the `nozzles` array and the tool keeps
     * heating. (Observed on live hardware; this is the ff-5mp-api-ts v1.6.1 nozzle-off bugfix.)
     */
    const val NOZZLE_OFF = 0

    /**
     * Number of tool/nozzle entries the Creator 5 firmware requires in the `nozzles` array. The
     * firmware ignores the array unless its length is exactly this (verified in the firmware).
     */
    const val NOZZLE_COUNT = 4

    /**
     * Builds a [NOZZLE_COUNT]-length `nozzles` array of [TEMP_NO_CHANGE] placeholders with a single
     * tool set to [value]. Returns `null` if [toolIndex] is out of range (0..[NOZZLE_COUNT]-1).
     *
     * For a CANCEL pass [NOZZLE_OFF] (0) as the [value] — NOT [TEMP_OFF] (-100), which the firmware
     * ignores inside the `nozzles` array.
     */
    fun buildNozzleArray(toolIndex: Int, value: Int): List<Int>? {
        if (toolIndex !in 0 until NOZZLE_COUNT) return null
        return List(NOZZLE_COUNT) { idx -> if (idx == toolIndex) value else TEMP_NO_CHANGE }
    }
}
