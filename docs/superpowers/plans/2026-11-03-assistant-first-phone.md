# Assistant-first Bluey phone app — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android app usable for any request (voice or typed), show what Bluey is doing, and keep focus as an
option instead of the whole screen. Spec: `docs/superpowers/specs/2026-11-03-assistant-first-phone-design.md`.

**Architecture:** Two small pure Kotlin units (`Requests`, `ToolActivity`) hold the request note, suggestion chips,
tool labels and failure detection, so they're unit-testable on the JVM. `LiveVoice` uses them and exposes
`activity`, `lastRequest`, `lastReply` and `sendText()`. A new stateless `AssistantHud` composable (driven by a
`HudState` value) replaces the focus-only HUD in `RootScreen`, so screenshot tests can render it without a live Mac.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (BOM 2024.12.01), OkHttp WebSocket (OpenAI Realtime), JUnit 4,
Robolectric + Roborazzi screenshots, GitHub Actions (`.github/workflows/android.yml`), adb for device install.

**No local Android SDK:** this Mac has 3.9 GB free, so Gradle runs in GitHub Actions. The red/green test loop for
Android tasks happens in CI after push (one push per batch of tasks), not locally. Swift (Mac) tests run locally with
`swift test --scratch-path "$HOME/Library/Caches/Bluey/swiftpm-tests"` (the default `.build` on the iCloud-synced
Desktop hangs).

---

### Task 1: Request note, suggestions, tool labels (pure Kotlin)

**Files:**
- Create: `android/app/src/main/java/com/mabochel/bluey/voice/Requests.kt`
- Create: `android/app/src/main/java/com/mabochel/bluey/voice/ToolActivity.kt`
- Test: `android/app/src/test/java/com/mabochel/bluey/RequestsTest.kt`

- [ ] **Step 1: Write the failing tests** — `RequestsTest.kt`:

```kotlin
package com.mabochel.bluey

import com.mabochel.bluey.voice.Requests
import com.mabochel.bluey.voice.ToolActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestsTest {
    @Test fun noteNoLongerPushesEveryRequestIntoFocus() {
        assertFalse(Requests.NOTE.contains("When they describe work"))
        assertFalse(Requests.NOTE.contains("Never merely say you're ready"))
        assertTrue(Requests.NOTE.contains("Do what they ask"))
        assertTrue(Requests.NOTE.contains("only if they clearly say they are about to work on something themselves"))
    }

    @Test fun suggestionsAreRequestsNotFocusTasks() {
        assertEquals(3, Requests.suggestions.size)
        for (s in Requests.suggestions) {
            assertTrue(s.label.isNotBlank() && s.request.isNotBlank())
            assertFalse(s.request.contains("focus", ignoreCase = true))
        }
    }

    @Test fun toolLabels() {
        assertEquals("Opening Notes…", ToolActivity.label("open_app", """{"name":"Notes"}"""))
        assertEquals("Opening an app…", ToolActivity.label("open_app", "not json"))
        assertEquals("Typing…", ToolActivity.label("type_text", """{"text":"hello"}"""))
        assertEquals("Looking at your screen…", ToolActivity.label("look_at_screen"))
        assertEquals("Working with Codex…", ToolActivity.label("codex_start"))
        assertEquals("Working…", ToolActivity.label("something_new"))
    }

    @Test fun failuresAreSurfacedButNormalResultsAreNot() {
        val accessibility = "I need Accessibility permission to click and type. The user has to allow Bluey in " +
            "System Settings, Privacy & Security, Accessibility."
        assertEquals(accessibility, ToolActivity.failure("type_text", accessibility))
        assertEquals("The Mac didn't answer.", ToolActivity.failure("open_app", "The Mac didn't answer."))
        assertNull(ToolActivity.failure("type_text", "Typed it."))
        assertNull(ToolActivity.failure("focus_start", "Focusing on Writing my report for 30 minutes."))
        // A screen read can contain any words; only its known error forms count.
        assertNull(ToolActivity.failure("look_at_screen", "Frontmost app: Mail. L1 \"Error: couldn't send\""))
        assertEquals("Research failed: offline. Tell the user in a few words.",
            ToolActivity.failure("web_research", "Research failed: offline. Tell the user in a few words."))
    }
}
```

