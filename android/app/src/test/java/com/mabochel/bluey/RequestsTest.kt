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

    private val screen = "\nHere's the screen now (ids have changed):\nFrontmost app: Mail\nL1 \"Error: couldn't send\""

    @Test fun failuresAreSurfacedButNormalResultsAreNot() {
        val accessibility = "I need Accessibility permission to click and type. The user has to allow Bluey in " +
            "System Settings, Privacy & Security, Accessibility."
        assertEquals(accessibility, ToolActivity.failure("type_text", accessibility))
        assertEquals("The Mac didn't answer.", ToolActivity.failure("open_app", "The Mac didn't answer."))
        // Action results end with a screen read: only the leading sentence counts, however long the screen is.
        assertEquals("I couldn't find an app called Notez.",
            ToolActivity.failure("open_app", "I couldn't find an app called Notez." + screen))
        assertNull(ToolActivity.failure("open_app", "Opened Notes." + screen))
        assertEquals("That's a password field. Ask the user to type it themselves.",
            ToolActivity.failure("type_text", "That's a password field. Ask the user to type it themselves."))
        assertNull(ToolActivity.failure("type_text", "Typed it."))
        assertNull(ToolActivity.failure("point_at", "Pointing at \"Error log\"."))
    }

    @Test fun focusAndScreenFailuresUseTheMacsOwnWording() {
        assertNull(ToolActivity.failure("focus_start", "Focus started: fix the failed tests, 30 minutes."))
        assertEquals("There's already a focus session. End it before starting another.",
            ToolActivity.failure("focus_start", "There's already a focus session. End it before starting another."))
        assertEquals("I can't see the screen. Screen Recording permission is off for Bluey on the Mac.",
            ToolActivity.failure("look_at_screen", "I can't see the screen. Screen Recording permission is off for Bluey on the Mac."))
        // A screen read can contain any words; only its known error forms count.
        assertNull(ToolActivity.failure("look_at_screen", "Frontmost app: Mail. L1 \"Error: couldn't send\""))
        assertEquals("The Mac didn't answer.", ToolActivity.failure("web_research", "The Mac didn't answer."))
        assertEquals("Research failed: offline. Tell the user in a few words.",
            ToolActivity.failure("web_research", "Research failed: offline. Tell the user in a few words."))
    }

    @Test fun debugLogsNeverContainTypedTextOrScreenText() {
        val args = ToolActivity.redacted("""{"text":"my secret message","press_return":true}""")
        assertFalse(args.contains("secret"))
        assertTrue(args.contains("<17 chars>") && args.contains("press_return"))
        assertEquals("ok (${("Opened Notes." + screen).length} chars)", ToolActivity.logLine("open_app", "Opened Notes." + screen))
        assertEquals("{…}", ToolActivity.redacted("not json"))
        assertEquals("Opening an app…", ToolActivity.label("open_app", """{"name":null}"""))
    }
}
