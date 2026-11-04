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

    /**
     * Long jobs go to Codex (the user's ChatGPT plan, no API cost; it can see the screen and use apps too); quick
     * ones stay with Bluey's own cursor. Codex runs with full access, so anything irreversible is confirmed first.
     */
    const val CODEX_HINT = "If you have the codex_start tool: for long jobs on documents, files or code, or anything " +
        "needing many steps across other apps (more than about five actions), call codex_start with a complete prompt " +
        "in the user's words, reply in a few words, and tell the user when Codex finishes. Before handing off anything " +
        "that would send, post, delete, buy or submit something, ask the user first, and unless they explicitly asked " +
        "for it, add \"Don't send, post, delete, buy or submit anything\" to the prompt. Games, pointing and quick " +
        "things (looking, a few clicks, typing, opening an app or site) you do yourself."

    /** The note for a request in the given mode. */
    fun note(mode: Mode): String =
        listOf(NOTE, CODEX_HINT, mode.instructions).filter { it.isNotEmpty() }.joinToString(" ")

    val suggestions: List<Suggestion> get() = Modes.helper.ideas
}