- [ ] **Step 2: Implement `Requests.kt`:**

```kotlin
package com.mabochel.bluey.voice

/** A tap-to-send idea on the main screen. */
data class Suggestion(val label: String, val request: String)

/** What the phone sends along with each request, and the ideas it offers. */
object Requests {
    /**
     * Sent as a system note right after every held or typed request. Neutral on purpose: the old note told the
     * model to call focus_start whenever words "describe work", so requests like "open Notes and type…" became tasks.
     */
    const val NOTE = "The preceding words were spoken (or typed) to Bluey. Do what they ask with your tools, or answer " +
        "them. Start a focus session only if they clearly say they are about to work on something themselves, or ask " +
        "to focus. Keep existing safety/personality instructions."

    val suggestions = listOf(
        Suggestion("What's on my screen?", "What's on my screen right now?"),
        Suggestion("Open Notes and type a note", "Open Notes and type: Bluey was here."),
        Suggestion("Look something up", "Look up today's top news headlines."),
    )
}
```

- [ ] **Step 3: Implement `ToolActivity.kt`:**

```kotlin
package com.mabochel.bluey.voice

import com.mabochel.bluey.link.BlueyJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Short status lines for what Bluey is doing on the Mac, and spotting tool results that failed. */
object ToolActivity {
    fun label(name: String, arguments: String = "{}"): String = when (name) {
        "look_at_screen" -> "Looking at your screen…"
        "point_at", "point_at_spot" -> "Pointing…"
        "stop_pointing" -> "Done pointing"
        "open_app" -> argument(arguments, "name")?.let { "Opening $it…" } ?: "Opening an app…"
        "open_url" -> "Opening a website…"
        "type_text" -> "Typing…"
        "click" -> "Clicking…"
        "press_keys" -> "Pressing keys…"
        "scroll" -> "Scrolling…"
        "drag" -> "Dragging…"
        "web_research" -> "Researching…"
        "focus_start" -> "Starting focus…"
        "focus_pause" -> "Pausing focus…"
        "focus_resume" -> "Resuming focus…"
        "focus_end" -> "Ending focus…"
        "focus_status" -> "Checking focus…"
        "go_to_sleep" -> "Going to sleep…"
        else -> if (name.startsWith("codex_")) "Working with Codex…" else "Working…"
    }

    private val failureSigns = listOf("I need", "couldn't", "Couldn't", "can't", "isn't", "didn't answer", "failed",
        "Failed", "error", "Error", "is off", "not allowed", "Unknown tool", "No target", "End it before",
        "Connect the paired phone")

    /**
     * The Mac's message when a tool's output reads like a failure, else null. Screen reads and research reports
     * can contain any words, so only their known error forms count; long outputs are normal results.
     */
    fun failure(name: String, output: String): String? {
        val text = output.trim()
        if (text.isEmpty()) return null
        if (name == "look_at_screen" || name == "web_research") {
            val known = text.startsWith("I need") || text.startsWith("Research failed") || text.startsWith("Screen Recording")
            return if (known) text.take(160) else null
        }
        if (text.length > 240) return null
        return text.takeIf { t -> failureSigns.any { t.contains(it) } }
    }

    private fun argument(arguments: String, key: String): String? =
        runCatching { (BlueyJson.parseToJsonElement(arguments).jsonObject[key] as? JsonPrimitive)?.content }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
```

- [ ] **Step 4: Commit** — `git add` the three files; message "Android: neutral request note, suggestions and tool labels".

### Task 2: LiveVoice — typed requests, activity, logging

