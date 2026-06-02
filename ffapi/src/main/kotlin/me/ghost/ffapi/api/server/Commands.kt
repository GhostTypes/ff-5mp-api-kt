package me.ghost.ffapi.api.server

/** `/control` command identifiers sent in the payload `cmd` field. Ported from the TS `Commands`. */
object Commands {
    const val LIGHT_CONTROL = "lightControl_cmd"
    const val PRINTER_CONTROL = "printerCtl_cmd"
    const val JOB_CONTROL = "jobCtl_cmd"
    const val CIRCULATION_CONTROL = "circulateCtl_cmd"
    const val CAMERA_CONTROL = "streamCtrl_cmd"
    const val TEMP_CONTROL = "temperatureCtl_cmd"
    const val STATE_CONTROL = "stateCtrl_cmd"
    const val MATERIAL_STATION_CONFIG = "msConfig_cmd"
    const val MATERIAL_STATION = "ms_cmd"
    const val RENAME = "reName_cmd"
    const val DELAY_CLOSE = "delayClose_cmd"
}
