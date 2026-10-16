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

### Added (late evening, same release)
- Side chat support end-to-end: `session_id` on send + ack validation,
  per-session NDJSON subscriptions (reply deltas only flow per session),
  session activity observation with unread counting, left-corner
  side-chats panel (Main chat + scrollable sessions + blue-dot unread +
  New side chat), per-session local history with switch/clear.
- Server main-chat history adoption on first connect (`/chat/history`
  limit 20; falls back to local display on 403).
- Attachments: right-corner + button. Take photo → custom camera with
  `for_muse` result (auto-compressed to ≤2 MB) → "say something"
  voice-first commentary → next voice turn carries WAV + JPEG `items[]`.
  Choose file → SAF picker with the same pending-attachment flow.
- Character avatar renders the user's own emoji glyph (preferences key
  `avatar_emoji`, default 🙂) — removes the non-redistributable
  plush illustration from the render path.

### Known limitations
- On-screen SDK token entry not implemented (Stage 1a of `docs/PRD.md`).
- Single main chat only; side chats are Stage 1b/1c.
- No camera-to-Muse send path yet (Stage 2).
- On this GSI: system auto-rotate dead (per-app fallback included),
  power-double-press launch timing-sensitive.