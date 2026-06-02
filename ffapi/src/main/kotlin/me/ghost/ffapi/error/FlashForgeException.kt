package me.ghost.ffapi.error

/**
 * Base type for all failures surfaced by this library. Methods return [Result] (see
 * `docs/parity.md` for the divergence from the TS lib's bare `Boolean` returns); on failure the
 * [Result] carries one of these so a caller can branch on the *cause* without string-matching.
 */
sealed class FlashForgeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The printer rejected the supplied `serialNumber` / `checkCode` (HTTP path) or `~M601` lock. */
class AuthException(
    message: String = "Printer rejected credentials (serial number / check code)"
) : FlashForgeException(message)

/** The printer could not be reached — offline, wrong IP, or a socket/timeout error. */
class PrinterUnreachableException(
    message: String = "Printer is unreachable",
    cause: Throwable? = null
) : FlashForgeException(message, cause)

/** The requested feature is not available on this model (e.g. filtration on a non-Pro). */
class NotSupportedException(feature: String) :
    FlashForgeException("$feature is not available on this printer")

/** A response arrived but could not be parsed into the expected shape. */
class ProtocolException(message: String, cause: Throwable? = null) :
    FlashForgeException(message, cause)

/**
 * The printer answered with a non-OK API envelope (`code != 0`). [code] is the firmware's response
 * code; see the TS `FNetCode` mapping.
 */
class ApiErrorException(val code: Int, detail: String) :
    FlashForgeException("Printer API error (code=$code): $detail")
