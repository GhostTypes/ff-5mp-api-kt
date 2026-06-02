# ff-5mp-api-kt

Kotlin/JVM (Android-first) client library for FlashForge 3D printers — a 1:1 port of the
[`ff-5mp-api-ts`](../ff-5mp-api-ts) TypeScript library.

Speaks the FlashForge LAN wire protocol: HTTP REST (8898), TCP G-code (8899), MJPEG camera
(8080), and UDP discovery. Targets the Adventurer **5M / 5M Pro / AD5X** (modern HTTP+TCP) and
legacy **Adventurer 3 / 4** (TCP).

Built to replace the in-tree protocol layer of the `flashforgeui-app` Android app with a
standalone, reusable library.

> **Status:** scaffolding. See [CLAUDE.md](CLAUDE.md) for the full porting plan and references.
