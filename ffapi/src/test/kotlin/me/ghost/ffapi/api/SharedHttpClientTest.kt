package me.ghost.ffapi.api

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Verifies the shared-default HTTP client. Each [FlashForgeHttpApi] used to build a private
 * `OkHttpClient`, so a consumer holding one transport per printer session churned its own
 * connection pool + dispatcher per session. Instances now share one process-wide default; the
 * `httpClient` constructor parameter is the injection seam for callers that need their own config.
 */
class SharedHttpClientTest {

    @Test
    fun `two default instances share the same default client`() {
        val a = FlashForgeHttpApi("printer-a")
        val b = FlashForgeHttpApi("printer-b", 8898)

        assertSame(FlashForgeHttpApi.defaultClient, a.client)
        assertSame(FlashForgeHttpApi.defaultClient, b.client)
    }

    @Test
    fun `an injected client is used as-is`() {
        val custom = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()

        val api = FlashForgeHttpApi("printer", httpClient = custom)

        assertSame(custom, api.client)
        assertNotSame(FlashForgeHttpApi.defaultClient, api.client)
    }

    @Test
    fun `test interceptor derives from the shared default without forking its pools`() {
        val noop = Interceptor { chain -> chain.proceed(chain.request()) }

        val api = FlashForgeHttpApi("printer", testInterceptor = noop)

        // A derived client (it carries the interceptor), but the pool and dispatcher stay shared.
        assertNotSame(FlashForgeHttpApi.defaultClient, api.client)
        assertSame(FlashForgeHttpApi.defaultClient.connectionPool, api.client.connectionPool)
        assertSame(FlashForgeHttpApi.defaultClient.dispatcher, api.client.dispatcher)
    }

    @Test
    fun `shared default keeps the hardware-proven timeouts`() {
        val client = FlashForgeHttpApi.defaultClient

        assertEquals(3_000, client.connectTimeoutMillis)
        assertEquals(8_000, client.readTimeoutMillis)
        assertEquals(5_000, client.writeTimeoutMillis)
        assertEquals(12_000, client.callTimeoutMillis)
    }
}
