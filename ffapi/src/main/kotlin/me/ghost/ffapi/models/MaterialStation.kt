package me.ghost.ffapi.models

import kotlinx.serialization.Serializable

/**
 * Information about a single slot in the AD5X material station (IFS). Mirrors the TS `SlotInfo`.
 * Reported inline on `/detail` via [MatlStationInfo.slotInfos].
 */
@Serializable
data class SlotInfo(
    val hasFilament: Boolean = false,
    /** Color hex string, e.g. "#FFFFFF". */
    val materialColor: String = "",
    /** Material name, e.g. "PLA". */
    val materialName: String = "",
    /** 1-based slot identifier. */
    val slotId: Int = 0,
)

/**
 * Detailed AD5X material-station state. Slot counts here come from a structured sub-object that the
 * firmware reports as genuine integers, so they stay [Int] (unlike the top-level `/detail` numerics).
 */
@Serializable
data class MatlStationInfo(
    /** Currently loading slot id (0 if none). */
    val currentLoadSlot: Int = 0,
    /** Currently active/printing slot id (0 if none). */
    val currentSlot: Int = 0,
    /** Total number of slots in the station. */
    val slotCnt: Int = 0,
    val slotInfos: List<SlotInfo> = emptyList(),
    /** Current action state of the material station. */
    val stateAction: Int = 0,
    /** Current step within the state action. */
    val stateStep: Int = 0,
)

/**
 * Independent material loading info (single-extruder-with-station flow). [materialName] may be "?"
 * when unknown.
 */
@Serializable
data class IndepMatlInfo(
    val materialColor: String = "",
    val materialName: String = "",
    val stateAction: Int = 0,
    val stateStep: Int = 0,
)
