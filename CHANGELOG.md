# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]
### Fixed
- Serialize HTTP command submission. The command POSTs (`/control`, `/product`, `/printGcode`) now run through a per-client FIFO mutex: commands execute one at a time in submission order, and a failed command does not block later ones. Read endpoints (`/detail`, `/gcodeList`, `/gcodeThumb`, camera) and file uploads stay off the mutex, so polling never waits behind a command and pause/stop never waits behind an upload.

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

[Unreleased]: https://github.com/GhostTypes/ff-5mp-api-kt/compare/b369382f79e340c65539e30343c5c72e8309b17b...HEAD
[0.3.0]: https://github.com/GhostTypes/ff-5mp-api-kt/compare/6f4fd2deab6c479894bc56ee928ae24d04db2e06...b369382f79e340c65539e30343c5c72e8309b17b
[0.2.0]: https://github.com/GhostTypes/ff-5mp-api-kt/compare/131b442f10afa828ef8bceae6ee646453a6654c9...6f4fd2deab6c479894bc56ee928ae24d04db2e06
[0.1.1]: https://github.com/GhostTypes/ff-5mp-api-kt/compare/02dde7f35360834304d280e3b2cab2e51604da48...131b442f10afa828ef8bceae6ee646453a6654c9
[0.1.0]: https://github.com/GhostTypes/ff-5mp-api-kt/compare/57db60b089634561345a1edf52583504def88cc8...02dde7f35360834304d280e3b2cab2e51604da48
