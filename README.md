# Muse R1

A voice-first [Muse](https://muse.ai) gadget experience for the Rabbit r1,
running on LineageOS. Hold the side button, speak, and Muse replies — on
screen and aloud. Take photos with the on-device camera, flip between
front and rear with the rotating motor, and launch the camera from the
home screen with a double press.

Native Android app (no WebView, no Compose). Built on Meta's open
[muse-gadget-sdk](https://github.com/facebookincubator/muse-gadget-sdk)
protocol: BLE pairing, SDK tokens, Noise-XX encrypted WebSocket.
Not RabbitOS and not an official Rabbit or Meta product.

## What it does today

- **Voice notes** — hold the r1 side button to record (0.3–20 s), release
  to send; Muse transcribes and replies; Android TTS reads the reply aloud.
- **Camera** — custom CameraX app: shutter button or side-button shutter,
  front/rear flip via the rotating motor (`su` → sysfs), privacy 90° on
  close, photos saved to the gallery.
- **Launch paths** — home-screen camera widget, launcher icon, or the
  system power double-press gesture.
- **Side button** — tap to lock, hold to talk, hold+wheel for volume.
- **Display history** — the conversation stays on the r1 until you clear it.

## What is coming (staged)

On-screen SDK token entry, side chats with unread indicators,
camera-to-Muse photo sending, task/reminders notifications, streaming
dictation. See [docs/PRD.md](docs/PRD.md) and the current capability map
in [docs/SPEC.md](docs/SPEC.md).

## Getting started

1. [Install LineageOS](docs/lineageos.md) on your r1 (unlocked bootloader;
   wiping required). Install Magisk if you want the camera motor controls.
2. [Set up Muse r1](docs/setup.md): build, install, provision your
   [SDK token](https://gadgets.muse.ai/settings/sdk-tokens), pair from the
   Muse phone app (Settings → Devices → Developer mode), enable the side
   button service.
3. Unlock, hold the side button, and talk.

Device quirks (camera, motor, auto-rotate) are documented in
[docs/hardware/](docs/hardware/).

## Building

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17   # Java 17
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Package: `com.zzyan.r1.muse`. Development guidance for humans and agents:
[AGENTS.md](AGENTS.md). Architecture deep-dive: [docs/architecture.md](docs/architecture.md).

## Status

Source-built debug builds; single maintainer. Distinguish what is verified
locally versus confirmed on hardware — the verification rules live in
[AGENTS.md](AGENTS.md).

## License

Apache-2.0 for this project. Vendored SDK and Noise sources keep their
upstream licenses — see [NOTICE.md](NOTICE.md). The character illustration
is not redistributable.