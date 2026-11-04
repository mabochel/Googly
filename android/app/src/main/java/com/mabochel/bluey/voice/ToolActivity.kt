package com.mabochel.bluey.voice

import com.mabochel.bluey.link.BlueyJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

    /** The Mac appends a fresh screen read to action results after this line (RealtimeHost.look(prefix:)). */
    private const val SCREEN_MARKER = "\nHere's the screen now"

    /** How the Mac's focus tools report success (FocusCompanion); anything else from them is a problem. */
    private val focusSuccess = listOf("Focus started", "Focus paused", "Focus resumed", "Focus finished", "Task:",
        "No focus session yet")

    /** Phrases the Mac uses when an action didn't happen (RealtimeHost, ComputerControl, MacLink). */
    private val failureSigns = listOf("I need", "I can't", "couldn't", "Couldn't", "can't", "don't know",
        "doesn't look like", "didn't answer", "isn't connected", "failed", "Failed", "is off", "not allowed",
        "turned off", "pressed stop", "password field", "Unknown tool", "No target", "Call look_at_screen first")

    /**
     * The Mac's message when a tool didn't work, else null. Only the result's leading sentence counts (action
     * results end with a screen read that can contain any words); screen reads and research reports only count
     * in their known error forms.
     */
    fun failure(name: String, output: String): String? {
        val lead = output.substringBefore(SCREEN_MARKER).trim()
        if (lead.isEmpty()) return null
        val problem = when {
            name == "look_at_screen" -> lead.takeIf { it.startsWith("I can't see the screen") || it.startsWith("The Mac") }
            name == "web_research" -> lead.takeIf { it.startsWith("Research failed") || it.startsWith("The Mac") }
            name.startsWith("focus_") -> lead.takeIf { l -> focusSuccess.none { l.startsWith(it) } }
            lead.length > 240 -> null
            else -> lead.takeIf { l -> failureSigns.any { l.contains(it) } }
        }
        return problem?.take(200)
    }

    /** Tool arguments for the debug log, with typed text, prompts and questions replaced by their length. */
    fun redacted(arguments: String): String = runCatching {
        val args = BlueyJson.parseToJsonElement(arguments).jsonObject
        JsonObject(args.mapValues { (key, value) ->
            if (key in privateKeys) JsonPrimitive("<${(value as? JsonPrimitive)?.contentOrNull?.length ?: 0} chars>") else value
        }).toString()
    }.getOrDefault("{…}")

    /** A tool result for the debug log: the failure, or just its size (results carry screen text). */
    fun logLine(name: String, output: String): String =
        failure(name, output)?.let { "failed: $it" } ?: "ok (${output.length} chars)"

    private val privateKeys = setOf("text", "prompt", "question")

    private fun argument(arguments: String, key: String): String? =
        runCatching { (BlueyJson.parseToJsonElement(arguments).jsonObject[key] as? JsonPrimitive)?.contentOrNull }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
