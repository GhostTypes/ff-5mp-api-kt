package me.ghost.ffapi.api.server

/** HTTP API endpoint paths (port 8898). Ported from the TS `Endpoints`. */
object Endpoints {
    /** OEM MJPEG camera stream port. */
    const val CAMERA_STREAM_PORT = 8080
    const val CONTROL = "/control"
    const val DETAIL = "/detail"
    const val GCODE_LIST = "/gcodeList"
    const val GCODE_PRINT = "/printGcode"
    const val GCODE_THUMB = "/gcodeThumb"
    const val PRODUCT = "/product"
    const val UPLOAD_FILE = "/uploadGcode"
}
