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
- **Auth failures are split from other envelope errors.** Per the corrected docs (2026-09), only
  envelope `code 1` ("SN is different" / "Access code is different") is an `AuthException`;
  `-1` ("Parameters is error") and `-2` ("Lan mode error", the Creator 5 LAN-mode gate) surface
  as `ApiErrorException` with the firmware's own message. The TS lib does not type these at all
  (it returns `null` and logs), so this split is a Kotlin-side improvement.

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
- **`FFPrinterDetail.pid` deserializes leniently** (`LenientPidSerializer`): the authoritative docs
  describe the wire value as a hex-encoded string (`"0023"` = 0x23 = 35 = 5M) while some transports
  send a plain JSON number (`35`). A JSON number parses as an int; a string is stripped of leading
  zeros and parsed as hex. The TS lib models `pid: number` only — this is a docs-driven hardening,
  not a TS divergence to mirror.
- **`FFMachineInfo.completionTimeMillis` is `Long?`** and null unless the print is advancing
  (`MachineState.Printing`) — mirrors the TS `CompletionTime: Date | null` gate. Outside Printing
  the firmware freezes `estimatedTime` while the wall clock moves, so any derived timestamp drifts;
  `printEta` (the remaining duration) stays populated. **Breaking for consumers** that assumed
  non-null (the current app reads no `completionTime`).
- **Temperature sentinels are normalized in `MachineInfo.fromDetail`** (values `<= -50`, e.g. the
  `-108` a chamber-less Creator 5 reports, read as absent) and `FFMachineInfo.hasChamberSensor` is
  derived from a real chamber reading. The TS lib passes sentinels through; this mirrors the py
  client (`TEMP_SENTINEL_FLOOR` / `has_chamber_sensor`) instead. `FFPrinterDetail` keeps the raw
  firmware values.
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

## Material-station palettes

Both the AD5X and the Creator 5 series render a slot color icon only on a byte-for-byte,
  case-sensitive match against their own 24-entry firmware palette, sent as uppercase `#RRGGBB`
 *with* the leading `#`. The palettes differ (Blue is `#45A8F9` on the AD5X, `#4CAAF8` on the
 Creator 5), and the CIEDE2000 nearest-color snap is shared: `PaletteSnap` mirrors the TS
 `paletteSnap.ts`, with `Ad5xPalette` / `Creator5Palette` delegating to it exactly as
 `ad5xPalette.ts` / `creator5Palette.ts` do. The palette entry type is the shared
 `PaletteSnap.PaletteColor` (the TS `PaletteColor` interface).

## Creator 5 family

The Creator 5 / Creator 5 Pro is HTTP-only (no legacy TCP/8899 service), driven by
`Creator5Backend`:

- **Fail-fast TCP overrides.** TCP-only operations (`home`, `listLocalFiles`, `slotAction`) throw
  `NotSupportedException` instead of the TS no-op-on-httpOnly pattern — surfaces misuse at the
  backend boundary rather than silently doing nothing.
- **Reuses `AD5XMaterialMapping`** as the shared material-mapping type (the TS
  `Creator5MaterialMapping` converged to the same 5-field shape in v1.6.0, so a separate type is
  unnecessary).
- **`setChamberTemp` is capability-gated** (`PrinterCapabilities.chamberTempControl`) rather than
  sent unconditionally — only the Creator 5 family has a chamber heater. (TS sends it
  unconditionally and relies on other models ignoring the field.)
- **`waitForPartCool()` is not ported** (TS no-ops it on httpOnly; no internal caller).

## Not yet ported (TODO)

- `FiveMClient` composition root + the `Control` / `Info` / `Files` / `JobControl` / `TempControl`
  module split. The `PrinterBackend` tier + `FlashForgeHttpApi` currently cover the same surface.
- TCP file transfer (M28/M29) for legacy printers. (HTTP multipart `/uploadGcode` upload **is**
  implemented for the AD5X and Creator 5.)
- Dedicated `FlashForgeA3Client` / `FlashForgeA4Client` subclasses (legacy quirks currently live in
  `FlashForgeTcpClient` + `GenericLegacyBackend`).
- Camera-stream detection probe (`detectCameraStream`).
