# CLAUDE.md — ff-5mp-api-kt

Guidance for Claude Code working in this repo.

## Git workflow

**Commit and push directly to `main`.** Do NOT create branches or PRs unless the user explicitly
asks. Commit at logical checkpoints to keep history organized, and push whenever convenient.
`local.properties` (the local Android SDK path) is gitignored and must never be committed.

## What this is

A **Kotlin/JVM (Android-first) port of the `ff-5mp-api-ts` TypeScript library** — a clean-room
client library for FlashForge 3D printers (Adventurer **5M / 5M Pro / AD5X** and **Creator 5 /
Creator 5 Pro**, plus legacy **Adventurer 3 / 4** over TCP). It speaks the FlashForge LAN wire
protocol:

- **HTTP REST** on port **8898** (modern 5M/5M Pro/AD5X; **Creator 5 / Creator 5 Pro are
  HTTP-only** — they have no usable legacy TCP/8899 control channel)
- **TCP G-code/M-code** on port **8899** (5M family + legacy; control-only for 5M, full polling
  for legacy). The Creator 5 family must not use this path.
- **MJPEG camera** on port **8080**
- **UDP discovery** (broadcast/multicast)
- Per-request auth via `serialNumber` + `checkCode` on the HTTP path

The goal is a **1:1 port of the TS library's public surface** (same client hierarchy, same
method names where idiomatic Kotlin allows), packaged as a standalone library so it can be
consumed by the Android app (and potentially other Kotlin/JVM projects) instead of each app
re-implementing the protocol.

## Why we're building it

The Android app (`flashforgeui-app`, see below) currently has its own in-tree copy of all the
protocol code under `api/` and `backend/`. That code grew organically and is tangled with the
app's UI/state concerns. Extracting a dedicated library:

- **Shrinks the app** and separates protocol concerns from UI/state.
- Gives a **single source of truth** for the wire protocol, maintained against the TS reference.
- Is **reusable** beyond this one app.

The end state: this library becomes the dependency, and the app **rips out** its own
`api/`/`backend/` packages and depends on this instead. (Scope/structure — separate Gradle
module in the app repo vs. its own repo/Maven artifact — this repo is the standalone-repo
approach.)

## Source-of-truth references (READ THESE — they are the ground truth)

