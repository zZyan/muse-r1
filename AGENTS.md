---
name: muse-r1
description: "Development rules for Muse R1 — the Android app that turns a Rabbit r1 running LineageOS into a voice-first Muse gadget. Use when building, modifying, installing, testing, or pushing this app, or when touching transport, pairing, camera, motor, or side-button code. Triggers: build, install, verify, camera, motor, pairing, accessibility, push."
version: 0.1.0
---

# Muse R1 — Agent Development Guide

## What This Is

Native Android app (Views, no Compose/WebView) for a Rabbit r1 running
LineageOS 21. It pairs with Meta's Muse through the vendored
[muse-gadget-sdk](https://github.com/facebookincubator/muse-gadget-sdk)
protocol: BLE pairing, SDK token, Noise-XX encrypted WebSocket.
It is not RabbitOS and not an official Rabbit or Meta app.

## Package Layout

```
com.zzyan.r1.muse/
  core/         MainActivity, MuseApp, MuseScreen, SpeechOutput,
                VoiceRecorder, DisplayHistory, CredentialStore,
                DeviceCredentials, ShakeDetector, AutoRotate
  camera/       CameraActivity (CameraX + motor flip), CameraLauncherActivity,
                CameraWidgetProvider
  hardware/     SideButtonService (accessibility), SideButtonGesture
  transport/    MuseConnection, ReplyTracker, WireCodec, NoiseSession
                (vendored; keep PROVENANCE.md intact)
  pairing/      PairingServer (BLE GATT), PairingCrypto, BleFraming
docs/
  PRD.md  SPEC.md  CHANGELOG.md  architecture.md  setup.md
  hardware/     Device troubleshooting (camera, motor, GSI quirks)
```

## Core Rules

| Rule | Do | Don't |
|---|---|---|
| Pairing | Update with `adb install -r` | Uninstall or clear data (loses credentials) |
| Secrets | Keep SDK tokens, serials, chats out of source and logs | Commit or screenshot credentials |
| Transport | Preserve vendored SDK provenance and licenses | Rewrite Noise/wire codec without a test |
| Camera/motor | Resync motor `0→90→180` via `su` before camera tests | Write sysfs without root |
| Device ops | Ask before flashing, rebooting, wiping, keylayout edits | Force anything on the physical r1 |
| Build claims | State what was executed vs. what needs hands-on checks | Claim device verification you did not run |

## Build

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:assembleDebug          # build
./gradlew :app:testDebugUnitTest      # unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

SDK path is in the ignored `local.properties`. Install preserves pairing;
a package change, uninstall, or data clear voids it.

## Pre-Push Checklist

1. `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes.
2. Diff contains no tokens (`mgst_…`), device serials, or conversation text.
3. Camera still enumerates: `adb shell dumpsys media.camera | grep "devices:"`.
4. Side-button gestures still pass if `hardware/` changed.
5. `CHANGELOG.md` updated for user-visible changes.

## Known Device Facts

- Camera motor: `/sys/devices/platform/step_motor_ms35774/orientation`
  (`0`=front, `90`=privacy, `180`=rear), needs Magisk `su` and a resync
  cycle after boot. Details: `docs/hardware/CAMERA_ISSUE.md`.
- Auto-rotate is broken on this GSI (dead MTK `DEVICE_ORIENTATION` sensor);
  forced rotation works. Details: `docs/hardware/`.
- Power-button double-press launch is timing-sensitive: first press slightly
  longer, second press quick.
- Muse native voice output (`output_modality:"voice"`) returns server errors;
  Android TTS is the standing fallback (ADR `0001`).

## Boundaries

**Works today:** voice notes (hold side button), tap-to-lock, wheel volume,
camera capture→gallery with front/rear motor flip, camera widget,
double-press camera launch, display history.

**In pipeline (see `docs/PRD.md`):** on-screen token input, side chats
(`session_id`), camera→Muse send, task/reminder notifications, streaming
dictation. **Not planned:** replacing the vendored Noise implementation,
background voice capture.

## Resources

- `docs/PRD.md` — staged feature plan (P0 = token + side chats)
- `docs/SPEC.md` — protocol capability map and implementation gaps
- `docs/hardware/` — camera, motor, and GSI troubleshooting
- `CONTEXT.md` — project glossary (what counts as "voice note", "pairing")
- `docs/architecture.md` — data flow, pairing, transcript handling