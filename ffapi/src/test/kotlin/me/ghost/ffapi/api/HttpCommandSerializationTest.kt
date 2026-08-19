package me.ghost.ffapi.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import me.ghost.ffapi.error.ApiErrorException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer as OkBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proves the FIFO command serialization on the HTTP tier (`FlashForgeHttpApi.commandMutex`).
 *
 * The fake transport below answers every call from memory. It holds each call for a short
 * delay and counts the calls in flight. If two commands overlapped, that count would
 * exceed one. The class KDoc on `commandMutex` records the scope: commands serialize,
 * reads and uploads do not.
 */
class HttpCommandSerializationTest {

    /** Crashes the fake transport. An `Error` escapes `post()`'s typed `Result` wrapping. */
    private class TransportCrash : Error("simulated transport crash")

    /** How the fake transport answers call one. Later calls always answer OK. */
    private enum class FirstAnswer { OK, HTTP_500, CRASH }

    /**
     * Fake OkHttp transport built on the library's test-interceptor seam. It records each
     * request body, delays every call by [delayMs] (so overlap becomes measurable), and
     * answers call one with [firstAnswer]. [peak] tracks the highest number of calls that
     * ran at the same time.
     */
    private class FakeTransport(private val delayMs: Long, private val firstAnswer: FirstAnswer = FirstAnswer.OK) {
        private val calls = AtomicInteger()
        private val active = AtomicInteger()
        val peak = AtomicInteger()
        val bodies = mutableListOf<String>()
        private val gate = Any()

        val interceptor = Interceptor { chain ->
            val inFlight = active.incrementAndGet()
            peak.updateAndGet { highest -> maxOf(highest, inFlight) }
            try {
                Thread.sleep(delayMs)
                val isFirst = calls.incrementAndGet() == 1
                synchronized(gate) { bodies += bodyText(chain.request()) }
                when {
                    isFirst && firstAnswer == FirstAnswer.CRASH -> throw TransportCrash()
                    isFirst && firstAnswer == FirstAnswer.HTTP_500 ->
                        response(chain, 500, """{"code":500,"message":"server error"}""")
                    else -> response(chain, 200, """{"code":0,"message":"ok"}""")
                }
            } finally {
                active.decrementAndGet()
            }
        }

        private fun response(chain: Interceptor.Chain, code: Int, body: String) =
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("ok")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()

        private fun bodyText(request: Request): String {
            val buffer = OkBuffer()
            request.body?.writeTo(buffer)
            return buffer.readUtf8()
        }
    }

    // ---- Commands serialize: one at a time, in launch order ----

    @Test
    fun `two concurrent commands execute one at a time in launch order`() = runTest {
        val fake = FakeTransport(delayMs = 100)
        val api = FlashForgeHttpApi("printer", testInterceptor = fake.interceptor)

        val first = async { api.pauseJob("SN", "CC") }
        val second = async { api.controlLight("SN", "CC", on = true) }
        val results = awaitAll(first, second)

        assertTrue(results.all { it.isSuccess })
        assertEquals("never two command POSTs in flight", 1, fake.peak.get())
        assertEquals(2, fake.bodies.size)
        assertTrue("the first request must be the pause command", fake.bodies[0].contains("jobCtl_cmd"))
        assertTrue("the second request must be the light command", fake.bodies[1].contains("lightControl_cmd"))
    }

    // ---- A failing command must not block the next one ----

    @Test
    fun `a failing command releases the lock and the next command still runs`() = runTest {
        val fake = FakeTransport(delayMs = 50, firstAnswer = FirstAnswer.HTTP_500)
        val api = FlashForgeHttpApi("printer", testInterceptor = fake.interceptor)

        val failing = async { api.pauseJob("SN", "CC") }
        val following = async { api.resumeJob("SN", "CC") }
        val results = awaitAll(failing, following)

        assertTrue(results[0].isFailure)
        assertTrue(results[0].exceptionOrNull() is ApiErrorException)
        assertTrue(results[1].isSuccess)
        assertEquals("commands still serialize after a failure", 1, fake.peak.get())
    }

    @Test
    fun `a command that throws still leaves the lock free for the next command`() = runTest {
        // post() converts caught failures to Result.failure. A transport crash (an Error)
        // escapes that wrapping and propagates through withLock. The lock must still release.
        val fake = FakeTransport(delayMs = 50, firstAnswer = FirstAnswer.CRASH)
        val api = FlashForgeHttpApi("printer", testInterceptor = fake.interceptor)

        val crashed = runCatching { api.pauseJob("SN", "CC") }
        assertTrue(crashed.isFailure)

        val following = api.resumeJob("SN", "CC")
        assertTrue(following.isSuccess)
        assertEquals(2, fake.bodies.size)
    }

    // ---- Reads stay outside the lock ----

    @Test
    fun `a read overlaps a slow command instead of queueing behind it`() = runTest {
        val fake = FakeTransport(delayMs = 150)
        val api = FlashForgeHttpApi("printer", testInterceptor = fake.interceptor)

        val command = async { api.pauseJob("SN", "CC") }
        val read = async { api.getRecentFileList("SN", "CC") } // OK envelope parses as an empty list
        val results = awaitAll(command, read)

        assertTrue(results.all { it.isSuccess })
        assertEquals("the read must not wait for the command to finish", 2, fake.peak.get())
    }
}
