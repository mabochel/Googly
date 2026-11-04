# Fast Bluey — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Keep the fast Realtime voice model (pointing, clicking, typing) but stop the rate-limit stalls and most of
the cost, hand long jobs to Codex, add five modes, and sign CI builds with a stable key.
Spec: `docs/superpowers/specs/2026-11-04-fast-bluey-design.md`.

**Architecture:** Three new pure Kotlin units (`Modes`, `ContextTrimmer`, `RateLimit`) plus `Requests.note(mode)`,
unit-tested on the JVM. `LiveVoice` gives ids to the items it creates, asks `ContextTrimmer` what to delete after
each tool round, retries `rate_limit_exceeded` with `RateLimit`'s delay, and sends the mode-aware note. The HUD
gets a mode picker and per-mode ideas. CI writes a keystore from secrets. No Mac changes.

**Tech Stack:** Kotlin, Compose, OpenAI Realtime events (`conversation.item.create` with client ids,
`conversation.item.delete`), JUnit, Roborazzi, GitHub Actions secrets, openssl (PKCS12 keystore).

Android builds and tests run in GitHub Actions (no local SDK; 3.9 GB free).

---

### Task 1: Modes and mode-aware notes
- Create `voice/Modes.kt`: `data class Mode(id, label, instructions, ideas)`, `Modes.all` (helper, french,
  interview, roast, game), `Modes.byId(id)` (falls back to helper).
- `Requests`: keep `NOTE`; add `CODEX_HINT`; `note(mode)` = NOTE + CODEX_HINT + mode instructions;
  `suggestions` = `Modes.helper.ideas`.
- Tests (`RequestsTest`): five modes with ideas; unknown id → helper; `note(french)` contains "only in French";
  `note(helper)` has no "Mode:"; every note contains `codex_start`.

### Task 2: ContextTrimmer
- Create `voice/ContextTrimmer.kt`: records each finished tool round (outputs with size and their call item id,
  screenshots); keeps the newest round; returns ids to delete from older rounds: every screenshot, and outputs over
  1,500 characters together with their function-call item. `reset()` on new sessions.
- Tests: two rounds → older screenshot + big output + its call deleted, small output kept; three rounds; reset.

### Task 3: RateLimit
- Create `voice/RateLimit.kt`: `delayMillis(message, attempt)` parses "try again in 1.5s" / "in 200ms" (clamped to
  0.5–20 s), else 2 s × 2^attempt capped at 20 s; `MAX_TRIES = 3`.
- Tests: seconds hint, ms hint, no hint backoff, cap.

### Task 4: LiveVoice wiring
- Client ids (`b` + 30 hex) on tool outputs and screenshot messages; record function-call item ids from
  `response.done`; after a round, `conversation.item.delete` for the trimmer's ids (event ids start `del_`, their
  errors ignored); trimmer reset on sleep/reconnect.
- `response.done` failed with `rate_limit_exceeded`: schedule `response.create` after `RateLimit.delayMillis`
  (generation-guarded), status "One sec, OpenAI asked me to slow down…", up to `MAX_TRIES`; reset tries on success
  and new requests.
- `mode` (persisted in the voice prefs) and `sendRequest` uses `Requests.note(mode)`.

### Task 5: HUD
- `HudState.mode`; ideas from `mode.ideas`; a mode button top right (under sound/app) opening a menu of the five
  modes; `RootScreen` passes `onMode = { live.mode = it }`.
- Screenshots for French tutor ideas and the open mode menu.

### Task 6: Stable signing
- Generate a PKCS12 keystore with openssl, keep it and its password in `~/Library/Application Support/Googly/
  android-signing/` (0600), set secrets `BLUEY_KEYSTORE_B64`, `BLUEY_STOREPASSWORD`, `BLUEY_KEYALIAS`,
  `BLUEY_KEYPASSWORD` on `mabochel/Googly`; workflow decodes the keystore to `$RUNNER_TEMP` and exports
  `BLUEY_STOREFILE` when the secret exists.

### Task 7: Review, build, install, verify
- Code review subagent; fix findings. Push; CI green; check screenshots; verify the APK's signing certificate.
- User uninstalls the current app once; `adb install`; pair; test a 10+ step session with logs (no
  `rate_limit_exceeded`), French tutor, a Codex hand-off; compare spend on the usage page.
