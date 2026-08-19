package me.ghost.ffapi

import me.ghost.ffapi.models.FFPrinterDetail

/**
 * FlashForge printer models the library supports. Modern printers (5M family) are identified by the
 * firmware-stable [FFPrinterDetail.pid] from HTTP `/detail`; legacy printers (Adventurer 3/4) by the
 * `Machine Type:` string in the TCP `~M115` reply. Name is only a last-resort fallback (users rename
 * machines). Ported from the app's `PrinterModel`.
 */
enum class PrinterModel {
    ADVENTURER_5M,
    ADVENTURER_5M_PRO,
    AD5X,
    CREATOR_5,
    CREATOR_5_PRO,
    ADVENTURER_3,
    ADVENTURER_4,
    GENERIC_LEGACY,
    UNKNOWN;

    /** Whether this model uses the modern HTTP REST API (port 8898) as its primary transport. */
    val isModern: Boolean
        get() = this == ADVENTURER_5M || this == ADVENTURER_5M_PRO || this == AD5X ||
            this == CREATOR_5 || this == CREATOR_5_PRO

    /**
     * Whether this is a Creator 5 series printer (Creator 5 or Creator 5 Pro). Mirrors the TS
     * `client.isCreator5` flag used to gate the HTTP-only temperature transport and palette snap.
     * These printers are HTTP-only (no usable TCP control channel) and drive their tools via the
     * `temperatureCtl_cmd` `nozzles[]` array.
     */
    val isCreator5: Boolean
        get() = this == CREATOR_5 || this == CREATOR_5_PRO

    companion object {
        const val PID_5M = 35
        const val PID_5M_PRO = 36
        const val PID_AD5X = 38
        const val PID_CREATOR_5 = 40
        const val PID_CREATOR_5_PRO = 41

        /** Resolves the model from `/detail`, pid-first with a name/capability fallback. */
        fun fromDetail(detail: FFPrinterDetail): PrinterModel {
            when (detail.pid) {
                PID_5M -> return ADVENTURER_5M
                PID_5M_PRO -> return ADVENTURER_5M_PRO
                PID_AD5X -> return AD5X
                PID_CREATOR_5 -> return CREATOR_5
                PID_CREATOR_5_PRO -> return CREATOR_5_PRO
            }
            val hasStation = detail.hasMatlStation == true ||
                (detail.matlStationInfo?.slotCnt ?: 0) > 0 ||
                (detail.matlStationInfo?.slotInfos?.isNotEmpty() == true)
            val name = detail.name.orEmpty()
            return when {
                name.contains("Creator 5 Pro", ignoreCase = true) -> CREATOR_5_PRO
                name.contains("Creator 5", ignoreCase = true) -> CREATOR_5
                hasStation || name.equals("AD5X", ignoreCase = true) ||
                    name.contains("5X", ignoreCase = true) -> AD5X
                name.contains("Pro", ignoreCase = true) -> ADVENTURER_5M_PRO
                name.contains("5M", ignoreCase = true) -> ADVENTURER_5M
                else -> UNKNOWN
            }
        }

        /** Resolves the model from a TCP `~M115` `Machine Type:` value (legacy fallback). */
        fun fromMachineType(machineType: String): PrinterModel {
            val upper = machineType.uppercase()
            return when {
                upper.contains("ADVENTURER 3") || upper.contains("ADVENTURER III") -> ADVENTURER_3
                upper.contains("ADVENTURER 4") -> ADVENTURER_4
                else -> GENERIC_LEGACY
            }
        }
    }
}

/**
 * What a connected printer can actually do, resolved at connect time from [PrinterModel] plus the
 * `/product` capability flags. Consumers gate UI on these.
 *
 * @property ledViaHttp `true` to drive the LED over HTTP `lightControl_cmd` (factory LEDs); `false`
 *   to drive it over TCP `~M146` (custom LEDs).
 * @property chamberTempControl heated-chamber temperature control is available (Creator 5 series);
 *   the chamber setpoint is driven over HTTP — see
 *   [me.ghost.ffapi.backend.PrinterBackend.setChamberTemp].
 */
data class PrinterCapabilities(
    val model: PrinterModel = PrinterModel.UNKNOWN,
    val ledControl: Boolean = false,
    val ledViaHttp: Boolean = false,
    val filtrationControl: Boolean = false,
    val hasMaterialStation: Boolean = false,
    val chamberTempControl: Boolean = false,
)
