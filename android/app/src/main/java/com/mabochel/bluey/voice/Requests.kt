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
