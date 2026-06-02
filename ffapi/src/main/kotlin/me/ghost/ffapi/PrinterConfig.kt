package me.ghost.ffapi

/**
 * Connection + identity for a single printer the library talks to. Replaces the app's
 * `PrinterEntity` (a Room DB row) with a transport-agnostic value the consumer fills in.
 *
 * @property customLedEnabled whether the user wired their own LEDs on a 5M / AD5X (driven over TCP
 *   `~M146`); acts as the LED-control baseline when the firmware doesn't expose `lightControl_cmd`.
 */
data class PrinterConfig(
    val ipAddress: String,
    val serialNumber: String,
    val checkCode: String,
    val name: String? = null,
    val firmwareVersion: String? = null,
    val customLedEnabled: Boolean = false,
)
