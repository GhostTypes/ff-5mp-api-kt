# ff-5mp-api-kt

Kotlin/JVM (Android-first) client library for FlashForge 3D printers — a 1:1 port of the
[`ff-5mp-api-ts`](https://github.com/GhostTypes/ff-5mp-api-ts) TypeScript library.

Speaks the FlashForge LAN wire protocol: HTTP REST (8898), TCP G-code (8899), MJPEG camera
(8080), and UDP discovery. Targets the Adventurer **5M / 5M Pro / AD5X** and **Creator 5 / Creator 5 Pro**
(modern HTTP+TCP; the Creator 5 family is HTTP-only, with no legacy TCP/8899 service),
plus legacy **Adventurer 3 / 4** (TCP).

Built to replace the in-tree protocol layer of the FlashForge Android app with a
standalone, reusable library.

> **Status:** core ported and verified against a 5M Pro and AD5X. Creator 5 / Creator 5 Pro
> support is ported (wire formats verified against `ff-5mp-api-ts` v1.6.1) but not yet
> verified on hardware. See [CLAUDE.md](CLAUDE.md) for the porting plan and
> [docs/parity.md](docs/parity.md) for intentional divergences from the TS lib.

## What's implemented

- **Models + `MachineInfo.fromDetail`** — `/detail` shapes with pid-first model detection.
- **TCP** — `FlashForgeClient` (persistent read-loop transport, keep-alive, reconnect) + typed
  status queries (M105/M115/M119/M27/M114) + `GCodeController` control commands.
- **HTTP** — `FlashForgeHttpApi` (OkHttp): `/detail`, `/product`, `/control`, `/printGcode`,
  `/gcodeList`, `/gcodeThumb`, with typed exceptions.
- **Backend tier** — `PrinterBackend` + `DualApiBackend` +
  `Adventurer5M/5MPro/AD5X/Creator5/GenericLegacy` + `PrinterBackendFactory` (the recommended
  high-level API).
- **Discovery** — `PrinterDiscovery.discover(context?)` (UDP, 276/140-byte protocols).
- **Creator 5 family** — `Creator5Backend` drives the HTTP-only Creator 5 / Creator 5 Pro:
  HTTP temperature control (`TempControl` / `temperatureCtl_cmd`, per-tool + chamber),
  `creator5Palette` (24-entry CIEDE2000 color snap), `msConfig_cmd` slot config, and
  Creator 5 start-job / file upload.

Not yet ported (see parity.md): `FiveMClient` module split, A3/A4 subclasses, camera probe.

## Consuming it from the app

Build and install to the local Maven repo:

```bash
./gradlew :ffapi:publishToMavenLocal   # publishes me.ghost:ff-5mp-api-kt:0.4.0
```

In the app, add `mavenLocal()` to the repositories and depend on it:

```kotlin
// settings.gradle.kts (dependencyResolutionManagement { repositories { ... } })
mavenLocal()

// app/build.gradle.kts
implementation("me.ghost:ff-5mp-api-kt:0.4.0")
```

Then construct a `FlashForgeHttpApi` + `FlashForgeClient`, resolve the model via
`PrinterModel.fromDetail(...)`, and build a backend with `PrinterBackendFactory.create(...)`.

## Build

```bash
./gradlew :ffapi:testDebugUnitTest   # unit tests (run on the local JVM)
./gradlew :ffapi:assembleRelease     # build the AAR
```
