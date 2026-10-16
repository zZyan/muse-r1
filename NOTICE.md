# NOTICE

Muse R1 is licensed under the Apache License, Version 2.0 (see `LICENSE`).

## Third-party components and origins

| Component | Origin | License |
|---|---|---|
| `app/src/main/java/com/zzyan/r1/muse/transport/` | Vendored from Meta's [muse-gadget-sdk](https://github.com/facebookincubator/muse-gadget-sdk), commit `7e7123e`. Provenance details in that directory's `PROVENANCE.md`. | Apache-2.0 |
| `app/src/main/java/com/southernstorm/noise/` | Vendored Noise protocol implementation (southernstorm/noise-c, Java port). See that directory's `PROVENANCE.md`. | MIT |
| `app/src/main/java/com/zzyan/r1/muse/transport/LICENSE-APACHE-2.0.txt` | Copy of the Apache-2.0 text, retained with the vendored SDK sources. | Apache-2.0 |

## Prior work

This project began as a fork-informed rebuild of
[cameronapak/muse-r1](https://github.com/cameronapak/muse-r1) (MIT), a native
Android Home app that turns a Rabbit r1 into a Muse gadget. The codebase has
been renamed, restructured into new packages (`com.zzyan.r1.muse` with
`core/`, `camera/`, `transport/`, `hardware/`, `pairing/`), and extended with:
a custom camera app with rotating-motor control, a home-screen camera widget,
accelerometer-based rotation, and side-button double-press camera launch.

Original MIT code by Cameron Pak is redistributed here under Apache-2.0 with
this notice in place of the original license text, as permitted by MIT.

The character illustration (`muse_character.png`) is NOT covered by any open
license; its redistribution rights are not established.