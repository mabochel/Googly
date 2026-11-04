# Fast Bluey: lean voice sessions, Codex for long jobs, fun modes (Android)

Date: 2026-11-04 · Branch: `fix/voice-assistant-mode` · Supersedes the Codex-only draft of this file.

## Why

The user wants fast replies (1–3 s) and Bluey's own cursor pointing, clicking and typing. Codex can't do either
(10–60 s per request, no access to Bluey's cursor), so the Realtime voice model stays. Tonight's problems with it:

- 10 replies failed with `rate_limit_exceeded`, and $1.53 of the $1.70 spent was Realtime input. Every action
  result includes a full screen read (6–13k characters) and look_at_screen adds a screenshot; all of it stays in the
  conversation and is re-sent on every response.
- Long chains of clicks in other apps (find a ChatGPT chat, ask for a PDF, open it) went badly.

Codex is installed, signed in with ChatGPT (no API cost) and Bluey can already start Codex jobs (`codex_start`).

## Design (phone only; the Mac app is not rebuilt, so macOS permissions stay)

### 1. Lean context

The phone gives its own ids to the items it adds (`conversation.item.create` with `item.id`): tool outputs
(`function_call_output`) and screenshot messages. After each tool round, it deletes (`conversation.item.delete`)
all screen-carrying items except the newest round's, replacing large outputs with nothing. A small pure class
(`ContextTrimmer`) decides which ids to delete: keep the latest round, delete older rounds' screenshots and any
output over 1,500 characters. Function-call items themselves stay so the conversation remains valid.

### 2. Retry on rate limits

When `response.done` has `status: failed` with code `rate_limit_exceeded`, wait (the "try again in Xs" hint from
the message if present, else 2 s, doubling, max 3 tries, capped at 20 s) and send `response.create` again. Status
line: "One sec, OpenAI asked me to slow down…". After the last try, show the error as now.

### 3. Codex for long jobs

The request note tells the model: for jobs that need many steps in other apps, documents or files, or anything
over about five clicks, call `codex_start` with a complete prompt, reply in a few words, and Bluey reports when
Codex finishes (existing Codex notes path). Quick things (look, point, a click or two, typing into the focused
field, opening an app or site) stay with Bluey.

### 4. Modes

A pure `Modes` object: id, label, instructions, ideas (tap-to-send requests).

- **Helper** (default): does what you ask.
- **French tutor**: reply only in French at B2–C1 level; gently correct the user's French in one short note;
  explain the screen in French when asked.
- **Interview coach**: look at the job posting on screen; ask one interview question at a time; after the user's
  answer give one line of feedback and the next question.
- **Roast**: a kind, funny roast of what's on screen (look first, point at the funniest thing).
- **Game buddy**: open and play simple browser games (e.g. Quick, Draw!) with his cursor, with commentary.

The active mode's instructions go into the note sent after each request (so no Mac change is needed). Mode chips
sit top right under the sound and app buttons; the ideas at the bottom right come from the active mode. The mode
is remembered on the phone.

### 5. Stable app signing (so updates install over the current app)

CI builds are signed with a throwaway key, so every update needs an uninstall and a new pairing. A release keystore
is generated once, stored as GitHub Actions secrets (`BLUEY_KEYSTORE_B64`, `BLUEY_STOREPASSWORD`, `BLUEY_KEYALIAS`,
`BLUEY_KEYPASSWORD`), and the workflow writes it to disk and sets the `BLUEY_STOREFILE`… variables the Gradle file
already reads. After one last uninstall, later updates install in place and keep the pairing.

## Testing

- JVM tests: `ContextTrimmer` (keeps the newest round, deletes older screenshots and big outputs, never deletes
  calls), rate-limit hint parsing and backoff, `Modes` (five modes, ideas, French/interview instructions present),
  note building with a mode.
- Screenshots: HUD with mode chips and each mode's ideas.
- Device over USB with logs: a 10-step session doesn't hit `rate_limit_exceeded`; "Open Notes and type hello"
  still types; French tutor answers in French; a long ChatGPT-app request goes to `codex_start`; spend per session
  checked on the OpenAI usage page.

## Out of scope

Mac changes (compact screen reads after actions, a cheaper "mini" realtime model) — later, once Mac rebuilds keep
permissions (Apple Development certificate). iPhone app.
