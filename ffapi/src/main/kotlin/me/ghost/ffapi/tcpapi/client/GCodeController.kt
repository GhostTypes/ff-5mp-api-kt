package me.ghost.ffapi.tcpapi.client

import kotlinx.coroutines.delay
import me.ghost.ffapi.tcpapi.FlashForgeClient

/**
 * Sends specific G-code commands to a printer over TCP, wrapping LED/job/homing/temperature
 * control. Ported from the TS `GCodeController`. Each method returns [Result]; multi-step sequences
 * short-circuit on the first failure.
 */
class GCodeController(private val client: FlashForgeClient) {

    suspend fun ledOn(): Result<Unit> = client.sendCmdOk(GCodes.CMD_LED_ON)
    suspend fun ledOff(): Result<Unit> = client.sendCmdOk(GCodes.CMD_LED_OFF)

    suspend fun pauseJob(): Result<Unit> = client.sendCmdOk(GCodes.CMD_PAUSE_PRINT)
    suspend fun resumeJob(): Result<Unit> = client.sendCmdOk(GCodes.CMD_RESUME_PRINT)
    suspend fun stopJob(): Result<Unit> = client.sendCmdOk(GCodes.CMD_STOP_PRINT)
    suspend fun startJob(filename: String): Result<Unit> =
        client.sendCmdOk(GCodes.CMD_START_PRINT.replace("%%filename%%", filename))

    suspend fun home(): Result<Unit> = client.sendCmdOk(GCodes.CMD_HOME_AXES, timeoutMs = 15_000)

    /** Absolute positioning, move to a safe position, then home. */
    suspend fun rapidHome(): Result<Unit> {
        client.sendCmdOk("~G90").onFailure { return Result.failure(it) }
        move(105.0, 105.0, 220.0, 9000).onFailure { return Result.failure(it) }
        return home()
    }

    suspend fun move(x: Double, y: Double, z: Double, feedrate: Int): Result<Unit> =
        client.sendCmdOk("~G1 X$x Y$y Z$z F$feedrate")

    suspend fun moveExtruder(x: Double, y: Double, feedrate: Int): Result<Unit> =
        client.sendCmdOk("~G1 X$x Y$y F$feedrate")

    suspend fun extrude(length: Double, feedrate: Int = 450): Result<Unit> =
        client.sendCmdOk("~G1 E$length F$feedrate")

    suspend fun setExtruderTemp(temp: Int, waitFor: Boolean = false): Result<Unit> {
        val ok = client.sendCmdOk("~M104 S$temp")
        if (!waitFor || ok.isFailure) return ok
        return waitForExtruderTemp(temp)
    }

    suspend fun setBedTemp(temp: Int, waitFor: Boolean = false): Result<Unit> {
        val ok = client.sendCmdOk("~M140 S$temp")
        if (!waitFor || ok.isFailure) return ok
        return waitForBedTemp(temp, cooling = false)
    }

    suspend fun cancelExtruderTemp(): Result<Unit> = client.sendCmdOk("~M104 S0")

    suspend fun cancelBedTemp(waitForCool: Boolean = false): Result<Unit> {
        val ok = client.sendCmdOk("~M140 S0")
        if (!waitForCool || ok.isFailure) return ok
        return waitForBedTemp(37, cooling = true)
    }

    /** Polls until the bed reaches [temp] (or cools to it), with a 30s timeout. */
    suspend fun waitForBedTemp(temp: Int, cooling: Boolean): Result<Unit> {
        if (cooling) client.sendCmdOk("${GCodes.WAIT_FOR_BED_TEMP}R$temp")
        else client.sendCmdOk("${GCodes.WAIT_FOR_BED_TEMP}S$temp")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 30_000) {
            val cur = client.getTempInfo().getOrNull()?.getBedTemp()?.getCurrent()
            if (cur == temp) return Result.success(Unit)
            delay(1000)
        }
        return Result.failure(IllegalStateException("waitForBedTemp(target $temp) timed out"))
    }

    /** Polls until the extruder reaches [temp], with a 30s timeout. */
    suspend fun waitForExtruderTemp(temp: Int): Result<Unit> {
        client.sendCmdOk("${GCodes.WAIT_FOR_HOTEND_TEMP}S$temp")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 30_000) {
            val cur = client.getTempInfo().getOrNull()?.getExtruderTemp()?.getCurrent()
            if (cur == temp) return Result.success(Unit)
            delay(1000)
        }
        return Result.failure(IllegalStateException("waitForExtruderTemp(target $temp) timed out"))
    }
}
