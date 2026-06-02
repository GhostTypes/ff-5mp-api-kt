package me.ghost.ffapi.tcpapi.client

/**
 * G-code/M-code command strings for FlashForge TCP communication (port 8899). Ported from the TS
 * `GCodes`. All commands are `~`-prefixed per the FlashForge wire protocol.
 */
object GCodes {
    /** Initiate a control session (login / acquire control lock). */
    const val CMD_LOGIN = "~M601 S1"
    /** Terminate a control session (logout / release control lock). */
    const val CMD_LOGOUT = "~M602"

    /** Emergency stop of all printer activity. */
    const val CMD_EMERGENCY_STOP = "~M112"

    /** Request current print job status. */
    const val CMD_PRINT_STATUS = "~M27"
    /** Request endstop status. */
    const val CMD_ENDSTOP_INFO = "~M119"
    /** Request general printer info (firmware, model, etc.). */
    const val CMD_INFO_STATUS = "~M115"
    /** Request current X/Y/Z/A/B coordinates. */
    const val CMD_INFO_XYZAB = "~M114"
    /** Request current temperatures (extruder, bed). */
    const val CMD_TEMP = "~M105"

    /** Turn LEDs full white. */
    const val CMD_LED_ON = "~M146 r255 g255 b255 F0"
    /** Turn LEDs off. */
    const val CMD_LED_OFF = "~M146 r0 g0 b0 F0"

    /** Enable filament runout sensor. */
    const val CMD_RUNOUT_SENSOR_ON = "~M405"
    /** Disable filament runout sensor. */
    const val CMD_RUNOUT_SENSOR_OFF = "~M406"

    /** List local files. */
    const val CMD_LIST_LOCAL_FILES = "~M661"
    /** Retrieve a file thumbnail (requires a file path argument). */
    const val CMD_GET_THUMBNAIL = "~M662"

    /** Take a picture with the camera, if equipped. */
    const val TAKE_PICTURE = "~M240"

    /** Home all axes (G28). */
    const val CMD_HOME_AXES = "~G28"

    /** Select a file for printing; `%%filename%%` is replaced with the file path. */
    const val CMD_START_PRINT = "~M23 0:/user/%%filename%%"
    /** Pause the current print job. */
    const val CMD_PAUSE_PRINT = "~M25"
    /** Resume a paused print job. */
    const val CMD_RESUME_PRINT = "~M24"
    /** Stop/cancel the current print job. */
    const val CMD_STOP_PRINT = "~M26"

    /** Set extruder temperature and wait (M109; requires S[temp]). */
    const val WAIT_FOR_HOTEND_TEMP = "~M109"
    /** Set bed temperature and wait (M190; requires S[temp] or R[temp] for cooling). */
    const val WAIT_FOR_BED_TEMP = "~M190"

    /** Prepare for file upload; `%%size%%` and `%%filename%%` are placeholders. */
    const val CMD_PREP_FILE_UPLOAD = "~M28 %%size%% 0:/user/%%filename%%"
    /** Indicate completion of file upload. */
    const val CMD_COMPLETE_FILE_UPLOAD = "~M29"
}
