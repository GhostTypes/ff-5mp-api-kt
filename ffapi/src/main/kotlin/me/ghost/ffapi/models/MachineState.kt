package me.ghost.ffapi.models

/**
 * Operational states of the printer, mapped from the raw `/detail` status string by
 * [MachineInfo.fromDetail].
 */
enum class MachineState {
    Ready,
    Busy,
    Calibrating,
    Error,
    Heating,
    Printing,
    Pausing,
    Paused,
    Cancelled,
    Completed,
    Unknown,
}
