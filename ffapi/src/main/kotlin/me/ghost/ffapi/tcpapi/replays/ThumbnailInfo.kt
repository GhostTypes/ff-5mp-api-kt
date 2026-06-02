package me.ghost.ffapi.tcpapi.replays

import java.util.Base64

/**
 * PNG thumbnail extracted from an `~M662` reply. Ported from the TS `ThumbnailInfo`. The reply is a
 * mixed text+binary blob: an "ok" text delimiter followed by raw PNG bytes. We locate "ok", then
 * scan for the PNG signature (89 50 4E 47 0D 0A 1A 0A) to find the true image start.
 *
 * NOTE: [replay] must be decoded from the socket bytes as ISO-8859-1 (Latin-1) so the binary octets
 * round-trip losslessly — the same as the TS lib's `Buffer.from(str, 'binary')`. Java's `saveToFile`
 * from the TS version is dropped; callers on Android handle the [getImageBytes] directly.
 */
class ThumbnailInfo {
    private var imageData: ByteArray? = null
    private var fileName: String? = null

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }

    fun fromReplay(replay: String?, fileName: String): ThumbnailInfo? {
        if (replay.isNullOrEmpty()) return null

        return try {
            this.fileName = fileName

            val okIndex = replay.indexOf("ok")
            if (okIndex == -1) return null

            val rawBinary = replay.substring(okIndex + 2)
            val bytes = rawBinary.toByteArray(Charsets.ISO_8859_1)

            val pngStart = findPngSignature(bytes)
            if (pngStart < 0) return null

            imageData = bytes.copyOfRange(pngStart, bytes.size)
            this
        } catch (_: Exception) {
            null
        }
    }

    private fun findPngSignature(data: ByteArray): Int {
        if (data.size < PNG_SIGNATURE.size) return -1
        outer@ for (i in 0..(data.size - PNG_SIGNATURE.size)) {
            for (j in PNG_SIGNATURE.indices) {
                if (data[i + j] != PNG_SIGNATURE[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** Raw PNG bytes, or null if no image was parsed. */
    fun getImageBytes(): ByteArray? = imageData

    /** Base64-encoded PNG, or null. */
    fun getImageData(): String? = imageData?.let { Base64.getEncoder().encodeToString(it) }

    fun getFileName(): String? = fileName

    /** A `data:image/png;base64,...` URL, or null. */
    fun toBase64DataUrl(): String? = getImageData()?.let { "data:image/png;base64,$it" }
}
