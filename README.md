# ff-5mp-api-kt

Kotlin/JVM (Android-first) client library for FlashForge 3D printers — a 1:1 port of the
[`ff-5mp-api-ts`](../ff-5mp-api-ts) TypeScript library.

Speaks the FlashForge LAN wire protocol: HTTP REST (8898), TCP G-code (8899), MJPEG camera
(8080), and UDP discovery. Targets the Adventurer **5M / 5M Pro / AD5X** (modern HTTP+TCP) and
legacy **Adventurer 3 / 4** (TCP).

Built to replace the in-tree protocol layer of the `flashforgeui-app` Android app with a
standalone, reusable library.

> **Status:** core ported and live-verified against a 5M Pro + AD5X. See [CLAUDE.md](CLAUDE.md) for
> the porting plan and [docs/parity.md](docs/parity.md) for intentional divergences from the TS lib.

## What's implemented

- **Models + `MachineInfo.fromDetail`** — `/detail` shapes with pid-first model detection.
- **TCP** — `FlashForgeClient` (persistent read-loop transport, keep-alive, reconnect) + typed
  status queries (M105/M115/M119/M27/M114) + `GCodeController` control commands.
- **HTTP** — `FlashForgeHttpApi` (OkHttp): `/detail`, `/product`, `/control`, `/printGcode`,
  `/gcodeList`, `/gcodeThumb`, with typed exceptions.
- **Backend tier** — `PrinterBackend` + `DualApiBackend` + `Adventurer5M/5MPro/AD5X/GenericLegacy`
  + `PrinterBackendFactory` (the recommended high-level API).
- **Discovery** — `PrinterDiscovery.discover(context?)` (UDP, 276/140-byte protocols).

Not yet ported (see parity.md): `FiveMClient` module split, file upload, A3/A4 subclasses, camera probe.

## Consuming it from the app

Build and install to the local Maven repo:

```bash
./gradlew :ffapi:publishToMavenLocal   # publishes me.ghost:ff-5mp-api-kt:0.1.0
```

In the app, add `mavenLocal()` to the repositories and depend on it:

```kotlin
// settings.gradle.kts (dependencyResolutionManagement { repositories { ... } })
mavenLocal()

// app/build.gradle.kts
implementation("me.ghost:ff-5mp-api-kt:0.1.0")
```

Then construct a `FlashForgeHttpApi` + `FlashForgeClient`, resolve the model via
`PrinterModel.fromDetail(...)`, and build a backend with `PrinterBackendFactory.create(...)`.

## Build

```bash
./gradlew :ffapi:testDebugUnitTest   # unit tests (run on the local JVM)
./gradlew :ffapi:assembleRelease     # build the AAR
```
