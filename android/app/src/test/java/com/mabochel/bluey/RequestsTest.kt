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
