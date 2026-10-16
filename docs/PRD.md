# PRD — Muse R1 Widget MVP

Status: proposed · Owner: @zZyan · Last updated: 2026-10-15

## Vision

A voice-first, self-contained Muse gadget experience running natively on a
Rabbit r1 (LineageOS). The r1 is not a companion screen — it is the Muse
device: talk to it, take photos with it, receive reminders on it. The
experience diverges from both RabbitOS and the original muse-r1 by being
fully controllable: no USB-only provisioning, no single-thread chat, no
dead-end voice notes.

## Guiding principles

1. **Voice first.** Every flow starts with the side button and the microphone.
2. **On-device control.** Token entry, camera, motor, and pairing must not
   require a host computer.
3. **Preserve the wire.** The vendored transport stays; features ride the
   protocol the upstream SDK already supports.

---

## Stage 0 — Protocol fixes (foundation, ~1 h)

Foundation for everything after; invisible UX.

1. Add `device_id` to `/chat/stream` bodies (`transport/MuseConnection.kt`).
   Upstream includes it; without it Muse cannot attribute turns to the gadget
   or route follow-up device commands.
2. Handle `agent.status` + `task.status` events in `ReplyTracker.kt`
   (currently dropped) — surface to the status line.
3. Tag every event with `session_id`; route turns by `(session_id, message_id)`.

Acceptance: build unit-tested; manual send/receive unchanged.

## Stage 1 — P0 MVP: token + main chat + side chats (~1 day)

1a. **On-screen SDK token input.** IME text entry on a setup screen;
    `CredentialStore.saveToken(String)` mirroring the pending-file import
    validation (non-blank, ≤16 KiB, Keystore AES-GCM, no backup).
    Acceptance: fresh install → type token → connects.

1b. **Side chats on the wire.** `session_id` in send + subscribe;
    `owns_chat` ack validation (`is_thread`, per upstream `MuseTurn`);
    replay dedup (`ReplayScope`, bounded 2048 IDs).
    Acceptance: send to a new UUID → Muse acks a dedicated thread.

1c. **Side chat UI.** Expandable menu from the left corner: Main chat +
    side-chat list with unread blue dots (local unread counts). Same visual
    language as the Muse phone app's chat list.
    Acceptance: phone-side side chat → blue dot → open → dot clears.

1d. **Main chat history.** Render recent server history on connect
    (`GET /chat/history?limit=20`); fall back to local display history on 403.

## Stage 2 — Camera → Muse + attachments (~½ day)

1. Capture → `items[]` as `{type:"file", mime_type:"image/jpeg", data_base64}`
   (≤2 MiB, resize/compress first). Send to main or the active side chat.
2. Render image content blocks in replies (currently stripped at
   `ReplyTracker.kt` text extraction).
3. Generic file attach (SAF picker) behind the same `items[]` path.

Acceptance: photo taken in camera → arrives in the Muse conversation.

## Stage 3 — Reminders + notifications (~½ day)

1. `task.status` running/completed → device notifications.
2. Avatar menu → Tasks/Reminders list from tracked task events.
3. Deliver while backgrounded: notification channel + AlarmManager;
   persistent connection is a separate decision (foreground service).

Acceptance: task set via voice → completion notification on the r1.

## Stage 4 — Streaming dictation (~½ day)

1. Port `/api/voice/dictation` (streamed PCM, NDJSON partial transcripts).
2. Long-press → live streaming with partials; release → final transcript
   (server-side filler-word cleanup), chat box to edit, then send.
3. Keep the existing voice-note path as fallback.

## Stage 5 — Polish (~½ day)

Emoji rendering in replies, avatar reaction states keyed to `agent.status`
(thinking/speaking/idle), conversation text-input bar, shake-to-clear retained.

## Deferred

- Lip reading (camera → ML model → predicted text, merged with mic input).
- `link.register` / `link.invoke` (Muse commanding the r1).
- Home network tunnel. Muse native voice output (blocked server-side).

## Risks

| Risk | Mitigation |
|---|---|
| No "list side chats" endpoint | Track sessions as events arrive; history per known session |
| History route 403 (observed) | Live events primary; local history fallback |
| Background delivery needs persistent socket | Foreground service or accept foreground-only in MVP |
| IME usability on 480×640 | Test early in Stage 1; wheel-driven picker as fallback |