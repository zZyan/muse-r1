# UX reference — official Muse app (com.facebook.aura v11)

Captured 2026-10-15 on-device via accessibility-tree dumps (480×640).
Structure and geometry only; no user content. Source for PRD Stages 1c
and 3. The dumps use stable `hatch-*` resource ids.

## 1. Chat view — `hatch-chat-view` (main chat + side chats)

| Zone | Bounds | Element |
|---|---|---|
| Message bubbles | full width, stacked | `hatch-message-assistant` (latest: `hatch-message-assistant-latest`) |
| System/task notes | same bubbles | task results and download warnings render inline in the chat |
| Avatar button (center-top) | `[190,53][290,153]` | opens the status screen (below); persona name label under it |
| Side panel button (top-left) | `[20,53][80,113]` | "Open side panel" |
| Invite (top-right) | `[400,53][460,113]` | `hatch-invite-friends-button` |
| Composer row | y ≈ 433–493 | Add attachment `[23,433][83,493]` · EditText `hatch-message-input` `[75,432][397,492]` (placeholder "Message") · Voice input (mic) `[400,433][460,493]` |
| Bottom nav (fixed) | y ≈ 513–573 | 5 tabs × 76 px: `hatch-fixed-tab-chat` / `-feed` / `-ideas` / `-goals` / `-library` |

Design cues for Muse R1:
- Composer is present but voice is a first-class second button, not a hidden mode — voice-first with text fallback (PRD Stage 4).
- Attachment button sits left of the input; camera capture should reuse this slot (PRD Stage 2).
- Numbered inline quick-replies ("1. / 2." rows) are rendered as tappable chat rows — candidate for reply actions.

## 2. Side panel — "Open side panel" (Stage 1c menu)

Overlay panel (~354 px wide) with:

```
< personas header >
TABS button            (label: "You can reorder with long press")
  Main chat            row 354×56
  Feed
  Ideas
  Goals
  Library
Settings (bottom-left)   New side chat (bottom-right)
Dismiss (right edge)
```

- Rows are the same five fixed tabs; long-press reorders them.
- "New side chat" creates a side chat — confirms side chats are a first-class
  surface, matching the `session_id` protocol support (PRD Stage 1b).
- Muse R1 mapping: left-corner expandable menu shows Main chat + side chats
  (unread blue dots) + device settings; side-chat creation/reorder deferred.

## 3. Status screen — avatar tap (`hatch-status-screen`)

```
[Close]          avatar (Edit) [Share avatar]
      persona name
      status text ("online")

hatch-status-tabs (segmented, 5 × 84 px, y 240–337):
  Activity | Approvals | Desktop | Upcoming | Identity

hatch-activity-view (y 337–640, scrollable):
  hatch-activity-day-card
    "Today"
    hatch-activity-action-row (480×109..136)
      <task title>
      <one-line result summary>
      <timestamp>
```

- **Activity** = agent task log — rows of browser/research tasks with title,
  summary, timestamp. Direct visual target for `task.status` handling
  (PRD Stage 0 → 3): each gadget-visible task should render the same way.
- **Approvals** = approve/confirm flows from Muse (not captured in detail).
- **Desktop / Identity** = file/memory surfaces (not captured in detail).

Design cues: the user-requested "avatar → items list" is this screen; the
r1 rebuild should show persona status + the same five tabs, with Reminders
(Upcoming) and Tasks (Activity) as the two P0 tabs (see PRD priority).

## 4. Upcoming tab = Reminders (Stage 3 reference)

```
Reminders                     (section header)
  row (480×109, tappable):
    <reminder title>
    <date>  e.g. month-day-year
    <time>  e.g. 9:00 AM
  ...
```

- Flat list, no grouping visible on this account; scrollable past 2–3 rows.
- Rows carry date AND time — enough for r1-side AlarmManager scheduling of
  device notifications (PRD Stage 3c: notification channel + alarm).
- The r1 can surface these as notifications without a persistent connection
  by scheduling locally once reminders are fetched.

## Measurement constants (480×640 reference)

- Bottom nav height: 67 px (513→573 content + 580 label band).
- Composer height: 60 px; input field width 322 px between two 60×60 buttons.
- Row height (lists): 56 px (menu) / 109–136 px (content rows).
- Side panel width: ~354 px with 13 px margins.
- Everything is 1-handed: primary actions live in y 400–580.