**Files:** Modify `android/app/src/main/java/com/mabochel/bluey/voice/LiveVoice.kt`

- [ ] **Step 1: Add state** after `inputLevel`:

```kotlin
    /** What Bluey is doing right now, or the last thing that went wrong. Shown under your request. */
    var activity by mutableStateOf("")
        private set
    /** The last thing you asked, spoken or typed. */
    var lastRequest by mutableStateOf("")
        private set
    /** His last reply (also shown in the bubble on the Mac). */
    var lastReply by mutableStateOf("")
        private set
```

and a private field `private var pendingText: String? = null` next to `askWhenReady`.

- [ ] **Step 2: `sleep()`** — also clear `pendingText = null` and `activity = ""`.
- [ ] **Step 3: `beginAsk()`** — after `holding = true`, clear `activity = ""`, `lastRequest = ""`, `lastReply = ""`.
- [ ] **Step 4: Typed requests** — add after the "Hold to ask" section:

```kotlin
    // MARK: Typed requests

    /** Sends a typed request, waking him first if needed. */
    fun sendText(text: String) {
        val words = text.trim()
        if (words.isEmpty()) return
        pendingText = words
        lastRequest = words
        lastReply = ""
        activity = "Sending…"
        Log.i(TAG, "typed request: $words")
        if (state == State.Asleep) { wake(); return }
        if (socketReady) flushText()
    }

    private fun flushText() {
        val words = pendingText ?: return
        pendingText = null
        if (responseActive) send(obj("type" to "response.cancel"))
        val item = "typed-" + UUID.randomUUID()
        onUserTurn?.invoke(item, true)
        onUserWords?.invoke(item, words, true)
        sendRequest(words)
    }

    /** A request in words (transcribed or typed): the words, the neutral note, then a text-only reply. */
    private fun sendRequest(words: String) {
        Log.i(TAG, "request: $words")
        send(obj("type" to "conversation.item.create", "item" to message("user", words)))
        send(obj("type" to "conversation.item.create", "item" to message("system", Requests.NOTE)))
        send(obj("type" to "response.create",
            "response" to obj("output_modalities" to JsonArray(listOf(JsonPrimitive("text"))))))
        moveTo(State.Thinking)
        activity = "Thinking…"
    }
```

- [ ] **Step 5: `session.created`** — after the `if (askWhenReady) ask() else moveTo(...)` line add
  `if (pendingText != null) flushText()`.
- [ ] **Step 6: transcription completed** — replace the three `send(...)` calls (user words, the old focus note,
  `response.create`) with `lastRequest = words` then `sendRequest(words)`.
- [ ] **Step 7: `response.done`** — set `lastReply = transcript` when the transcript isn't empty; when there are no
  function calls and `activity.endsWith("…")`, set `activity = "Done"` (failures stay visible).
- [ ] **Step 8: `error` event** — after setting `feedback`, also set `activity = feedback`.
- [ ] **Step 9: `runTools`** — per call, before running: `Log.i(TAG, "tool $name $arguments")` and
  `activity = ToolActivity.label(name, arguments)`; in the finish callback, first thing inside `main.post`:
  `Log.i(TAG, "result $name: ${output.take(300)}")` then `ToolActivity.failure(name, output)?.let { activity = it }`.
- [ ] **Step 10: Commit** — "Android: typed requests, activity status and tool logging".

### Task 3: AssistantHud (stateless) and TypeSheet

**Files:** Create `android/app/src/main/java/com/mabochel/bluey/ui/AssistantHud.kt` containing:
`HudState` (connected, headline, status, reply, nudge, voice, inputLevel, session, focusStatus, focusFresh),
`hudState(model)`, `AssistantHud(state, onType, onFocus, onPair, onSuggestion, onFocusCommand)`, private
`FocusCorner`, `SuggestionChip`, `VoiceIcon`, `TypeSheet(send, close)`, and the status helpers moved from
`RootScreen.kt` (`voiceIcon`, `voiceStatus`, `focusStatus`). Full code is the file as committed; behaviour:

