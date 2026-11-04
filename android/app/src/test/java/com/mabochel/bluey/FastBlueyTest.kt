package com.mabochel.bluey

import com.mabochel.bluey.voice.ContextTrimmer
import com.mabochel.bluey.voice.ContextTrimmer.Item
import com.mabochel.bluey.voice.Modes
import com.mabochel.bluey.voice.RateLimit
import com.mabochel.bluey.voice.Requests
import com.mabochel.bluey.voice.ToolActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastBlueyTest {
    @Test fun fiveModesEachWithIdeas() {
        assertEquals(listOf("helper", "french", "interview", "roast", "game"), Modes.all.map { it.id })
        for (mode in Modes.all) {
            assertTrue(mode.label.isNotBlank())
            assertTrue("${mode.id} needs ideas", mode.ideas.isNotEmpty())
        }
        assertEquals(Modes.helper, Modes.byId("nope"))
        assertEquals(Modes.helper, Modes.byId(null))
        assertEquals(Modes.french, Modes.byId("french"))
    }

    @Test fun notesCarryTheModeAndTheCodexHint() {
        val helper = Requests.note(Modes.helper)
        assertTrue(helper.startsWith(Requests.NOTE))
        // Switching back to Helper must end an earlier French or roast persona.
        assertTrue(helper.contains("Mode: helper. This replaces any earlier mode"))
        assertTrue(Requests.note(Modes.french).contains("only in French"))
        assertTrue(Requests.note(Modes.interview).contains("one realistic interview question at a time"))
        for (mode in Modes.all) {
            val note = Requests.note(mode)
            assertTrue(note.contains("codex_start"))
            assertTrue(note.contains("This replaces any earlier mode"))
            // Codex has full access, so irreversible hand-offs are confirmed first.
            assertTrue(note.contains("ask the user first"))
        }
    }

    private fun out(id: String, screen: Boolean, call: String? = "c$id") =
        Item.Output(id, call, "call_$id", screen, "stub $id")

    @Test fun trimmerKeepsTheScreenThroughRoundsWithoutOne() {
        val trimmer = ContextTrimmer()
        // look_at_screen: the screen arrives.
        assertEquals(emptyList<ContextTrimmer.Action>(), trimmer.roundFinished(listOf(out("look", true), Item.Screenshot("img1"))))
        // point_at / type_text carry no screen: the look stays so he can still answer about it.
        assertEquals(emptyList<ContextTrimmer.Action>(), trimmer.roundFinished(listOf(out("point", false))))
        // A click brings a fresh screen: the old look becomes a stub after its call, its screenshot goes.
        assertEquals(
            listOf(ContextTrimmer.Action.Delete("look"), ContextTrimmer.Action.Stub("clook", "call_look", "stub look"),
                ContextTrimmer.Action.Delete("img1")),
            trimmer.roundFinished(listOf(out("click", true))),
        )
    }

    @Test fun trimmerNeverOrphansACall() {
        val trimmer = ContextTrimmer()
        trimmer.roundFinished(listOf(out("noid", true, call = null), out("small", false)))
        // Without the call's id the stub can't be placed, so that output stays; non-screen outputs always stay.
        assertEquals(emptyList<ContextTrimmer.Action>(), trimmer.roundFinished(listOf(out("next", true))))
    }

    @Test fun trimmerStartsOverForANewSession() {
        val trimmer = ContextTrimmer()
        trimmer.roundFinished(listOf(Item.Screenshot("old")))
        trimmer.reset()
        assertEquals(emptyList<ContextTrimmer.Action>(), trimmer.roundFinished(listOf(Item.Screenshot("new"))))
    }

    @Test fun stubsKeepTheOutcomeAndDropTheScreen() {
        val action = "Clicked \"New Note\".\nHere's the screen now (ids have changed):\nFrontmost app: Notes\nL1 \"secret\""
        assertTrue(ToolActivity.carriesScreen("click", action))
        assertTrue(ToolActivity.carriesScreen("look_at_screen", "Frontmost app: Notes"))
        assertFalse(ToolActivity.carriesScreen("web_research", "Report …---REPORT---…")) // research stays readable
        assertFalse(ToolActivity.carriesScreen("type_text", "Typed it."))
        assertEquals("Clicked \"New Note\". (Screen details removed to save space.)", ToolActivity.stub("click", action))
        assertTrue(ToolActivity.stub("look_at_screen", "Frontmost app: Notes").startsWith("Looked at the screen."))
    }

    @Test fun rateLimitDelays() {
        assertEquals(1_500L, RateLimit.delayMillis("Rate limit reached for gpt-realtime. Please try again in 1.5s.", 0))
        assertEquals(500L, RateLimit.delayMillis("Please try again in 120ms", 0)) // never hammer
        assertEquals(20_000L, RateLimit.delayMillis("Please try again in 95s", 0)) // capped
        assertEquals(2_000L, RateLimit.delayMillis(null, 0))
        assertEquals(4_000L, RateLimit.delayMillis("slow down", 1))
        assertEquals(16_000L, RateLimit.delayMillis(null, 3))
        assertEquals(20_000L, RateLimit.delayMillis(null, 9))
    }
}
