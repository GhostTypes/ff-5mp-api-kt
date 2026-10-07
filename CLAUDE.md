# CLAUDE.md — ff-5mp-api-kt

Kotlin/JVM (Android-first) client library for FlashForge 3D printers, ported from
[`ff-5mp-api-ts`](https://github.com/GhostTypes/ff-5mp-api-ts). Single Gradle module `:ffapi`
(`com.android.library`), package `me.ghost.ffapi`, published as `me.ghost:ff-5mp-api-kt`.
Primary consumer: the [FlashForgeUI Android app](https://github.com/Parallel-7/FlashForgeUI-Android).

User docs: `README.md`. Intentional divergences from the TS lib: `docs/parity.md` (keep it current).
Release history: `CHANGELOG.md` (Keep a Changelog + SemVer).

## Git workflow

- **Commit and push straight to `main`** — sole maintainer; no branches or PRs unless asked.
- `local.properties` (SDK path) is gitignored and must never be committed.

## Build, test, release

Gradle wrapper 9.3.1 / AGP 9.1.1 on JDK 25. Library code targets Java 11.

```
./gradlew :ffapi:testDebugUnitTest      # unit tests (local JVM, no device)
./gradlew :ffapi:assembleRelease        # build the AAR
./gradlew :ffapi:publishToMavenLocal    # install me.ghost:ff-5mp-api-kt:<version> locally
```

**Releasing a version** (the app's CI depends on every step):
1. Bump `version` in the `publishing` block of `ffapi/build.gradle.kts` (and its comment).
2. Move `CHANGELOG.md` `[Unreleased]` entries under the new version; update README version refs.
3. Commit, tag **`v<version>`**, push the commit **and the tag**.
4. `publishToMavenLocal`, then bump the pin in the app's `app/build.gradle.kts`. The app's CI checks
   out this repo at `v<pinned version>`, so an untagged version breaks it.

## Scope and structure

```
me.ghost.ffapi
├── PrinterModel / PrinterConfig    pid → model detection; per-printer connection input
├── api/            FlashForgeHttpApi (OkHttp, 8898), PrinterDiscovery (UDP),
│                   controls/ (per-model HTTP helpers, palettes), server/ (endpoints, payloads)
├── backend/        PrinterBackend strategy tier — the recommended high-level API:
│                   DualApiBackend → 5M / 5M Pro / AD5X, Creator5Backend (HTTP-only),
│                   GenericLegacyBackend (TCP), PrinterBackendFactory
├── tcpapi/         FlashForgeTcpClient (socket, keep-alive, reconnect) → FlashForgeClient (typed
│                   commands), client/ (G-codes, GCodeController), replays/ (TCP response parsers)
├── models/         @Serializable wire shapes, MachineInfo.fromDetail → FFMachineInfo, MachineState
└── error/          FlashForgeException hierarchy (AuthException, ApiErrorException, …)
```

Only FlashForge protocol belongs here. App concerns (UI, persistence, Spoolman, NFC) stay in the app.

## Reference material

Siblings of this repo (`../`), all by the same maintainer:
- `ff-5mp-api-ts` — the reference implementation. Mirror its public surface (`src/index.ts`) and
  port its tests; firmware quirks live in them. Record any deliberate divergence in `docs/parity.md`.
- `ff-5mp-api-py` — Python port; source of some fixes (temp sentinels, `has_chamber_sensor`).
- `flashforge-api-docs` — protocol docs (`docs-wiki/`: HTTP, TCP, discovery, auth, error codes,
  per-model pages; `endpoints/`: captured specs). Explains *why* when the TS code is unclear.
- `FlashForgeUI-Electron` — desktop app; origin of the per-model backend tier.
- `flashforge-emulator-v2` — headless printer emulator (legacy A3/A4 + modern).

## Protocol rules (hardware-verified — preserve)

- **No TLS.** Printers speak plain HTTP (8898), TCP (8899), MJPEG (8080) and UDP discovery.
- **Firmware numbers are inconsistent** (`5` vs `5.0`): every numeric wire field is `Float?`, never
  `Int` or `Double` (the app's Compose UI is `Float`-native). Only `pid` is `Int?`, parsed leniently
  (JSON number or hex string `"0023"`).
- **Model detection is pid-first** (35 5M, 36 5M Pro, 38 AD5X, 40 Creator 5, 41 Creator 5 Pro);
  legacy printers fall back to TCP `~M115` `Machine Type:`. Never substring-match the user-editable
  printer name.
- **`/detail` is the source of truth** for modern printers (status, temps, IFS inline). TCP is
  control-only there; only `GenericLegacyBackend` polls over TCP (M119 + M105 + M27).
- **Creator 5 family is HTTP-only** — never open 8899; TCP-only ops throw `NotSupportedException`.
  Temps go over HTTP `temperatureCtl_cmd`: `nozzles[]` has exactly 4 entries and uses `0` (not
  `-100`) for off; bed/chamber use `-100` to cancel. Chamber control is gated on
  `FFMachineInfo.hasChamberSensor` (base C5 reports `-108`).
- **5M / 5M Pro / AD5X temps go over TCP** (M104 / M140).
- **Temperature sentinels** (`<= -50`) are absent in `FFMachineInfo`; `FFPrinterDetail` keeps raw values.
- **Envelope errors:** only code `1` is `AuthException`; `-1` / `-2` (bad params / not in LAN mode)
  are `ApiErrorException` with the firmware message.
- **Slot colors** must snap to the model's own 24-entry palette (`Ad5xPalette` / `Creator5Palette`,
  CIEDE2000 via `PaletteSnap`) and be sent as uppercase `#RRGGBB` with the `#`. The palettes differ.
- **`/product` is unreliable** for the Creator 5 Pro; gate filtration/TVOC/door on pid, not flags.
- **Discovery:** the empty broadcast payload is deliberate — it works on real hardware.
- **Legacy (A3/A4, emulator-verified):** M661 file list (A4 `::` vs A3 `info_list.size:`), M662
  thumbnail (A4 raw PNG vs A3 `0xa2a22a2a` magic), `~M146` LEDs (A4 RGB vs A3 on/off).
- I/O on `Dispatchers.IO`, `soTimeout = 10000`, 10 s connect timeout; HTTP commands are serialized
  per client (reads are not). Release `~M602` and close everything on every exit path.

## Conventions

- Idiomatic Kotlin: `val`, strict nullability, data classes, `suspend` funs returning `Result<T>`
  with typed `FlashForgeException` causes.
- `kotlinx.serialization` (`ignoreUnknownKeys = true; explicitNulls = false`) + OkHttp; one shared
  default `OkHttpClient`.
- KDoc the public API. Port tests alongside code. Changes that alter behavior get a CHANGELOG entry.
