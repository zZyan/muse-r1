# Changelog

All notable changes to Muse R1. Format loosely follows Keep a Changelog;
versioning is `<stage>.<feature>.<fix>` starting at 0.1.0.

## [0.1.0] — 2026-10-15

### Project
- Renamed the project to `com.zzyan.r1.muse`; reorganized into `core/`,
  `camera/`, `hardware/`, `transport/`, `pairing/` packages.
- Relicensed first-party code under Apache-2.0 (see `NOTICE.md` for origins
  and retained licenses).
- Fresh single-commit history on `zZyan/muse-r1`.

### Added
- Custom camera (`camera/CameraActivity`): CameraX preview, front/rear
  flip via the r1 rotating motor (`su` → sysfs orientation), privacy
  90° on close, side-button shutter while in the foreground.
- Front/rear orientation correction: TextureView preview rotation and
  180° bitmap post-correction for front captures.
- Home-screen camera widget (`camera/CameraWidgetProvider`) and
  `CameraLauncherActivity` trampoline.
- Side-button double-press → camera launch (`hardware/SideButtonService`).
- Accelerometer-driven `user_rotation` fallback (`core/AutoRotate`).

### Inherited (from the cameronapak/muse-r1 base, restructured)
- Voice notes over BLE pairing + SDK token provisioning, Noise-XX
  WebSocket transport, reply/transcript tracking, Android TTS playback,
  local display history, tap-to-lock, wheel volume, PIN-locked boot flow.

### Known limitations
- On-screen SDK token entry not implemented (Stage 1a of `docs/PRD.md`).
- Single main chat only; side chats are Stage 1b/1c.
- No camera-to-Muse send path yet (Stage 2).
- On this GSI: system auto-rotate dead (per-app fallback included),
  power-double-press launch timing-sensitive.