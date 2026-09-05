package me.ghost.ffapi.api

import kotlinx.coroutines.test.runTest
import me.ghost.ffapi.error.ApiErrorException
import me.ghost.ffapi.error.AuthException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the split of the firmware's `/detail` + `/product` envelope error codes
 * (corrected docs, 2026-09):
 *
 * - `code 1` ("SN is different" / "Access code is different") is the only auth failure →
 *   [AuthException].
 * - `code -1` ("Parameters is error") and `code -2` ("Lan mode error", the Creator 5 LAN-mode
 *   gate) are NOT credential failures → [ApiErrorException] with the firmware's own message.
 * - `/printGcode` codes `2` (busy) and `3` (file-not-exist) pass through with their firmware
 *   messages.
 *
 * Before this split every non-zero code on the credentialed reads surfaced as
 * [AuthException], so a printer in cloud-only mode read as "wrong check code".
 */
class AuthErrorSplitTest {

    /** Fake transport answering every request with the same envelope. */
    private class EnvelopeTransport(private val code: Int, private val message: String) {
        val interceptor = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("ok")
                .body(
                    """{"code":$code,"message":"$message"}""".toResponseBody(
                        "application/json".toMediaType()
                    )
                )
                .build()
        }
    }

    private fun api(code: Int, message: String) =
        FlashForgeHttpApi("printer", testInterceptor = EnvelopeTransport(code, message).interceptor)

    @Test
    fun `code 1 with an SN mismatch is an auth failure`() = runTest {
        val r = api(1, "SN is different").getDetail("SN", "CC")
        assertTrue(r.isFailure)
        val e = r.exceptionOrNull()
        assertTrue("expected AuthException, got $e", e is AuthException)
        assertEquals("SN is different", e!!.message)
    }

    @Test
    fun `code 1 with an access-code mismatch is an auth failure`() = runTest {
        val r = api(1, "Access code is different").getProduct("SN", "CC")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is AuthException)
    }

    @Test
    fun `code -1 parameters error is a typed API error, not an auth failure`() = runTest {
        val r = api(-1, "Parameters is error").getDetail("SN", "CC")
        assertTrue(r.isFailure)
        val e = r.exceptionOrNull()
        assertTrue("expected ApiErrorException, got $e", e is ApiErrorException)
        assertEquals(-1, (e as ApiErrorException).code)
        assertTrue(e.message!!.contains("Parameters is error"))
    }

    @Test
    fun `code -2 lan mode error is a typed API error, not an auth failure`() = runTest {
        // The Creator 5 LAN-mode gate: fixing it needs the printer's LCD, not new credentials.
        val r = api(-2, "Lan mode error").getDetail("SN", "CC")
        assertTrue(r.isFailure)
        val e = r.exceptionOrNull()
        assertTrue("expected ApiErrorException, got $e", e is ApiErrorException)
        assertEquals(-2, (e as ApiErrorException).code)
    }

    @Test
    fun `printGcode busy and file-not-exist keep their firmware messages`() = runTest {
        val busy = api(2, "Printer is Busy.")
            .printGcodeLegacy("SN", "CC", "job.gcode", leveling = false)
        assertTrue(busy.isFailure)
        val busyErr = busy.exceptionOrNull() as ApiErrorException
        assertEquals(2, busyErr.code)
        assertTrue(busyErr.message!!.contains("Printer is Busy."))

        val missing = api(3, "File does not exist.")
            .printGcodeLegacy("SN", "CC", "nope.gcode", leveling = false)
        assertTrue(missing.isFailure)
        val missingErr = missing.exceptionOrNull() as ApiErrorException
        assertEquals(3, missingErr.code)
        assertTrue(missingErr.message!!.contains("File does not exist."))
    }
}
