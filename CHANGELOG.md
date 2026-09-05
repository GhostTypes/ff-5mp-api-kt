# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [0.4.1] - 2026-09-05
### Fixed
- **Share one default `OkHttpClient` across all `FlashForgeHttpApi` instances** (connection pool + dispatcher) instead of building a private client per transport — a consumer with one transport per printer session no longer churns a pool per session. Timeouts are unchanged; a new optional `httpClient` constructor parameter injects a custom client.

## [0.4.0] - 2026-09-05
### Fixed
- **Serialize HTTP command submission.** The command POSTs (`/control`, `/product`, `/printGcode`) now run through a per-client FIFO mutex: commands execute one at a time in submission order, and a failed command does not block later ones. Read endpoints (`/detail`, `/gcodeList`, `/gcodeThumb`, camera) and file uploads stay off the mutex, so polling never waits behind a command and pause/stop never waits behind an upload. (Previously listed under Unreleased.)
- **Map `"pause"` to Paused and `"downloading"` to Busy.** The Creator 5 Pro (pid 41, firmware 1.9.4) reports `"pause"` — not the documented `"paused"` — exactly when it pauses itself on a detected clog, so the state read `Unknown` at the moment the user most needed to know why the print stopped. `"downloading"` (file transfer in progress) maps onto the existing Busy: a new enum member would be a breaking change for consumers that pin the enum to a fixed list. The fw-5.x-only strings (`cloud_slicing` / `sending` / `unzipping`) intentionally stay Unknown, matching the TS reference. Ported from ts `a0ddfab` / py `f6f0c96`.
- **Gate `completionTimeMillis` on an advancing print.** The firmware only counts `estimatedTime` down while `status == "printing"`; outside it the field freezes while the wall clock keeps moving, so a timestamp derived on every poll walked forward one minute per minute — a paused print appeared to recede forever. `FFMachineInfo.completionTimeMillis` is now null outside Printing (heating included: warmup does not advance the job either). `printEta`, the remaining *duration*, stays populated in every state. Ported from ts `a0ddfab` / py `0cb8c73`.
- **Snap AD5X slot colors to the firmware palette and keep the leading `#`.** `PrinterBackend.setSlotMaterial` used to strip the `#` and send freeform hex — the pre-1.8.0 TS behavior, disproven on real hardware: the AD5X, like the Creator 5, renders a slot icon only on a byte-for-byte, case-sensitive match against its own 24-entry palette sent as uppercase `#RRGGBB`, and the printer *stores* what it is sent, so a bare value poisoned `slotInfos[].materialColor` on every later read. New `Ad5xPalette` (its 24 colors and 14 materials differ from the Creator 5's: Blue `#45A8F9` vs `#4CAAF8`) with the CIEDE2000 snap shared via `PaletteSnap`; `ConfigureSlotWireFormatTest` now asserts the hardware-proven behavior. Ported from ts `8028a70`.
- **Normalize temperature sentinels.** The firmware reports "this sensor does not exist" with an out-of-band negative value (`-108` on a chamber-less Creator 5) instead of omitting the field; that used to flow straight into `FFMachineInfo` as a literal -108 °C reading. `MachineInfo.fromDetail` now maps values `<= -50` (`TEMP_SENTINEL_FLOOR`) to absent when building the structured temperatures. `FFPrinterDetail` stays untouched firmware truth. Ported from py `f14470b`.
- **Split auth failures from parameter and LAN-mode errors.** Every non-zero `/detail` / `/product` envelope code used to become `AuthException`. Per the corrected docs (2026-09): only `code 1` ("SN is different" / "Access code is different") is an auth failure; `-1` ("Parameters is error") and `-2` ("Lan mode error", the Creator 5 LAN-mode gate) are now `ApiErrorException`s carrying the firmware's own message, so a printer in cloud-only mode no longer reads as wrong stored credentials. `/printGcode` codes `2` (busy) / `3` (file-not-exist) were verified to already pass through with their firmware messages.
- **Harden the TCP connect and teardown lifecycle** (audit-A finding). The fire-and-forget `connect()` could leak a socket — and the `~M601` control lock it may hold — when the login handshake failed: the failure path closed the socket but leaked the reader/writer and never released the lock. All sockets now open with a bounded 10 s connect timeout (`CONNECT_TIMEOUT_MS`, matching `soTimeout`), a single mutex-guarded teardown swaps fields before closing so a manual `disconnect()` racing the connect-failure path (or a second `disconnect()`) can no longer double-close, and the `~M602` release + close runs on every exit path including connect failure. No public API, keep-alive, or reconnect behavior changed.

### Added
- `FFMachineInfo.hasChamberSensor` — true iff the printer actually reported a (non-sentinel) chamber temperature. The heated chamber is a Creator 5 series *option*, not a family trait; gate chamber entities on this, never on `isCreator5`. Ported from py's `has_chamber_sensor`.
- `LenientPidSerializer` for `FFPrinterDetail.pid`: a JSON number (`35`) and the documented hex-encoded string (`"0023"` = 0x23 = 35) both deserialize to the same `Int?`. Neither the TS nor the py client handles the string form (pydantic mis-coerces it to decimal 23), so this is a deliberate docs-driven hardening.
- `Ad5xPalette` (`AD5X_PALETTE`, `AD5X_MATERIALS`, `snapToAd5xPalette`) and the shared `PaletteSnap` perceptual-snapping machinery, ported 1:1 from ts `ad5xPalette.ts` / `paletteSnap.ts`.

### Changed
- **Breaking:** `FFMachineInfo.completionTimeMillis` is now `Long?` (was `Long`); null means "no valid ETA", never "unknown" — see the gate above. (TS: `CompletionTime: Date | null`. The consuming app reads no `completionTime` today.)
- **Breaking:** `Creator5Palette.Color` moved to the shared `PaletteSnap.PaletteColor` type (mirroring the TS `PaletteColor` shared interface); update imports.
- `/detail` and `/product` non-zero envelopes other than code 1 now surface as `ApiErrorException` instead of `AuthException` (see the auth split above).

## [0.3.0] - 2026-08-19
### Fixed
- **`FFMachineInfo.hasMatlStation` no longer misses the Material Station on the Creator 5 series.** It was a straight copy of the raw `hasMatlStation` value from `/detail`, which is an AD5X-only field — a Creator 5 Pro omits it entirely (verified on real hardware, pid 41, firmware 1.9.4) while reporting a fully populated `matlStationInfo` with four loaded slots. It therefore arrived `null`, and consumers gating on it saw no station on exactly the models that have one. `fromDetail` already computed the correct value for its own AD5X heuristic (flag `== true` OR `slotCnt > 0` OR non-empty `slotInfos`) and then discarded it; that derived value is now what the property exposes.

### Changed
- **`FFMachineInfo.hasMatlStation` is now a non-null `Boolean`** rather than `Boolean?`. A capability has no "unknown" state, and offering one is the mechanism of the bug above: firmware omits what does not apply, so an absent field reads as `null`, and `null` reads as "no". `FFPrinterDetail.hasMatlStation` keeps the untouched firmware value and stays nullable, because there the absence *is* the information.

### Docs
- Neutralize internal provenance phrasing in comments and KDoc — firmware versions and observed behavior stay, internal tooling references go
- Fix the chamber-control capability reference in `docs/parity.md` to `PrinterCapabilities.chamberTempControl`
- README: fix the ff-5mp-api-ts link, genericize internal app references, soften live-verification wording
- Extend `EndstopStatus` KDoc to cover `FilamentStatus` and both LED line variants; consolidate the `hasMatlStation` rationale into `FFMachineInfo`
- Update stale `httpOnly`/capability breadcrumbs in `PrinterBackend`/`PrinterModel` to describe current behavior

## [0.2.0] - 2026-06-28
### Added
- Add Creator 5 / Creator 5 Pro support as a new HTTP-only printer family:
  - `Creator5Backend` (extends `DualApiBackend`), routed via `PrinterBackendFactory`; HTTP-only, fails fast on TCP-only operations
  - `Creator5Palette`: fixed 24-entry firmware color palette with CIEDE2000 nearest-color snapping in CIE L*a*b* space (byte-for-byte firmware match required)
  - Heated-chamber control (`setChamberTemp`/`cancelChamberTemp`, capability-gated, firmware-capped at 80 °C) and 4-head tool-changer temperature API (`setToolTemp`/`setToolTemps`/`cancelToolTemp`)
  - HTTP temperature transport (`temperatureCtl_cmd` with per-tool `nozzles[]` array)
  - File upload (`uploadFile` for AD5X, `uploadFileCreator5` for Creator 5) and Creator 5-native print-start/upload endpoints
  - Firmware pids 40 (Creator 5) and 41 (Creator 5 Pro); `PrinterCapabilities.chamberTempControl`; `productId`-based discovery detection (`MODERN_PRODUCT_IDS`)
  - New `/detail` and `FFMachineInfo` fields: `model`, `camera`, `lidar`, `nozzleTemps`/`nozzleTargetTemps`, `nozzleCount`, `hasCamera`, `hasLidar`, `hasDoorSensor` (Creator 5 Pro), `chamber`, `toolTemps`
  - New `TempControl` constants/helpers and 7 new test files
### Changed
- Bump version to 0.2.0
- Document Creator 5 / Creator 5 Pro support in README and `docs/parity.md`; remove file upload from the not-yet-ported list (now implemented)

## [0.1.1] - 2026-06-03
### Changed
- Unify all telemetry numeric fields on `Float?` (from `Double?`) to match the consuming app's Float-native Compose UI and eliminate boundary conversions — `FFPrinterDetail`, `ApiWire`, `FFMachineInfo`, `Temperature`, `MachineInfo.fromDetail`, `GenericLegacyBackend`. `pid` stays `Int?`, structured ints stay `Int`, and conceptually-int fields stay `Float?` since firmware appends `.0`

## [0.1.0] - 2026-06-01
### Added
- Initial release: Kotlin/JVM port of `ff-5mp-api-ts` (module `:ffapi`, coordinates `me.ghost:ff-5mp-api-kt`)
- Gradle scaffold mirroring the app toolchain; maven-publish wired to `mavenLocal`
- Models with pid-first `MachineInfo` detection and error taxonomy
- TCP replay parsers (M105, M27, M114, M115, M119, M662)
- TCP client tier: `FlashForgeTcpClient` + `FlashForgeClient` + `GCodeController` (persistent read-loop coroutine transport, Mutex-serialized exchange, keep-alive modes, auto-reconnect, `Result<T>` with typed exceptions)
- HTTP transport `FlashForgeHttpApi` (OkHttp + kotlinx.serialization) with typed exceptions (`PrinterUnreachableException`, `AuthException`, `ApiErrorException`) and per-model backend strategy tier (`PrinterBackend`, `DualApiBackend`, `Adventurer5M`/`5MPro`/`AD5X`/`GenericLegacy` backends + factory)
- UDP discovery (pure-JVM core + optional `MulticastLock`), verified against live 5M Pro and AD5X hardware
- CLAUDE.md porting guide, README consumption guide, and `docs/parity.md`

[0.4.1]: https://github.com/GhostTypes/ff-5mp-api-kt/compare/14a9e5edab2ba42eb0fc8e6d07348ea9ed566461...HEAD
[0.4.0]: https://github.com/GhostTypes/ff-5mp-api-kt/compare/b369382f79e340c65539e30343c5c72e8309b17b...14a9e5edab2ba42eb0fc8e6d07348ea9ed566461
[0.3.0]: https://github.com/GhostTypes/ff-5mp-api-kt/compare/6f4fd2deab6c479894bc56ee928ae24d04db2e06...b369382f79e340c65539e30343c5c72e8309b17b
[0.2.0]: https://github.com/GhostTypes/ff-5mp-api-kt/compare/131b442f10afa828ef8bceae6ee646453a6654c9...6f4fd2deab6c479894bc56ee928ae24d04db2e06
[0.1.1]: https://github.com/GhostTypes/ff-5mp-api-kt/compare/02dde7f35360834304d280e3b2cab2e51604da48...131b442f10afa828ef8bceae6ee646453a6654c9
[0.1.0]: https://github.com/GhostTypes/ff-5mp-api-kt/compare/57db60b089634561345a1edf52583504def88cc8...02dde7f35360834304d280e3b2cab2e51604da48
