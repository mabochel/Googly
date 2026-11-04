package com.mabochel.bluey

import com.mabochel.bluey.voice.ContextTrimmer
import com.mabochel.bluey.voice.ContextTrimmer.Item
import com.mabochel.bluey.voice.Modes
import com.mabochel.bluey.voice.RateLimit
import com.mabochel.bluey.voice.Requests
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
        assertTrue(helper.contains("codex_start"))
        assertFalse(helper.contains("Mode:"))
        assertTrue(Requests.note(Modes.french).contains("only in French"))
        assertTrue(Requests.note(Modes.interview).contains("one realistic interview question at a time"))
        for (mode in Modes.all) assertTrue(Requests.note(mode).contains("codex_start"))
    }

    @Test fun trimmerKeepsTheNewestRoundAndDropsOldScreens() {
        val trimmer = ContextTrimmer()
        val first = listOf(Item.Output("out1", 13_000, "call1"), Item.Output("out2", 9, "call2"), Item.Screenshot("img1"))
        assertEquals(emptyList<String>(), trimmer.roundFinished(first))
        val second = listOf(Item.Output("out3", 8_000, "call3"), Item.Screenshot("img2"))
        // The big output goes with its call; the small one ("Typed it.") stays; the screenshot goes.
        assertEquals(listOf("out1", "call1", "img1"), trimmer.roundFinished(second))
        assertEquals(listOf("out3", "call3", "img2"), trimmer.roundFinished(listOf(Item.Output("out4", 20, "call4"))))
    }

    @Test fun trimmerStartsOverForANewSession() {
        val trimmer = ContextTrimmer()
        trimmer.roundFinished(listOf(Item.Screenshot("old")))
        trimmer.reset()
        assertEquals(emptyList<String>(), trimmer.roundFinished(listOf(Item.Screenshot("new"))))
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
