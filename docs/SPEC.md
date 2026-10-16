# SPEC — Muse R1 engineering notes

Status: living document · Last updated: 2026-10-15

Companion to `PRD.md`. Records what the protocol offers, what this app
implements, and what it deliberately does not. Sources: vendored upstream
SDK (`linux/src/musegadget/link_client.py`, `muse_turn.py`, `executor.py`
at commit `7e7123e`), gadgets.muse.ai docs, and live device testing.

## Auth layers (all implemented)

1. **SDK token** — `gadgets.muse.ai/settings/sdk-tokens`. Currently imported
   over USB into `files/pending-sdk-token` → Keystore encryption
   (`core/CredentialStore.kt`). On-screen entry is Stage 1a.
2. **Device credentials** — BLE pairing protocol v5 (`pairing/PairingServer.kt`):
   ECDH P-256 + HKDF → AES-GCM; `provision_v2` delivers access/refresh token.
   Host allowlist: `api.muse.ai`, `*.metaaivm.com`, `*.muse.ai`.
3. **VM session** — `POST /device_token/refresh` then
   `wss://<noiseHost>/v1/noise?vm_id=` with Noise_XX_25519_AESGCM_SHA256
   (`transport/NoiseSession.kt`, vendored southernstorm runtime).

## Wire protocol (transport/)

- Envelopes: `ServiceFrame{stream_id, kind}` — kind 2 ApplicationRequest,
  3 ApplicationResponse, 4 BodyChunk, 5 Reset. Noise frames chunk ≤64 KiB,
  ≤16 MiB assembly.
- Endpoints used today: `/fetch_vms`, `/device_token/refresh`, `/chat/stream`,
  `/chat/subscribe` (NDJSON), `/chat/history` (transcript fallback only).

## Capability map — upstream SDK vs Muse R1

| Capability | Upstream | Muse R1 | Gap work-stream |
|---|---|---|---|
| Voice note (WAV ≤2 MiB) | `send_voice(session_id=…)` | ✅ (≤640 KB, no session) | Stage 0 |
| Text chat | `send_chat(session_id=…)` | ❌ voice-only input | Stage 4 chat box |
| Side chats | `session_id`, `owns_chat`, `is_thread` validation | ❌ absent | Stage 1b/1c |
| `device_id` in chat POST | always sent | ❌ omitted | Stage 0 |
| Per-session subscription | `subscribe_chat(session_id)` | ❌ single stream | Stage 1b |
| Agent status (12 activity codes) | `agent.status` events | ❌ dropped | Stage 0 → 5 |
| Task status (terminal states) | `task.status` events | ❌ dropped | Stage 0 → 3 |
| Turn lifecycle (follow-ups, quiet timeouts) | `MuseTurn`, `ReplayScope` | ⚠️ simplified `ReplyTracker` (300 s hard cap) | Stage 1b |
| History render | `/chat/history?limit=N` | ⚠️ transcript fallback only, 403 risk | Stage 1d |
| Voice replies (MP3 TTS) | `stream_tts(message_id)` | ❌ Android TTS fallback (ADR 0001, server errors) | Deferred |
| Streaming dictation | `/api/voice/dictation` (24 kHz PCM NDJSON) | ❌ not ported | Stage 4 |
| Generic file items | `items[]`, any mime ≤2 MiB | ❌ audio/wav only | Stage 2 |
| Image content blocks in replies | structured transcript | ❌ stripped | Stage 2 |
| Image display | ESP32 boards | ❌ text only | Stage 2 |
| `link-control` registration | `link.register`, commands_v2 | ❌ absent | Deferred |
| `link.invoke` (commands) | `system.run`, `file.read/write`, `device.health` | ❌ absent | Deferred |
| Unpair events | `link.unpaired`, `node.unpaired` | ❌ absent | Stage 0 (detect) |
| Animated avatar | ESP32 pixel renderer (Jollybot excluded from license) | ⚠️ static PNG + canvas overlays | Stage 5 |

## Device-specific facts (validated on-device)

- Camera: one MTK hi846 device, `Facing=Back, Orientation=90`, enumerated only
  on matched boot/vendor/dtbo firmware trio (see `docs/hardware/CAMERA_ISSUE.md`).
- Motor: `echo <deg> > /sys/devices/platform/step_motor_ms35774/orientation`
  via Magisk `su` (0 front / 90 privacy / 180 rear); driver desyncs after
  boot — resync cycle `0→90→180` restores control.
- Front-mode preview requires 180° view rotation on a TextureView
  (`implementationMode = COMPATIBLE`); SurfaceView ignores the transform.
  Front photos get the same 180° bitmap post-correction.
- Auto-rotate dead system-wide: MTK `DEVICE_ORIENTATION` sensor (type 27)
  never fires under GSI; WindowOrientationListener binds to it and ignores
  the working accelerometer. Forced `user_rotation` works. See
  `docs/hardware/` for the full diagnosis.
- Power double-press → camera launch works (system gesture), timing
  sensitive; `adb shell input keyevent 26 26` reproduces.

## Testing posture

- Unit tests: `transport` wire codec, Noise session, ReplyTracker, pairing
  crypto/framing, core display history, gestures. Run: `:app:testDebugUnitTest`.
- Instrumentation (`DeviceChecks`) needs a physical r1 + emulator fixture;
  treat as manual, not CI.
- Delivery claims distinguish local code, executed checks, installation,
  and hands-on confirmation (AGENTS.md "Build claims").