All paths are siblings under `C:\Users\coper\Documents\GitHub\1flashforge_printers\`:

### 1. `ff-5mp-api-ts/` — the library to port (PRIMARY reference)

The TypeScript library we are porting **1:1**. Mirror its architecture and public API. Key files:

- `src/index.ts` — the complete public export surface. **Match this.**
- `src/FiveMClient.ts` — main client for modern printers; composes HTTP control modules + embeds
  a TCP client. Submodules: `control` (Control), `jobControl` (JobControl), `info` (Info),
  `files` (Files), `tempControl` (TempControl), `tcpClient` (FlashForgeClient).
- `src/api/controls/` — `Control.ts`, `Files.ts`, `Info.ts`, `JobControl.ts`, `TempControl.ts`
- `src/api/server/` — `Endpoints.ts` (HTTP endpoints), `Commands.ts` (HTTP command payload types)
- `src/api/network/` — `NetworkUtils.ts`, `FNetCode.ts`, `DiscoveryErrors.ts`
- `src/api/PrinterDiscovery.ts` — UDP discovery (276-byte modern + 140-byte legacy parsing)
- `src/api/filament/Filament.ts`, `src/api/misc/` (Temperature, ScientificNotationFloatConverter)
- `src/models/ff-models.ts` — raw API shapes incl. AD5X IFS types (`MatlStationInfo`, `SlotInfo`,
  `AD5XMaterialMapping`, `AD5X*JobParams`, etc.)
- `src/models/MachineInfo.ts` — `MachineInfo.fromDetail()`: transforms raw `FFPrinterDetail` into
  the structured `FFMachineInfo`. **Pid-first model detection** (35=5M, 36=5M Pro, 38=AD5X,
  40=Creator 5, 41=Creator 5 Pro via `KNOWN_HTTP_PIDS`); do NOT substring-match user-mutable
  `detail.name`.
- `src/tcpapi/` — `FlashForgeTcpClient.ts` (low-level socket + keep-alive), `FlashForgeClient.ts`
  (generic legacy), `FlashForgeA3Client.ts` / `FlashForgeA4Client.ts` (documented legacy clients),
  `client/GCodes.ts` (G-code definitions), `client/GCodeController.ts` / `A3GCodeController.ts`,
  `replays/` (TCP response parsers: `PrinterInfo`, `TempInfo`, `EndstopStatus`, `PrintStatus`,
  `LocationInfo`, `ThumbnailInfo` — each has `fromReplay(response)`).
- `CLAUDE.md`, `README.md`, `docs/` (`clients.md`, `modules.md`, `protocols.md`, `parity.md`,
  `MIGRATION_GUIDE.md`) — read these for intent and the public-API contract.
- Tests are co-located `*.test.ts` (Vitest). **Port the tests too** — they encode the parsing
  edge cases (firmware quirks) we must preserve.

### 2. `flashforge-api-docs/` — protocol documentation

Community-documented wire protocol. When the TS code is unclear, this explains *why*.

- `docs-wiki/` — the wiki markdown. Most relevant pages:
  - `HTTP-REST-API.md`, `TCP-Protocol.md`, `Discovery-Protocol.md`, `Authentication.md`
  - `G‐Code-Reference.md`, `M-Code-Reference.md`, `State-Machines.md`, `Error-Codes.md`
  - `Capability-Matrix.md`
  - Per-model: `Adventurer-5M-Series.md`, `Adventurer-5M-Pro-Features.md`, `AD5X.md`,
    `AD5X-IFS-Material-Station.md`, `AD5X-IFS-Serial-Protocol.md`, `Adventurer-3-Series.md`,
    `Adventurer-4-Series.md`
- `endpoints/` — captured API specs: `endpoints_5m_3.2.7.yaml`, `endpoints_ad5x_1.1.7.yaml`,
  `endpoints_ad5x_1.2.1.yaml`, `networkserver_commands_adventurer3.yaml`,
  `networkserver_commands_adventurer4.yaml`
- `ai_reference/` — additional reference material

### 3. `FlashForgeUI-Electron/` — the original desktop app

The Electron app the TS library was built for. Useful to see how the library is consumed in
practice and how the per-model backends are wired.

## The Android app this library is for: `flashforgeui-app`

Located at `C:\Users\coper\Documents\Prototyping\flashforgeui-app` — native Android (Kotlin +
Jetpack Compose) LAN monitor/control for FlashForge 5M / 5M Pro / AD5X. Package
`me.ghost.ffui`. This is the **primary consumer**; the library's API should make the app's needs
easy. Read its `CLAUDE.md` for full detail. What matters for this port:

### Code to extract / replace (the app's current in-tree protocol layer)

These app packages are what this library replaces. They are an existing Kotlin implementation of
the same protocol — useful as a **secondary reference** (they already solved Android/Kotlin-specific
issues), but the TS lib is the structural source of truth. Port the *shape* from TS; borrow
Kotlin/Android lessons from here.

- `app/src/main/java/me/ghost/ffui/api/`
  - `FlashForgeHttpApi.kt` — OkHttp + kotlinx.serialization; POST `/detail`, `/product`, `/control`
  - `FlashForgeTcpClient.kt` — raw Socket on 8899; M601 lock, synchronous
    `sendCommandWithResponse` (CompletableDeferred + Mutex), `KeepAliveMode`
    (MODERN/LEGACY_POLL/NONE), auto-reconnect w/ exponential backoff, M661 file list, M662 thumbnail
  - `PrinterModel.kt` — `PrinterModel` enum + pid-based detection + M115 fallback; `PrinterCapabilities`
  - `FlashForgeModels.kt` — `@Serializable` shapes; `/detail` carries `matlStationInfo` inline
  - `UdpDiscovery.kt` — UDP broadcast scan (`WifiManager.MulticastLock`). **The empty broadcast
    payload is deliberate — it works on real hardware; do not change it.**
- `app/src/main/java/me/ghost/ffui/backend/` — per-model strategy layer (mirrors the Electron
  backends): `PrinterBackend` (abstract), `DualApiBackend` (modern base, polls HTTP `/detail`),
  `Adventurer5MBackend` / `Adventurer5MProBackend` / `AD5XBackend` / `GenericLegacyBackend`
  (TCP polling via M105+M119+M27, M25/M24/M26 job control, M23+M24 start), `PrinterBackendFactory`.

> **NOT part of this library:** the app's `api/SpoolmanApi.kt` and `api/SpoolmanModels.kt` are a
> separate Spoolman-server integration, unrelated to the FlashForge protocol. Leave them in the app.

### Hard-won protocol lessons from the app (apply these in the port)

These are verified against live hardware (an AD5X on firmware 3.1.0) and the
`flashforge-emulator-v2` (headless A3). Preserve them:

- **Cleartext HTTP/TCP is required** — printers are plain HTTP/TCP, no TLS. (On Android this
  needs a network-security-config; that's the *app's* concern, but the library must not assume TLS.)
- **Firmware serializes numbers inconsistently** (decimals vs ints). Every numeric `/detail`
  field must be a nullable **`Float?`**, **not** Int — only `pid` is an Int. (`Float`, not `Double`:
  it's the unification target shared with the consuming app's `Float`-native Compose UI, so reads
  cross the boundary with no conversion. Don't reintroduce `Double` — see `docs/parity.md`.)
- **`/detail` is the single source of truth for modern printers** (status + IFS inline). TCP is
  control-only for modern (custom LEDs `~M146`, homing `~G28`). Only the legacy backend polls over TCP.
- **Model detection is pid-based** (35=5M, 36=5M Pro, 38=AD5X, 40=Creator 5, 41=Creator 5 Pro) on
  first `/detail`; legacy printers fall back to TCP `~M115` `Machine Type:` string. Never
  substring-match the user-mutable name.
- **Networking on IO dispatcher**; socket reads use `soTimeout = 10000`. Release the TCP lock
  (`~M602`) and close socket/reader/writer in teardown.
- **Temperature SET transport differs by family.** For 5M/5M Pro/AD5X, set temps over TCP G-code
  (M104/M140) — the HTTP `temperatureCtl_cmd` path is unverified for them, so prefer TCP. For the
  **Creator 5 family (HTTP-only)** there is no TCP path: use the verified HTTP
  `temperatureCtl_cmd` with the per-tool `nozzles[]` array (exactly 4 entries; use `0`, **not**
  `-100`, to turn a tool off — firmware ignores `-100` inside `nozzles[]`).
- **Legacy specifics** (emulator-verified): M119 (status/LED/current file), M105 (temps), M27
  (progress); job control M25/M24/M26; start M23+M24; file list M661 (A4 `::`-delimited vs A3
  `info_list.size:`); thumbnail M662 (A4 raw PNG vs A3 `0xa2a22a2a` magic header); LED control
  A4/Generic `~M146 r255...` (RGB) vs A3 `~M146 1/0` (on/off); A3 firmware uses `echo:`/`ack:`
  prefixes, IDLE status, `LEDStatus:`, `PrintFileName:`, fire-and-forget motion, M105 ok-prefix.
- **Creator 5 family is HTTP-only.** It exposes no usable legacy TCP/8899 control channel, so
  `Creator5Backend` fails fast (`NotSupportedException`) on TCP-only ops (`home()`, file listing)
  rather than hanging on a dead socket. Capability baselines: `hasMaterialStation=true`,
  `chamberTempControl=true` (heated chamber, firmware-capped at 80 °C); filtration control is
  forced on for the **Pro** only.
- **Creator 5 slot colors use a fixed 24-entry firmware palette.** The firmware renders a slot
  icon only on a byte-for-byte, case-sensitive match against this palette (unlike AD5X's freeform
  colors). Snap incoming colors via `Creator5Palette` using CIEDE2000 nearest-color in CIE L\*a\*b\*
  space; keep the `#` prefix for C5 (strip it for AD5X).
