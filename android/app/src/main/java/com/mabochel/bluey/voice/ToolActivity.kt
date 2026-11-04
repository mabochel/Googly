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