- Top left: headline (last request, or "Hold Bluey or type to ask anything" / "Pair your Mac to get started"),
  status (activity → feedback → voice status; "Pair or connect your Mac" when disconnected), reply, nudge, mic level.
- Bottom left: Pair (when disconnected), keyboard ("Type to Bluey", disabled when disconnected), Focus, voice icon.
- Bottom right: focus corner (task · status, pause/resume, stop, 44sp clock) only when a session exists; otherwise
  the three suggestion chips when connected and not busy (waking, asking, thinking, speaking).

- [ ] Commit — "Android: assistant-first HUD and type sheet".

### Task 4: RootScreen wiring

**Files:** Modify `android/app/src/main/java/com/mabochel/bluey/ui/RootScreen.kt`

- [ ] `RootUiState`: add `var showTyping by mutableStateOf(false)`.
- [ ] Replace `FocusFaceHud(...)` with `AssistantHud(hudState(model), onType = { ui.showTyping = true },
  onFocus = { ui.showFocus = true }, onPair = { ui.showPairSheet = true }, onSuggestion = { live.sendText(it.request) },
  onFocusCommand = { model.focus.command(it) })`.
- [ ] After the focus dashboard block: `if (ui.showTyping) { BackHandler { ui.showTyping = false };
  TypeSheet(send = { live.sendText(it) }) { ui.showTyping = false } }`.
- [ ] Delete `FocusFaceHud`, `hudStatus`, `voiceIcon`, `voiceStatus` (moved to AssistantHud.kt).
- [ ] Pairing intro headline: "Your little blueberry helper".
- [ ] Commit — "Android: main screen uses the assistant HUD".

### Task 5: Screenshot tests

**Files:** Modify `android/app/src/test/java/com/mabochel/bluey/ScreenshotTest.kt` — add renders `08-assistant-idle`,
`09-assistant-working`, `10-assistant-done`, `11-type-sheet` (FaceView + `AssistantHud(HudState(...))` with
connected = true). Existing `04-hud` keeps covering a running focus session through `RootScreen`.

- [ ] Commit — "Android: screenshots for the assistant HUD".

### Task 6: CI uploads screenshots

**Files:** Modify `.github/workflows/android.yml` — after "Test and build", add a step running
`./gradlew --no-daemon testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'` in `android/`, and upload
`android/app/build/screenshots/` as artifact `bluey-android-screens` (`if: always()`).

- [ ] Commit — "CI: upload Android screenshots".

### Task 7: Mac live probe uses the new note; README

**Files:** Modify `Tests/GooglyMacTests/VoiceRoutingLiveTests.swift` (`phoneNote` → the new `Requests.NOTE` text,
old text kept as `legacyPhoneNote`), `android/README.md` ("Using it": ask anything by holding or typing, chips,
status line, focus optional).

- [ ] Run `swift test --scratch-path "$HOME/Library/Caches/Bluey/swiftpm-tests"` — expected: passes, live probes skipped.
- [ ] Commit — "Probe and docs match the new phone note".

### Task 8: Review, push, CI, install, verify on device

- [ ] Code review (superpowers:requesting-code-review) of the branch diff; fix findings.
- [ ] `git push -u origin fix/voice-assistant-mode`; watch the "Android APK" run (`gh run watch`).
- [ ] Download `bluey-android-apk` and `bluey-android-screens` (`gh run download`), look at the screenshots.
- [ ] User uninstalls the old Bluey app (different signing key); `adb install app-release.apk`; user pairs again.
- [ ] `adb logcat -s BlueyVoice` while the user says/types "Open Notes and type hello from Bluey", "What's on my
  screen?", "I'm writing my report for 30 minutes". Expected: `tool open_app {"name":"Notes"}` … `type_text`;
  `look_at_screen`; `focus_start`. Fix whatever the log shows.
