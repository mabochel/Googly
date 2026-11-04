# Assistant-first Bluey phone app (Android)

Date: 2026-11-03 · Branch: `fix/voice-assistant-mode`

## Problem

Since Riley's Oct 22 rebuild, the phone app is built only for focus sessions:

- The main screen shows "What's the mission?", a 30:00 countdown and focus buttons. Nothing tells you Bluey can
  look at your screen, point, type, click, open apps or sites, research, or run Codex, even though the Mac still
  has all of those tools.
- After every held sentence the phone adds a system note: "When they describe work, call focus_start… Never
  merely say you're ready." On the real phone, requests like "Open Notes and type hello from Bluey" become focus
  sessions, even after the Mac's instructions were fixed.
- There is no way to type to Bluey, and the phone never shows what he did or why something failed, so when a
  request goes wrong there is nothing to see.

## Goal

Ask Bluey for anything, by voice or by typing, and see what he's doing. Focus becomes one thing you can ask for
(or start from the Focus button), not the only thing the app does.

Out of scope: the iPhone app, the Mac app's UI, meeting notes, a Codex UI, changing pairing or the encrypted link.

## Design

### 1. Request note (fixes the routing at its source)

Replace the focus-only note sent after each held (or typed) request with a neutral one:

> The preceding words were spoken (or typed) to Bluey. Do what they ask with your tools, or answer them. Start a
> focus session only if they clearly say they are about to work on something themselves, or ask to focus.

The note lives in one small pure function (`RequestNote`) so it can be unit-tested. The Mac's session instructions
(already changed on this branch) say the same thing, so phone and Mac agree.

### 2. Main screen (assistant first)

Landscape, Bluey's face full screen as today. Overlays:

- **Top left:** the last thing you asked (or "Hold Bluey or type to ask anything"), and under it one status line:
  what Bluey is doing or did ("Looking at your screen…", "Opening Notes…", "Typing…", "Done", or the error the Mac
  reported). His latest reply also shows here, not only in the bubble on the Mac.
- **Bottom:** three suggestion chips that send a request when tapped: "What's on my screen?", "Open Notes and type a
  note", "Look something up". Hidden while he's working.
- **Bottom left:** keyboard (type a request), Focus (opens the existing focus dashboard), voice status icon.
- **Focus only when running:** the mission name, countdown and pause/stop buttons appear only while a focus
  session exists, in a compact corner block. With no session there is no 30:00 timer.
- **Top right:** unchanged (sound, Bluey's history screen).
- Pairing screen headline: "Your little blueberry helper" instead of "Your little focus friend".

### 3. Type to Bluey

The keyboard button opens a one-line text field. Sending it:

- wakes Bluey if he's asleep (same path as holding his face), and once the session is ready
- sends a user text message + the request note + `response.create` (text only), exactly like a held request.

Typed requests appear in the voice history like spoken ones.

### 4. Activity feedback

`LiveVoice` gets an `activity` string the screen shows:

- When the model calls a tool, a friendly label from a pure function (`ToolLabels`): look_at_screen → "Looking at
  your screen…", open_app → "Opening {name}…", type_text → "Typing…", click → "Clicking…", web_research →
  "Researching…", focus_start → "Starting focus…", codex_start → "Asking Codex…", unknown → "Working…".
- When a tool's result looks like a failure (the Mac returns messages like "I need Accessibility permission…" or
  "The Mac didn't answer."), show that text.
- Every tool call and result is also written to Android's log (`adb logcat -s BlueyVoice`), so problems can be
  diagnosed over USB.

## Data flow

Hold face or type → `LiveVoice` (Realtime session on the phone) → model picks a tool → phone sends a `tool` packet
to the Mac over the encrypted link → Mac runs it (look, point, type, click, open, research, focus, Codex) → result
back to the phone → `activity` updates → model replies → reply shows on the phone and in the Mac bubble.

## Errors

- No Mac connection: chips and keyboard disabled, status says "Pair or connect your Mac".
- Voice can't connect or transcription fails: existing messages, shown in the status line.
- Tool failure: the Mac's message, shown in the status line and logged.

## Testing

- JVM unit tests (run in CI): `RequestNote`, `ToolLabels`, failure detection, and that the suggestion chips map to
  the requests they send.
- Screenshot tests (Roborazzi, already in the project): render the new main screen idle, working, and with a focus
  session running; CI uploads them so the layout can be checked before installing.
- Mac live probe (`VoiceRoutingLiveTests`, opt-in) updated to use the new note.
- On the device over USB: voice and typed "Open Notes and type hello from Bluey" opens Notes and types; "What's on
  my screen?" looks and answers; "I'm writing my report for 30 minutes" starts focus; `adb logcat` shows each tool.

## Build and install

- GitHub Actions builds the APK on push (existing workflow); it also uploads the screenshots.
- Install over USB with `adb`. The CI build is signed with a different key than the installed app, so the old app
  is uninstalled first and the phone is paired again once (scan the Mac's QR code).
