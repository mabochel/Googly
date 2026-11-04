package com.mabochel.bluey.voice

/** A tap-to-send idea on the main screen. */
data class Suggestion(val label: String, val request: String)

/** What the phone sends along with each request. */
object Requests {
    /**
     * Sent as a system note right after every held or typed request. Neutral on purpose: the old note told the
     * model to call focus_start whenever words "describe work", so requests like "open Notes and type…" became tasks.
     */
    const val NOTE = "The preceding words were spoken (or typed) to Bluey. Do what they ask with your tools, or answer " +
        "them. Start a focus session only if they clearly say they are about to work on something themselves, or ask " +
        "to focus. Keep existing safety/personality instructions."

    /** Long jobs go to Codex (the user's ChatGPT plan, no API cost); quick ones stay with Bluey's own cursor. */
    const val CODEX_HINT = "For jobs that need many steps in other apps, documents or files (more than about five " +
        "clicks), call codex_start with a complete prompt in the user's words instead of clicking through it " +
        "yourself, reply in a few words, and tell the user when Codex finishes. Quick things (looking, pointing, a " +
        "click or two, typing, opening an app or site) you do yourself."

    /** The note for a request in the given mode. */
    fun note(mode: Mode): String =
        listOf(NOTE, CODEX_HINT, mode.instructions).filter { it.isNotEmpty() }.joinToString(" ")

    val suggestions: List<Suggestion> get() = Modes.helper.ideas
}