- **Creator 5 tool-changer / heated-chamber control** is model-specific: `setToolTemp(toolIndex,
  …)`, `setToolTemps(list)`, `cancelToolTemp(i)` (4-head tool changer) plus capability-gated
  `setChamberTemp(celsius)` / `cancelChamberTemp()`.
- **`/product` is unreliable for capability detection.** It reports filtration/TVOC/door flags
  correctly for the 5M Pro but returns **wrong** values for the Creator 5 Pro. Gate these
  capabilities on the **firmware pid (model identity)**, not on `/product`-derived client flags
  (e.g. surface filtration/TVOC/door only for `is_pro` OR `is_creator5_pro`).

## Porting approach (suggested — confirm structure before deep work)

1. **Gradle/packaging shape** — now a pure Kotlin/JVM library (module `ffapi`, coordinates
   `me.ghost:ff-5mp-api-kt`, currently **0.2.0**). Published **via `mavenLocal()` only** (no
   remote registry, no git tags); the consuming app pulls it with `./gradlew
   :ffapi:publishToMavenLocal`. Keeping the core pure Kotlin/JVM (no Android framework deps where
   avoidable — e.g. discovery's `MulticastLock` is Android-specific and may need an abstraction)
   maximizes reuse.
2. **Mirror the TS package layout** under `src/main/kotlin/` (or KMP `commonMain`): `client`,
   `api/controls`, `api/server`, `api/network`, `models`, `tcpapi`, `tcpapi/replays`.
3. **Port models + parsers first** (`ff-models` → data classes, `MachineInfo.fromDetail`,
   `replays/*`), then the TCP client, then the HTTP controls, then `FiveMClient` on top.
4. **Port the tests alongside** each unit — they encode firmware edge cases.
5. Use **kotlinx.serialization** (the app's existing convention) — `@Serializable`,
   `Json { ignoreUnknownKeys = true; explicitNulls = false }`. OkHttp for HTTP, raw `Socket` for TCP.

## Conventions

- Idiomatic Kotlin: `val` over `var`, strict nullability, data classes, coroutines for async
  (the TS lib is Promise-based — map to `suspend` funs).
- KDoc the public API.
- Keep the public surface aligned with `ff-5mp-api-ts/src/index.ts`; note any intentional
  divergences in a `docs/parity.md` (the TS lib has one to mirror).
