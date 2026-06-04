# Parity with `ff-5mp-api-ts`

This Kotlin library is a 1:1 port of the TypeScript reference. This page tracks **intentional
divergences** — places where idiomatic Kotlin/Android or hard-won app lessons led us away from a
literal translation.

## Error handling

- **`Result<T>` + typed exceptions** instead of the TS bare `Boolean` / `Promise` returns. On
  failure the `Result` carries a `FlashForgeException` subtype: `AuthException` (rejected
  credentials), `PrinterUnreachableException` (network), `NotSupportedException` (capability not
  present), `ApiErrorException(code)` (non-zero API envelope), `ProtocolException` (unparseable
  reply). Callers branch on the cause without string-matching.

## Transport

- **Single `FlashForgeHttpApi` transport** shared by all HTTP operations (DRY), instead of the TS
  pattern of inlining `axios` in each control module.
- **TCP uses a persistent read-loop coroutine client** (ported from the verified app implementation:
  `Mutex`-serialized exchange, `KeepAliveMode`, exponential-backoff reconnect, `StateFlow`
  telemetry) rather than the TS one-shot per-command listener model.
- **`GCodeController` is bound to `FlashForgeClient`** rather than the TS generic
  `GCodeClientCapabilities` interface.

## Models

- **`/detail` numeric fields are `Float?`** (TS `number`). The firmware serializes numbers
  inconsistently (decimals vs ints) so they must be nullable floating-point, never `Int`. `Float`
  (not `Double`) is the deliberate unification target across the library and the consuming app — its
  Compose UI is `Float`-native, so this eliminates all `Float`/`Double` conversions at the boundary;
  printer telemetry needs nothing near double precision. Conceptually-integer fields like `printLayer`
  / `nozzleCnt` stay `Float?` too (firmware has been observed appending `.0` to whole values). Only
  `pid` is `Int?`. (`FFMachineInfo` + `Temperature` are likewise `Float`.)
- **`PrintStatus.getPrintPercent()` returns `Int?` (null)** when layer total is 0, instead of the TS
  `NaN`.
- Property names are idiomatic Kotlin (`typeName`, `isAD5X`, …) vs the TS PascalCase.

## Higher-level surface

- **The per-model `PrinterBackend` strategy tier is lifted into this library.** It does not exist in
  `ff-5mp-api-ts` (it came from FlashForgeUI-Electron / the Android app). It is the recommended
  high-level API; `FlashForgeHttpApi` + `FlashForgeClient` are the lower-level pieces it composes.
- **`PrinterConfig`** replaces the app's Room `PrinterEntity` as the per-printer input.

## Discovery

- Single `com.android.library` module; the UDP work is pure JVM and the Android `MulticastLock` is
  acquired only when a `Context` is supplied (`discover(context)` on Android, `discover(null)` /
  omitted off-device). The empty broadcast payload is deliberate (verified on hardware).

## Thumbnails

- `getThumbnail()` returns the PNG `ByteArray?` directly (TS returned a `ThumbnailInfo` wrapper). The
  `ThumbnailInfo` parser is retained for the TCP string-response path; the TS `saveToFile` (Node
  `fs`) is dropped — Android consumers handle the bytes.

## Not yet ported (TODO)

- `FiveMClient` composition root + the `Control` / `Info` / `Files` / `JobControl` / `TempControl`
  module split. The `PrinterBackend` tier + `FlashForgeHttpApi` currently cover the same surface.
- File **upload** (HTTP multipart `/uploadGcode` with firmware-version headers, and TCP M28/M29).
- Dedicated `FlashForgeA3Client` / `FlashForgeA4Client` subclasses (legacy quirks currently live in
  `FlashForgeTcpClient` + `GenericLegacyBackend`).
- Camera-stream detection probe (`detectCameraStream`).
